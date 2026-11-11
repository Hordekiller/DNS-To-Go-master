package com.hololo.app.dnschanger.dns;

import static com.hololo.app.dnschanger.dns.Fixtures.*;
import static org.junit.Assert.*;

import com.hololo.app.dnschanger.utils.BlockManager;
import com.hololo.app.dnschanger.utils.DNSCache;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DnsQueryProcessorTest {

    /** Scriptable upstream. */
    static final class FakeUpstream implements DnsQueryProcessor.Upstream {
        interface Behaviour { byte[] answer(byte[] q) throws Exception; }

        final String label;
        final boolean encrypted;
        final Behaviour behaviour;
        final AtomicInteger calls = new AtomicInteger();
        final List<byte[]> seen = Collections.synchronizedList(new ArrayList<>());

        FakeUpstream(String label, boolean encrypted, Behaviour behaviour) {
            this.label = label;
            this.encrypted = encrypted;
            this.behaviour = behaviour;
        }

        @Override public String label() { return label; }
        @Override public boolean encrypted() { return encrypted; }
        @Override public byte[] exchange(byte[] q) throws Exception {
            calls.incrementAndGet();
            seen.add(q);
            return behaviour.answer(q);
        }
    }

    static final class RecordingListener implements DnsQueryProcessor.Listener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public void onQuery(String d, int t) { events.add("query:" + d); }
        @Override public void onBlocked(String d) { events.add("blocked:" + d); }
        @Override public void onCacheHit(String d) { events.add("cache:" + d); }
        @Override public void onResolved(String d, String u) { events.add("resolved:" + d + "@" + u); }
        @Override public void onUpstreamError(String d, String u, String r) { events.add("error:" + u); }
        @Override public void onFailed(String d, String r) { events.add("failed:" + d); }
    }

    private final AtomicLong now = new AtomicLong(1_000_000);
    private BlockManager blocks;
    private DNSCache cache;
    private RecordingListener listener;
    private DnsQueryProcessor processor;

    @Before
    public void setUp() {
        blocks = new BlockManager();
        cache = new DNSCache(100, now::get);
        listener = new RecordingListener();
        processor = new DnsQueryProcessor(blocks, cache, listener, now::get, 1, 8000);
    }

    private static FakeUpstream good(String label) {
        return new FakeUpstream(label, false, q -> answer(q, 300));
    }

    @Test
    public void blockedDomainGetsNxdomainWithOriginalQuestion() {
        byte[] q = query(0x55, "ads.doubleclick.net", 28);
        FakeUpstream up = good("u");
        byte[] r = processor.process(q, Collections.singletonList(up));
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(r));
        assertTrue(DnsMessage.isValidResponseFor(q, r));
        assertEquals(0, up.calls.get());
        assertTrue(listener.events.contains("blocked:ads.doubleclick.net"));
    }

    @Test
    public void secondIdenticalQueryIsServedFromCacheWithNewId() {
        FakeUpstream up = good("u");
        processor.process(query(1, "example.com", 1), Collections.singletonList(up));
        now.addAndGet(5_000);
        byte[] q2 = query(2, "EXAMPLE.com", 1);
        byte[] r2 = processor.process(q2, Collections.singletonList(up));
        assertEquals(1, up.calls.get());
        assertEquals(2, DnsMessage.id(r2));
        assertEquals("TTL aged by 5s", 295, DnsMessage.cacheTtlSeconds(r2));
    }

    @Test
    public void invalidResponseFallsThroughToNextUpstream() {
        FakeUpstream wrongId = new FakeUpstream("wrong-id", false, q -> DnsMessage.withId(answer(q, 60), 0xFFFF));
        FakeUpstream good = good("good");
        byte[] q = query(7, "example.com", 1);
        byte[] r = processor.process(q, Arrays.asList(wrongId, good));
        assertTrue(DnsMessage.isValidResponseFor(q, r));
        assertEquals(2, wrongId.calls.get()); // 1 retry
        assertTrue(listener.events.contains("resolved:example.com@good"));
    }

    @Test
    public void servfailFromOneUpstreamTriesTheNext() {
        FakeUpstream failing = new FakeUpstream("sf", false,
                q -> Fixtures.response(q, 2, new long[0], -1, -1, false));
        FakeUpstream good = good("good");
        byte[] r = processor.process(query(7, "example.com", 1), Arrays.asList(failing, good));
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(r));
        assertEquals("no retry on an rcode answer", 1, failing.calls.get());
    }

    /** Regression: when every upstream failed the old code silently dropped the query. */
    @Test
    public void allUpstreamsFailingYieldsServfailNotSilence() {
        FakeUpstream down = new FakeUpstream("down", true, q -> { throw new IOException("timeout"); });
        byte[] q = query(9, "example.com", 1);
        byte[] r = processor.process(q, Arrays.asList(down, down));
        assertNotNull(r);
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(r));
        assertTrue(DnsMessage.isValidResponseFor(q, r));
        assertEquals("SERVFAIL must not be cached", 0, cache.size());
        assertTrue(listener.events.contains("failed:example.com"));
    }

    @Test
    public void noUpstreamsYieldsServfail() {
        byte[] r = processor.process(query(9, "example.com", 1), Collections.emptyList());
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(r));
    }

    @Test
    public void deadlineStopsTryingFurtherUpstreams() {
        FakeUpstream slow = new FakeUpstream("slow", false, q -> {
            now.addAndGet(9_000);
            throw new IOException("timeout");
        });
        FakeUpstream never = good("never");
        byte[] r = processor.process(query(3, "example.com", 1), Arrays.asList(slow, never));
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(r));
        assertEquals(0, never.calls.get());
    }

    @Test
    public void paddingOnlyForEncryptedAndOptStrippedForNonEdnsClient() {
        FakeUpstream plain = new FakeUpstream("plain", false,
                q -> Fixtures.response(q, 0, new long[]{60}, -1, -1, DnsMessage.arCount(q) > 0));
        FakeUpstream doh = new FakeUpstream("doh", true,
                q -> Fixtures.response(q, 0, new long[]{60}, -1, -1, DnsMessage.arCount(q) > 0));
        byte[] q = query(4, "plain.example", 1);
        processor.process(q, Collections.singletonList(plain));
        assertArrayEquals("plaintext upstream gets the query unmodified", q, plain.seen.get(0));

        byte[] q2 = query(5, "doh.example", 1);
        byte[] r2 = processor.process(q2, Collections.singletonList(doh));
        assertEquals("encrypted upstream gets an EDNS padding OPT", 1, DnsMessage.arCount(doh.seen.get(0)));
        assertEquals("client without EDNS gets no OPT back", 0, DnsMessage.arCount(r2));
        assertTrue(DnsMessage.isValidResponseFor(q2, r2));
    }

    @Test
    public void malformedQueries() {
        assertNull("shorter than a header", processor.process(new byte[5], Collections.emptyList()));
        byte[] response = answer(query(1, "a.com", 1), 1);
        assertNull("never answer a response", processor.process(response, Collections.emptyList()));
        byte[] noQuestion = query(1, "a.com", 1);
        noQuestion[5] = 0; // QDCOUNT = 0
        byte[] r = processor.process(noQuestion, Collections.emptyList());
        assertEquals(DnsMessage.RCODE_FORMERR, DnsMessage.rcode(r));
    }

    @Test
    public void nxdomainFromUpstreamIsCachedPerSoa() {
        FakeUpstream up = new FakeUpstream("u", false, q -> nxdomain(q, 600, 30));
        processor.process(query(1, "missing.example", 1), Collections.singletonList(up));
        processor.process(query(2, "missing.example", 1), Collections.singletonList(up));
        assertEquals(1, up.calls.get());
        now.addAndGet(31_000);
        processor.process(query(3, "missing.example", 1), Collections.singletonList(up));
        assertEquals("expired after SOA minimum", 2, up.calls.get());
    }
}
