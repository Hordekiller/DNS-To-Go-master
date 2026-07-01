package com.hololo.app.dnschanger.dnschanger;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;

public final class DnsPacketHandler {

    private static final int DNS_HEADER_LENGTH = 12;
    private static final int UDP_HEADER_LENGTH = 8;
    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int IPV6_HEADER_LENGTH = 40;

    public static boolean isDnsPacket(ByteBuffer packet, int length) {
        if (length < IPV4_MIN_HEADER_LENGTH) return false;
        int version = (packet.get(0) >> 4) & 0x0F;
        if (version == 4) {
            return isIPv4DnsQuery(packet, length);
        } else if (version == 6 && length >= IPV6_HEADER_LENGTH) {
            return isIPv6DnsQuery(packet, length);
        }
        return false;
    }

    private static boolean isIPv4DnsQuery(ByteBuffer packet, int length) {
        int ihl = (packet.get(0) & 0x0F) * 4;
        int totalLength = packet.getShort(2) & 0xFFFF;
        if (ihl < IPV4_MIN_HEADER_LENGTH || ihl > length) return false;
        if (totalLength < ihl + UDP_HEADER_LENGTH || totalLength > length) return false;

        byte protocol = packet.get(9);
        if (protocol != 17) return false;
        int udpOffset = ihl;
        if (udpOffset + UDP_HEADER_LENGTH > totalLength) return false;
        int destPort = packet.getShort(ihl + 2) & 0xFFFF;
        int udpLength = packet.getShort(ihl + 4) & 0xFFFF;
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > totalLength) return false;
        return destPort == 53;
    }

    private static boolean isIPv6DnsQuery(ByteBuffer packet, int length) {
        if (length < IPV6_HEADER_LENGTH) return false;
        int payloadLength = packet.getShort(4) & 0xFFFF;
        int packetLength = IPV6_HEADER_LENGTH + payloadLength;
        if (packetLength > length) return false;

        int nextHeader = packet.get(6) & 0xFF;
        int headerOffset = IPV6_HEADER_LENGTH;

        while (isIPv6ExtensionHeader(nextHeader)) {
            if (headerOffset + 2 > packetLength) return false;
            int extHdrLen;
            if (nextHeader == 44) {
                extHdrLen = 8;
            } else {
                extHdrLen = (packet.get(headerOffset + 1) & 0xFF) * 8 + 8;
            }
            headerOffset += extHdrLen;
            if (headerOffset >= packetLength) return false;
            nextHeader = packet.get(headerOffset) & 0xFF;
        }

        if (nextHeader != 17) return false;
        int udpOffset = headerOffset;
        if (udpOffset + UDP_HEADER_LENGTH > packetLength) return false;
        int destPort = packet.getShort(udpOffset + 2) & 0xFFFF;
        int udpLength = packet.getShort(udpOffset + 4) & 0xFFFF;
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > packetLength) return false;
        return destPort == 53;
    }

    public static boolean isIPv6ExtensionHeader(int nextHeader) {
        return nextHeader == 0 || nextHeader == 43 || nextHeader == 44
                || nextHeader == 50 || nextHeader == 51 || nextHeader == 60
                || nextHeader == 135;
    }

    public static String parseDomainName(ByteBuffer packet) {
        StringBuilder domain = new StringBuilder();
        for (int depth = 0; depth < 10; depth++) {
            if (!packet.hasRemaining()) break;
            int labelLength = packet.get() & 0xFF;
            if (labelLength == 0) break;

            if ((labelLength & 0xC0) == 0xC0) {
                if (packet.hasRemaining()) {
                    packet.get();
                    if (domain.length() == 0) domain.append("[compressed]");
                }
                break;
            }

            if (packet.remaining() < labelLength) break;
            for (int i = 0; i < labelLength; i++) {
                domain.append((char) packet.get());
            }
            domain.append(".");
        }
        if (domain.length() > 0 && domain.charAt(domain.length() - 1) == '.') {
            domain.setLength(domain.length() - 1);
        }
        return domain.length() == 0 ? "unknown" : domain.toString();
    }

    public static void skipDomainName(ByteBuffer buf) {
        int depth = 0;
        while (buf.hasRemaining() && depth++ < 10) {
            int len = buf.get() & 0xFF;
            if (len == 0) return;
            if ((len & 0xC0) == 0xC0) {
                if (buf.hasRemaining()) buf.get();
                return;
            }
            if (buf.remaining() < len) break;
            buf.position(buf.position() + len);
        }
    }

    public static DnsQueryResult parseDnsQuery(ByteBuffer packet, int length) {
        if (length < IPV4_MIN_HEADER_LENGTH) return null;

        int version = (packet.get(0) >> 4) & 0x0F;
        if (version == 4) {
            return parseIPv4DnsQuery(packet, length);
        } else if (version == 6 && length >= IPV6_HEADER_LENGTH) {
            return parseIPv6DnsQuery(packet, length);
        }
        return null;
    }

    private static DnsQueryResult parseIPv4DnsQuery(ByteBuffer packet, int length) {
        int ihl = (packet.get(0) & 0x0F) * 4;
        int totalLength = packet.getShort(2) & 0xFFFF;
        if (ihl < IPV4_MIN_HEADER_LENGTH || ihl > length) return null;
        if (totalLength < ihl + UDP_HEADER_LENGTH || totalLength > length) return null;

        byte protocol = packet.get(9);
        if (protocol != 17) return null;
        int udpOffset = ihl;
        if (udpOffset + UDP_HEADER_LENGTH > totalLength) return null;
        int destPort = packet.getShort(ihl + 2) & 0xFFFF;
        int udpLength = packet.getShort(ihl + 4) & 0xFFFF;
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > totalLength) return null;
        if (destPort != 53) return null;

        return extractDnsQuery(packet, udpOffset + UDP_HEADER_LENGTH, udpOffset + udpLength);
    }

    private static DnsQueryResult parseIPv6DnsQuery(ByteBuffer packet, int length) {
        if (length < IPV6_HEADER_LENGTH) return null;
        int payloadLength = packet.getShort(4) & 0xFFFF;
        int packetLength = IPV6_HEADER_LENGTH + payloadLength;
        if (packetLength > length) return null;

        int nextHeader = packet.get(6) & 0xFF;
        int headerOffset = IPV6_HEADER_LENGTH;

        while (isIPv6ExtensionHeader(nextHeader)) {
            if (headerOffset + 2 > packetLength) return null;
            int extHdrLen;
            if (nextHeader == 44) {
                extHdrLen = 8;
            } else {
                extHdrLen = (packet.get(headerOffset + 1) & 0xFF) * 8 + 8;
            }
            headerOffset += extHdrLen;
            if (headerOffset >= packetLength) return null;
            nextHeader = packet.get(headerOffset) & 0xFF;
        }

        if (nextHeader != 17) return null;
        int udpOffset = headerOffset;
        if (udpOffset + UDP_HEADER_LENGTH > packetLength) return null;
        int destPort = packet.getShort(udpOffset + 2) & 0xFFFF;
        int udpLength = packet.getShort(udpOffset + 4) & 0xFFFF;
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > packetLength) return null;
        if (destPort != 53) return null;

        return extractDnsQuery(packet, udpOffset + UDP_HEADER_LENGTH, udpOffset + udpLength);
    }

    private static DnsQueryResult extractDnsQuery(ByteBuffer packet, int dnsOffset, int dnsEnd) {
        if (dnsOffset < 0 || dnsEnd > packet.limit() || dnsEnd - dnsOffset < DNS_HEADER_LENGTH) return null;

        ByteBuffer dnsBuffer = packet.duplicate();
        dnsBuffer.position(dnsOffset);
        dnsBuffer.limit(dnsEnd);

        int transactionId = dnsBuffer.getShort() & 0xFFFF;
        dnsBuffer.getShort();
        int qdCount = dnsBuffer.getShort() & 0xFFFF;

        if (qdCount <= 0 || dnsBuffer.remaining() < 6) return null;
        dnsBuffer.getShort();
        dnsBuffer.getShort();
        dnsBuffer.getShort();

        String domain = parseDomainName(dnsBuffer);
        if (dnsBuffer.remaining() < 4) return null;
        int type = dnsBuffer.getShort() & 0xFFFF;
        dnsBuffer.getShort();

        int rawQueryLength = dnsEnd - dnsOffset;
        if (rawQueryLength <= 0) return null;
        byte[] rawQuery = new byte[rawQueryLength];
        ByteBuffer rawQueryBuffer = packet.duplicate();
        rawQueryBuffer.position(dnsOffset);
        rawQueryBuffer.limit(dnsEnd);
        rawQueryBuffer.get(rawQuery);

        byte[] originalPacket = new byte[packet.limit()];
        packet.duplicate().get(originalPacket);

        return new DnsQueryResult(transactionId, domain, type, rawQuery, originalPacket, dnsOffset);
    }

    public static byte[] addEdnsPadding(byte[] query, int maxPaddingLen) {
        if (query.length < 12) return query;

        int arCount = ((query[10] & 0xFF) << 8) | (query[11] & 0xFF);
        if (arCount > 0) return query;

        int padLen = ThreadLocalRandom.current().nextInt(1, Math.max(2, maxPaddingLen + 1));
        int optionLen = 4 + padLen;
        int optRecordLen = 1 + 2 + 2 + 4 + 2 + optionLen;

        ByteBuffer result = ByteBuffer.allocate(query.length + optRecordLen);
        result.put(query);

        result.put((byte) 0);
        result.putShort((short) 41);
        result.putShort((short) 1232);
        result.put((byte) 0);
        result.put((byte) 0);
        result.put((byte) 0);
        result.put((byte) 0);
        result.putShort((short) optionLen);
        result.putShort((short) 0x000C);
        result.putShort((short) padLen);
        for (int i = 0; i < padLen; i++) {
            result.put((byte) 0);
        }

        int newArCount = arCount + 1;
        result.put(10, (byte) ((newArCount >> 8) & 0xFF));
        result.put(11, (byte) (newArCount & 0xFF));

        return result.array();
    }

    public static final class DnsQueryResult {
        public final int transactionId;
        public final String domain;
        public final int type;
        public final byte[] rawQuery;
        public final byte[] originalPacket;
        public final int dnsOffset;

        public DnsQueryResult(int transactionId, String domain, int type,
                              byte[] rawQuery, byte[] originalPacket, int dnsOffset) {
            this.transactionId = transactionId;
            this.domain = domain;
            this.type = type;
            this.rawQuery = rawQuery;
            this.originalPacket = originalPacket;
            this.dnsOffset = dnsOffset;
        }
    }
}
