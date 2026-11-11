package com.hololo.app.dnschanger.dns;

/**
 * Minimal, allocation-light parser and builder for the IPv4/IPv6 + UDP/TCP packets
 * that arrive on the TUN interface. Pure Java (JVM-testable).
 *
 * <p>Only what DNS interception needs is supported. Anything we cannot handle
 * safely (fragments, ESP, unknown extension headers, inconsistent lengths) is
 * rejected by returning {@code null}.
 */
public final class IpPacket {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;
    public static final int TCP_FIN = 0x01;
    public static final int TCP_SYN = 0x02;
    public static final int TCP_RST = 0x04;
    public static final int TCP_ACK = 0x10;

    private static final int IPV4_MIN_HEADER = 20;
    private static final int IPV6_HEADER = 40;
    private static final int UDP_HEADER = 8;
    private static final int TCP_MIN_HEADER = 20;

    public final int version;
    /** Raw source / destination address bytes (4 or 16). */
    public final byte[] src;
    public final byte[] dst;
    /** Upper-layer protocol after all extension headers. */
    public final int protocol;
    /** Offset of the transport (UDP/TCP) header. */
    public final int l4Offset;
    /** Offset / end of the transport payload. */
    public final int payloadOffset;
    public final int payloadEnd;
    public final int srcPort;
    public final int dstPort;
    /** TCP only. */
    public final long tcpSeq;
    public final long tcpAck;
    public final int tcpFlags;

    private IpPacket(int version, byte[] src, byte[] dst, int protocol, int l4Offset,
                     int payloadOffset, int payloadEnd, int srcPort, int dstPort,
                     long tcpSeq, long tcpAck, int tcpFlags) {
        this.version = version;
        this.src = src;
        this.dst = dst;
        this.protocol = protocol;
        this.l4Offset = l4Offset;
        this.payloadOffset = payloadOffset;
        this.payloadEnd = payloadEnd;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.tcpSeq = tcpSeq;
        this.tcpAck = tcpAck;
        this.tcpFlags = tcpFlags;
    }

    public int payloadLength() {
        return payloadEnd - payloadOffset;
    }

    public byte[] copyPayload(byte[] packet) {
        byte[] out = new byte[payloadLength()];
        System.arraycopy(packet, payloadOffset, out, 0, out.length);
        return out;
    }

    /** Parses a raw IP packet of {@code length} bytes. Returns null if unsupported. */
    public static IpPacket parse(byte[] packet, int length) {
        if (packet == null || length <= 0 || length > packet.length) return null;
        int version = (packet[0] >> 4) & 0x0F;
        if (version == 4) return parseIpv4(packet, length);
        if (version == 6) return parseIpv6(packet, length);
        return null;
    }

    private static IpPacket parseIpv4(byte[] p, int length) {
        if (length < IPV4_MIN_HEADER) return null;
        int ihl = (p[0] & 0x0F) * 4;
        int totalLength = u16(p, 2);
        if (ihl < IPV4_MIN_HEADER || totalLength < ihl || totalLength > length) return null;
        int fragField = u16(p, 6);
        boolean moreFragments = (fragField & 0x2000) != 0;
        int fragOffset = fragField & 0x1FFF;
        if (moreFragments || fragOffset != 0) return null; // cannot reassemble
        byte[] src = slice(p, 12, 4);
        byte[] dst = slice(p, 16, 4);
        return parseTransport(4, src, dst, p[9] & 0xFF, p, ihl, totalLength);
    }

    private static IpPacket parseIpv6(byte[] p, int length) {
        if (length < IPV6_HEADER) return null;
        int end = IPV6_HEADER + u16(p, 4);
        if (end > length) return null;
        int next = p[6] & 0xFF;
        int offset = IPV6_HEADER;
        // RFC 8200 4.x: the Next Header field is the first octet of each extension
        // header and must be read *before* advancing past that header.
        for (int guard = 0; guard < 16; guard++) {
            int hdrLen;
            switch (next) {
                case 0:   // Hop-by-Hop
                case 43:  // Routing
                case 60:  // Destination Options
                case 135: // Mobility
                    if (offset + 2 > end) return null;
                    hdrLen = ((p[offset + 1] & 0xFF) + 1) * 8;
                    break;
                case 44:  // Fragment
                    if (offset + 8 > end) return null;
                    int fragField = u16(p, offset + 2);
                    if ((fragField & 0xFFF8) != 0 || (fragField & 0x1) != 0) return null;
                    hdrLen = 8; // atomic fragment; carry on
                    break;
                case 51:  // Authentication Header: length in 4-octet units, minus 2
                    if (offset + 2 > end) return null;
                    hdrLen = ((p[offset + 1] & 0xFF) + 2) * 4;
                    break;
                default:
                    // Upper-layer protocol (or ESP/No-Next-Header which we cannot parse)
                    if (next == 50 || next == 59) return null;
                    byte[] src = slice(p, 8, 16);
                    byte[] dst = slice(p, 24, 16);
                    return parseTransport(6, src, dst, next, p, offset, end);
            }
            if (offset + hdrLen > end) return null;
            next = p[offset] & 0xFF;
            offset += hdrLen;
        }
        return null; // too many extension headers
    }

    private static IpPacket parseTransport(int version, byte[] src, byte[] dst, int proto,
                                           byte[] p, int l4, int end) {
        if (proto == PROTO_UDP) {
            if (l4 + UDP_HEADER > end) return null;
            int udpLength = u16(p, l4 + 4);
            if (udpLength < UDP_HEADER || l4 + udpLength > end) return null;
            return new IpPacket(version, src, dst, proto, l4, l4 + UDP_HEADER, l4 + udpLength,
                    u16(p, l4), u16(p, l4 + 2), 0, 0, 0);
        }
        if (proto == PROTO_TCP) {
            if (l4 + TCP_MIN_HEADER > end) return null;
            int dataOffset = ((p[l4 + 12] >> 4) & 0x0F) * 4;
            if (dataOffset < TCP_MIN_HEADER || l4 + dataOffset > end) return null;
            return new IpPacket(version, src, dst, proto, l4, l4 + dataOffset, end,
                    u16(p, l4), u16(p, l4 + 2), u32(p, l4 + 4), u32(p, l4 + 8),
                    p[l4 + 13] & 0x3F);
        }
        return new IpPacket(version, src, dst, proto, l4, l4, end, 0, 0, 0, 0, 0);
    }

    /** Builds a UDP reply to {@code query}: addresses and ports swapped, valid checksums. */
    public static byte[] buildUdpReply(IpPacket query, byte[] payload) {
        int udpLength = UDP_HEADER + payload.length;
        int ipHeader = query.version == 4 ? IPV4_MIN_HEADER : IPV6_HEADER;
        byte[] out = new byte[ipHeader + udpLength];
        writeIpHeader(out, query, PROTO_UDP, udpLength);
        int l4 = ipHeader;
        put16(out, l4, query.dstPort);
        put16(out, l4 + 2, query.srcPort);
        put16(out, l4 + 4, udpLength);
        System.arraycopy(payload, 0, out, l4 + UDP_HEADER, payload.length);
        int sum = transportChecksum(out, query.dst, query.src, PROTO_UDP, l4, udpLength);
        // RFC 768 / RFC 8200 8.1: a computed checksum of zero is transmitted as 0xFFFF.
        put16(out, l4 + 6, sum == 0 ? 0xFFFF : sum);
        return out;
    }

    /**
     * Builds a TCP RST answering {@code segment} (RFC 9293 3.10.7.1), so clients that
     * try DNS-over-TCP to the tunnel fail fast instead of hanging until timeout.
     * Returns null if the segment is itself a RST (never answer a RST).
     */
    public static byte[] buildTcpReset(IpPacket segment) {
        if (segment.protocol != PROTO_TCP || (segment.tcpFlags & TCP_RST) != 0) return null;
        int ipHeader = segment.version == 4 ? IPV4_MIN_HEADER : IPV6_HEADER;
        byte[] out = new byte[ipHeader + TCP_MIN_HEADER];
        writeIpHeader(out, segment, PROTO_TCP, TCP_MIN_HEADER);
        int l4 = ipHeader;
        put16(out, l4, segment.dstPort);
        put16(out, l4 + 2, segment.srcPort);
        long seq;
        long ack;
        int flags;
        if ((segment.tcpFlags & TCP_ACK) != 0) {
            seq = segment.tcpAck;
            ack = 0;
            flags = TCP_RST;
        } else {
            seq = 0;
            long segLen = segment.payloadLength();
            if ((segment.tcpFlags & TCP_SYN) != 0) segLen++;
            if ((segment.tcpFlags & TCP_FIN) != 0) segLen++;
            ack = (segment.tcpSeq + segLen) & 0xFFFFFFFFL;
            flags = TCP_RST | TCP_ACK;
        }
        put32(out, l4 + 4, seq);
        put32(out, l4 + 8, ack);
        out[l4 + 12] = (byte) (5 << 4);
        out[l4 + 13] = (byte) flags;
        put16(out, l4 + 14, 0); // window
        int sum = transportChecksum(out, segment.dst, segment.src, PROTO_TCP, l4, TCP_MIN_HEADER);
        put16(out, l4 + 16, sum);
        return out;
    }

    private static void writeIpHeader(byte[] out, IpPacket query, int proto, int l4Length) {
        if (query.version == 4) {
            int total = IPV4_MIN_HEADER + l4Length;
            out[0] = 0x45;
            put16(out, 2, total);
            put16(out, 6, 0x4000); // DF
            out[8] = 64;           // TTL
            out[9] = (byte) proto;
            System.arraycopy(query.dst, 0, out, 12, 4);
            System.arraycopy(query.src, 0, out, 16, 4);
            put16(out, 10, onesComplement(sum(out, 0, IPV4_MIN_HEADER, 0)));
        } else {
            out[0] = 0x60;
            put16(out, 4, l4Length);
            out[6] = (byte) proto;
            out[7] = 64; // hop limit
            System.arraycopy(query.dst, 0, out, 8, 16);
            System.arraycopy(query.src, 0, out, 24, 16);
        }
    }

    /** Transport checksum over the pseudo-header + segment (checksum field must be 0). */
    static int transportChecksum(byte[] buf, byte[] srcAddr, byte[] dstAddr, int proto,
                                 int l4Offset, int l4Length) {
        long s = sum(srcAddr, 0, srcAddr.length, 0);
        s = sum(dstAddr, 0, dstAddr.length, s);
        if (srcAddr.length == 4) {
            s += proto;
            s += l4Length;
        } else {
            s += (l4Length >>> 16) & 0xFFFF;
            s += l4Length & 0xFFFF;
            s += proto;
        }
        s = sum(buf, l4Offset, l4Length, s);
        return onesComplement(s);
    }

    /** Verifies the checksum of a parsed packet (useful for tests and diagnostics). */
    public static boolean verifyTransportChecksum(byte[] packet, IpPacket parsed) {
        int l4Length = parsed.payloadEnd - parsed.l4Offset;
        long s = sum(parsed.src, 0, parsed.src.length, 0);
        s = sum(parsed.dst, 0, parsed.dst.length, s);
        if (parsed.version == 4) {
            s += parsed.protocol + l4Length;
        } else {
            s += ((l4Length >>> 16) & 0xFFFF) + (l4Length & 0xFFFF) + parsed.protocol;
        }
        s = sum(packet, parsed.l4Offset, l4Length, s);
        return fold(s) == 0xFFFF;
    }

    public static boolean verifyIpv4HeaderChecksum(byte[] packet) {
        int ihl = (packet[0] & 0x0F) * 4;
        return fold(sum(packet, 0, ihl, 0)) == 0xFFFF;
    }

    private static long sum(byte[] b, int off, int len, long acc) {
        int i = off;
        int end = off + len;
        for (; i + 1 < end; i += 2) {
            acc += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
        }
        if (i < end) acc += (b[i] & 0xFF) << 8;
        return acc;
    }

    private static int fold(long s) {
        while ((s >>> 16) != 0) s = (s & 0xFFFF) + (s >>> 16);
        return (int) s;
    }

    private static int onesComplement(long s) {
        return (~fold(s)) & 0xFFFF;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((long) u16(b, off) << 16) | u16(b, off + 2);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }

    private static void put32(byte[] b, int off, long v) {
        put16(b, off, (int) (v >>> 16) & 0xFFFF);
        put16(b, off + 2, (int) v & 0xFFFF);
    }
}
