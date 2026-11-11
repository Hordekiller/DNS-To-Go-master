package com.hololo.app.dnschanger.dns;

import static com.hololo.app.dnschanger.dns.Fixtures.*;
import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Random;

public class DnsMessageTest {

    private static String longName(int labels, String last) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels; i++) sb.append("l").append(i).append('.');
        return sb.append(last).toString();
    }

    /**
     * Regression: the old parser stopped after 10 labels, so names that differ only
     * after the 10th label produced the same cache key / blocklist name, and the
     * QTYPE was read from the middle of the name.
     */
    @Test
    public void parsesNamesWithManyLabels() {
        String a = longName(14, "a.example.com");
        String b = longName(14, "b.example.com");
        DnsMessage.Question qa = DnsMessage.parseQuestion(query(1, a, 28));
        DnsMessage.Question qb = DnsMessage.parseQuestion(query(1, b, 28));
        assertEquals(a, qa.name);
        assertEquals(b, qb.name);
        assertNotEquals(qa.name, qb.name);
        assertEquals(28, qa.type);
        assertEquals(1, qa.qclass);
    }

    @Test
    public void acceptsMaximumLengthNameAndRejectsLonger() {
        // 4 labels of 63 + one of 61 => 253 chars, 255 wire octets.
        String l63 = new String(new char[63]).replace('\0', 'a');
        String l61 = new String(new char[61]).replace('\0', 'b');
        String max = l63 + "." + l63 + "." + l63 + "." + l61;
        assertNotNull(DnsMessage.parseQuestion(query(1, max, 1)));
        assertNull(DnsMessage.parseQuestion(query(1, max + "c", 1)));
    }

    @Test
    public void rejectsOverlongLabel() {
        String l64 = new String(new char[64]).replace('\0', 'a');
        byte[] q = query(1, "ok.com", 1);
        q[12] = 64; // corrupt: label length 64
        assertNull(DnsMessage.parseQuestion(q));
        assertNotNull(l64);
    }

    @Test
    public void rejectsCompressionLoopsAndForwardPointers() {
        byte[] q = query(1, "x.com", 1);
        q[12] = (byte) 0xC0;
        q[13] = 12; // points to itself
        assertNull(DnsMessage.parseQuestion(q));
        q[13] = 40; // forward / out of range
        assertNull(DnsMessage.parseQuestion(q));
    }

    @Test
    public void nonAsciiBytesAreEscapedNotMerged() {
        byte[] q1 = query(1, "abc.com", 1);
        byte[] q2 = query(1, "abc.com", 1);
        q1[13] = (byte) 0xE9; // "\233bc"
        q2[13] = '.';          // a dot *inside* a label
        assertNotEquals(DnsMessage.parseQuestion(q1).name, DnsMessage.parseQuestion(q2).name);
        assertNotEquals("..bc.com", DnsMessage.parseQuestion(q2).name);
    }

    /**
     * Regression: the old NXDOMAIN builder used a 76-byte buffer (BufferOverflowException
     * for names over ~60 chars), always answered QTYPE=A and dropped the RD bit.
     */
    @Test
    public void nxdomainEchoesQuestionForLongNamesAndAnyType() {
        String name = longName(20, "tracker.example.com");
        byte[] q = query(0xBEEF, name, 28);
        byte[] r = DnsMessage.buildErrorResponse(q, DnsMessage.RCODE_NXDOMAIN);
        assertNotNull(r);
        assertTrue(DnsMessage.isResponse(r));
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(r));
        assertEquals(0xBEEF, DnsMessage.id(r));
        assertTrue("RD copied", (DnsMessage.flags(r) & 0x0100) != 0);
        assertTrue(DnsMessage.isValidResponseFor(q, r));
        DnsMessage.Question rq = DnsMessage.parseQuestion(r);
        assertEquals(name, rq.name);
        assertEquals(28, rq.type);
    }

    @Test
    public void servfailForQueryWithEdnsDropsAdditionalSection() {
        byte[] q = query(9, "example.com", 1, true);
        byte[] r = DnsMessage.buildErrorResponse(q, DnsMessage.RCODE_SERVFAIL);
        assertEquals(0, DnsMessage.arCount(r));
        assertTrue(DnsMessage.isValidResponseFor(q, r));
    }

    @Test
    public void validatesResponses() {
        byte[] q = query(0x1000, "example.com", 1);
        byte[] ok = answer(q, 300);
        assertTrue(DnsMessage.isValidResponseFor(q, ok));

        assertFalse("id mismatch", DnsMessage.isValidResponseFor(q, DnsMessage.withId(ok, 0x1001)));
        byte[] notResponse = ok.clone();
        notResponse[2] &= 0x7F;
        assertFalse("QR clear", DnsMessage.isValidResponseFor(q, notResponse));
        assertFalse("other question", DnsMessage.isValidResponseFor(q, answer(query(0x1000, "evil.com", 1), 300)));
        assertFalse("other type", DnsMessage.isValidResponseFor(q, answer(query(0x1000, "example.com", 28), 300)));
        byte[] truncated = java.util.Arrays.copyOf(ok, ok.length - 3);
        assertFalse("record runs past end", DnsMessage.isValidResponseFor(q, truncated));
        assertFalse("too short", DnsMessage.isValidResponseFor(q, new byte[5]));
        assertFalse(DnsMessage.isValidResponseFor(q, null));
    }

    @Test
    public void validationIsCaseInsensitive() {
        byte[] q = query(5, "Example.COM", 1);
        byte[] r = answer(query(5, "example.com", 1), 30);
        assertTrue(DnsMessage.isValidResponseFor(q, r));
    }

    @Test
    public void cacheTtlRules() {
        byte[] q = query(1, "example.com", 1);
        assertEquals(30, DnsMessage.cacheTtlSeconds(answer(q, 300, 30, 120)));
        assertEquals("RFC 2308: min(SOA TTL, MINIMUM)", 60,
                DnsMessage.cacheTtlSeconds(nxdomain(q, 3600, 60)));
        assertEquals(DnsMessage.NOT_CACHEABLE,
                DnsMessage.cacheTtlSeconds(Fixtures.response(q, 2, new long[0], -1, -1, false)));
        assertEquals("NODATA without SOA", DnsMessage.NOT_CACHEABLE,
                DnsMessage.cacheTtlSeconds(Fixtures.response(q, 0, new long[0], -1, -1, false)));
        byte[] tc = answer(q, 300);
        tc[2] |= 0x02;
        assertEquals(DnsMessage.NOT_CACHEABLE, DnsMessage.cacheTtlSeconds(tc));
    }

    @Test
    public void stripsOptAndAgesTtls() {
        byte[] q = query(1, "example.com", 1);
        byte[] withOpt = Fixtures.response(q, 0, new long[]{100}, -1, -1, true);
        byte[] stripped = DnsMessage.stripOpt(withOpt);
        assertEquals(0, DnsMessage.arCount(stripped));
        assertEquals(withOpt.length - 11, stripped.length);
        assertTrue(DnsMessage.isValidResponseFor(q, stripped));

        byte[] aged = DnsMessage.withTtlsReducedBy(answer(q, 100, 5), 10);
        assertEquals(0, DnsMessage.cacheTtlSeconds(aged));
        byte[] aged2 = DnsMessage.withTtlsReducedBy(answer(q, 100), 10);
        assertEquals(90, DnsMessage.cacheTtlSeconds(aged2));
    }

    @Test
    public void malformedInputNeverThrows() {
        Random random = new Random(7);
        byte[] base = nxdomain(query(1, "a.b.example.com", 1), 100, 50);
        for (int i = 0; i < 20000; i++) {
            byte[] m = base.clone();
            for (int f = 0; f < 4; f++) m[random.nextInt(m.length)] = (byte) random.nextInt(256);
            byte[] cut = java.util.Arrays.copyOf(m, random.nextInt(m.length + 1));
            DnsMessage.parseQuestion(cut);
            DnsMessage.cacheTtlSeconds(cut);
            DnsMessage.stripOpt(cut);
            DnsMessage.withTtlsReducedBy(cut, 3);
            DnsMessage.buildErrorResponse(cut, 2);
            DnsMessage.isValidResponseFor(base, cut);
        }
    }
}
