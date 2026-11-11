package com.hololo.app.dnschanger.utils;

import static com.hololo.app.dnschanger.dns.Fixtures.*;
import static org.junit.Assert.*;

import com.hololo.app.dnschanger.dns.DnsMessage;
import com.hololo.app.dnschanger.dns.Fixtures;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public class CacheAndBlocklistTest {

    @Test
    public void entriesExpireAtTtl() {
        AtomicLong now = new AtomicLong(0);
        DNSCache cache = new DNSCache(10, now::get);
        byte[] q = query(1, "example.com", 1);
        assertTrue(cache.put("example.com", 1, 1, answer(q, 10)));
        now.set(9_999);
        assertNotNull(cache.get("example.com", 1, 1, 77));
        now.set(10_000);
        assertNull(cache.get("example.com", 1, 1, 77));
    }

    @Test
    public void servedTtlIsAgedAndIdRewritten() {
        AtomicLong now = new AtomicLong(0);
        DNSCache cache = new DNSCache(10, now::get);
        cache.put("example.com", 1, 1, answer(query(1, "example.com", 1), 100));
        now.set(40_000);
        byte[] r = cache.get("example.com", 1, 1, 0xABCD);
        assertEquals(0xABCD, DnsMessage.id(r));
        assertEquals(60, DnsMessage.cacheTtlSeconds(r));
    }

    @Test
    public void errorsAndTruncatedResponsesAreNotCached() {
        DNSCache cache = new DNSCache(10, () -> 0);
        byte[] q = query(1, "example.com", 1);
        assertFalse(cache.put("example.com", 1, 1, Fixtures.response(q, 2, new long[0], -1, -1, false)));
        byte[] tc = answer(q, 100);
        tc[2] |= 0x02;
        assertFalse(cache.put("example.com", 1, 1, tc));
        assertFalse("zero TTL", cache.put("example.com", 1, 1, answer(q, 0)));
        assertEquals(0, cache.size());
    }

    @Test
    public void typeAndClassAreSeparateKeys() {
        DNSCache cache = new DNSCache(10, () -> 0);
        cache.put("example.com", 1, 1, answer(query(1, "example.com", 1), 100));
        assertNull(cache.get("example.com", 28, 1, 1));
        assertNull(cache.get("example.com", 1, 3, 1));
    }

    /** Regression: the old cache cleared *everything* when full. */
    @Test
    public void lruEvictsOnlyLeastRecentlyUsed() {
        DNSCache cache = new DNSCache(3, () -> 0);
        for (String n : new String[]{"a.com", "b.com", "c.com"}) {
            cache.put(n, 1, 1, answer(query(1, n, 1), 100));
        }
        assertNotNull(cache.get("a.com", 1, 1, 1)); // touch a
        cache.put("d.com", 1, 1, answer(query(1, "d.com", 1), 100));
        assertEquals(3, cache.size());
        assertNotNull(cache.get("a.com", 1, 1, 1));
        assertNull(cache.get("b.com", 1, 1, 1));
        assertNotNull(cache.get("d.com", 1, 1, 1));
    }

    @Test
    public void blocklistMatchesDomainAndSubdomainsOnly() {
        BlockManager blocks = new BlockManager();
        assertTrue(blocks.isBlocked("doubleclick.net"));
        assertTrue(blocks.isBlocked("ad.g.DoubleClick.net."));
        assertFalse("suffix of a label is not a subdomain", blocks.isBlocked("notdoubleclick.net"));
        assertFalse(blocks.isBlocked("doubleclick.net.example.com"));
        assertFalse(blocks.isBlocked(""));
        assertFalse(blocks.isBlocked(null));
    }

    @Test
    public void blocklistImportFormats() {
        BlockManager blocks = new BlockManager();
        String list = "# comment\n0.0.0.0 tracker.one\n127.0.0.1 tracker.two\naddress=/tracker.three/0.0.0.0\n"
                + "||tracker.four^\nplain-tracker.five\n!adguard comment\n";
        blocks.importFromStream(new ByteArrayInputStream(list.getBytes(StandardCharsets.UTF_8)));
        for (String d : new String[]{"tracker.one", "x.tracker.two", "tracker.three", "tracker.four", "plain-tracker.five"}) {
            assertTrue(d, blocks.isBlocked(d));
        }
    }

    @Test
    public void blocklistLookupIsFastForLargeLists() {
        BlockManager blocks = new BlockManager();
        for (int i = 0; i < 200_000; i++) blocks.addBlockedDomain("host" + i + ".ads.example");
        long start = System.nanoTime();
        for (int i = 0; i < 20_000; i++) blocks.isBlocked("www.l1.l2.l3.allowed" + i + ".example.org");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue("suffix lookup should be O(labels), took " + ms + "ms", ms < 2000);
    }
}
