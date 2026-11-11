package com.hololo.app.dnschanger.dns;

import static com.hololo.app.dnschanger.dns.Fixtures.*;
import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

public class IpPacketTest {

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    @Test
    public void parsesIpv4UdpDnsQuery() {
        byte[] q = query(0x1111, "example.com", 1);
        byte[] pkt = ipv4Udp(CLIENT_V4, VDNS_V4, 40000, 53, q);
        IpPacket p = IpPacket.parse(pkt, pkt.length);
        assertNotNull(p);
        assertEquals(4, p.version);
        assertEquals(IpPacket.PROTO_UDP, p.protocol);
        assertEquals(40000, p.srcPort);
        assertEquals(53, p.dstPort);
        assertArrayEquals(q, p.copyPayload(pkt));
    }

    /**
     * Regression: the old parser read the Next Header of an extension header from the
     * byte *after* the header (the UDP source port) and so dropped every IPv6 DNS query
     * carrying Hop-by-Hop / Destination Options headers.
     */
    @Test
    public void parsesIpv6WithExtensionHeaders() {
        byte[] q = query(0x2222, "example.org", 28);
        byte[] pkt = ipv6Udp(CLIENT_V6, VDNS_V6, 5353, 53, q, 0, 60, 44);
        IpPacket p = IpPacket.parse(pkt, pkt.length);
        assertNotNull("IPv6 DNS query with ext headers must be parsed", p);
        assertEquals(IpPacket.PROTO_UDP, p.protocol);
        assertEquals(5353, p.srcPort);
        assertEquals(53, p.dstPort);
        assertEquals(40 + 8 + 16 + 8, p.l4Offset);
        assertArrayEquals(q, p.copyPayload(pkt));
    }

    /**
     * Regression: the old IPv6 reply builder read the ports at fixed offset 40, so with
     * extension headers the reply went to garbage ports and was never delivered.
     */
    @Test
    public void ipv6ReplyUsesRealPortsAndValidChecksum() {
        byte[] q = query(0x3333, "example.net", 28);
        byte[] pkt = ipv6Udp(CLIENT_V6, VDNS_V6, 6000, 53, q, 0);
        IpPacket p = IpPacket.parse(pkt, pkt.length);
        byte[] answer = answer(q, 300);
        byte[] reply = IpPacket.buildUdpReply(p, answer);

        IpPacket r = IpPacket.parse(reply, reply.length);
        assertNotNull(r);
        assertEquals(53, r.srcPort);
        assertEquals(6000, r.dstPort);
        assertArrayEquals(VDNS_V6, r.src);
        assertArrayEquals(CLIENT_V6, r.dst);
        assertArrayEquals(answer, r.copyPayload(reply));
        assertTrue("IPv6 UDP checksum must verify", IpPacket.verifyTransportChecksum(reply, r));
        assertNotEquals("IPv6 UDP checksum must never be 0", 0, u16(reply, 46));
    }

    @Test
    public void ipv4ReplyHasValidHeaderAndUdpChecksums() {
        byte[] q = query(0x4444, "a.b.c.example.com", 1);
        byte[] pkt = ipv4Udp(CLIENT_V4, VDNS_V4, 1234, 53, q);
        byte[] reply = IpPacket.buildUdpReply(IpPacket.parse(pkt, pkt.length), answer(q, 60));
        IpPacket r = IpPacket.parse(reply, reply.length);
        assertNotNull(r);
        assertEquals(53, r.srcPort);
        assertEquals(1234, r.dstPort);
        assertTrue(IpPacket.verifyIpv4HeaderChecksum(reply));
        assertTrue(IpPacket.verifyTransportChecksum(reply, r));
    }

    @Test
    public void rejectsIpv4Fragments() {
        byte[] pkt = ipv4Udp(CLIENT_V4, VDNS_V4, 1, 53, query(1, "x.com", 1));
        pkt[6] = 0x20; // MF
        assertNull(IpPacket.parse(pkt, pkt.length));
        pkt[6] = 0x00;
        pkt[7] = 0x10; // offset != 0
        assertNull(IpPacket.parse(pkt, pkt.length));
    }

    @Test
    public void rejectsNonFirstIpv6Fragment() {
        byte[] pkt = ipv6Udp(CLIENT_V6, VDNS_V6, 1, 53, query(1, "x.com", 1), 44);
        pkt[40 + 3] = 0x08; // fragment offset = 1 (8 bytes)
        assertNull(IpPacket.parse(pkt, pkt.length));
    }

    @Test
    public void rejectsInconsistentLengths() {
        byte[] pkt = ipv4Udp(CLIENT_V4, VDNS_V4, 1, 53, query(1, "x.com", 1));
        assertNull("truncated read", IpPacket.parse(pkt, pkt.length - 5));
        byte[] bad = pkt.clone();
        bad[24] = (byte) 0xFF; // UDP length way beyond packet
        assertNull(IpPacket.parse(bad, bad.length));
    }

    @Test
    public void tcpSynGetsRstAck() {
        byte[] syn = ipv4Tcp(CLIENT_V4, VDNS_V4, 40001, 53, 1000L, 0L, IpPacket.TCP_SYN);
        IpPacket p = IpPacket.parse(syn, syn.length);
        assertNotNull(p);
        byte[] rst = IpPacket.buildTcpReset(p);
        IpPacket r = IpPacket.parse(rst, rst.length);
        assertNotNull(r);
        assertEquals(IpPacket.TCP_RST | IpPacket.TCP_ACK, r.tcpFlags);
        assertEquals(1001L, r.tcpAck);
        assertEquals(53, r.srcPort);
        assertEquals(40001, r.dstPort);
        assertTrue(IpPacket.verifyIpv4HeaderChecksum(rst));
        assertTrue(IpPacket.verifyTransportChecksum(rst, r));
    }

    @Test
    public void ackSegmentGetsPlainRstWithSeqFromAck() {
        byte[] seg = ipv4Tcp(CLIENT_V4, VDNS_V4, 40001, 53, 5L, 777L, IpPacket.TCP_ACK);
        IpPacket r = IpPacket.parse(IpPacket.buildTcpReset(IpPacket.parse(seg, seg.length)), 40);
        assertEquals(IpPacket.TCP_RST, r.tcpFlags);
        assertEquals(777L, r.tcpSeq);
    }

    @Test
    public void neverAnswersRst() {
        byte[] seg = ipv4Tcp(CLIENT_V4, VDNS_V4, 1, 53, 5L, 0L, IpPacket.TCP_RST);
        assertNull(IpPacket.buildTcpReset(IpPacket.parse(seg, seg.length)));
    }

    @Test
    public void fuzzedInputNeverThrows() {
        Random random = new Random(42);
        byte[] base = ipv6Udp(CLIENT_V6, VDNS_V6, 1, 53, query(7, "fuzz.example", 1), 0, 60);
        for (int i = 0; i < 20000; i++) {
            byte[] p = Arrays.copyOf(base, base.length);
            int flips = 1 + random.nextInt(6);
            for (int f = 0; f < flips; f++) p[random.nextInt(p.length)] = (byte) random.nextInt(256);
            int len = random.nextInt(p.length + 1);
            IpPacket parsed = IpPacket.parse(p, len);
            if (parsed != null && parsed.protocol == IpPacket.PROTO_UDP) {
                assertTrue(parsed.payloadEnd <= len);
                DnsMessage.parseQuestion(parsed.copyPayload(p));
            }
        }
    }
}
