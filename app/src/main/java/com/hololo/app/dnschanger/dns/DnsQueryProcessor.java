package com.hololo.app.dnschanger.dns;

import com.hololo.app.dnschanger.utils.BlockManager;
import com.hololo.app.dnschanger.utils.DNSCache;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns one client DNS query into exactly one DNS response:
 * blocklist -> cache -> upstream chain (validated) -> SERVFAIL.
 *
 * <p>Independent of Android, the TUN device and threading, so it is unit-testable
 * with fake upstreams. Thread-safe as long as the cache/blocklist are.
 */
public final class DnsQueryProcessor {

    /** One upstream endpoint (DoH, DoT, UDP, TCP...). */
    public interface Upstream {
        String label();

        /** Whether the transport is encrypted (EDNS padding is only useful then). */
        boolean encrypted();

        byte[] exchange(byte[] query) throws Exception;
    }

    public interface Listener {
        void onQuery(String domain, int type);

        void onBlocked(String domain);

        void onCacheHit(String domain);

        void onResolved(String domain, String upstreamLabel);

        void onUpstreamError(String domain, String upstreamLabel, String reason);

        void onFailed(String domain, String reason);
    }

    public interface Clock {
        long nowMillis();
    }

    public static final int EDNS_PADDING_MAX = 128;

    private final BlockManager blockManager;
    private final DNSCache cache;
    private final Listener listener;
    private final Clock clock;
    private final int retriesPerUpstream;
    private final long deadlineMs;

    public DnsQueryProcessor(BlockManager blockManager, DNSCache cache, Listener listener,
                             Clock clock, int retriesPerUpstream, long deadlineMs) {
        this.blockManager = blockManager;
        this.cache = cache;
        this.listener = listener;
        this.clock = clock;
        this.retriesPerUpstream = Math.max(0, retriesPerUpstream);
        this.deadlineMs = deadlineMs;
    }

    /**
     * @param query     the client's DNS message (UDP payload)
     * @param upstreams ordered candidates; may be empty (=> SERVFAIL)
     * @return the response to send to the client, or null if the message is too
     *         malformed to answer at all (fewer than 12 bytes, or it is a response)
     */
    public byte[] process(byte[] query, List<Upstream> upstreams) {
        if (query == null || query.length < DnsMessage.HEADER_LENGTH) return null;
        if (DnsMessage.isResponse(query)) return null; // never answer answers
        DnsMessage.Question q = DnsMessage.parseQuestion(query);
        if (q == null || DnsMessage.qdCount(query) != 1) {
            return DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_FORMERR);
        }
        int id = DnsMessage.id(query);
        if (listener != null) listener.onQuery(q.name, q.type);

        if (blockManager != null && blockManager.isBlocked(q.name)) {
            if (listener != null) listener.onBlocked(q.name);
            return DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_NXDOMAIN);
        }

        if (cache != null) {
            byte[] cached = cache.get(q.name, q.type, q.qclass, id);
            if (cached != null) {
                if (listener != null) listener.onCacheHit(q.name);
                return cached;
            }
        }

        boolean clientHasEdns = DnsMessage.arCount(query) > 0;
        long deadline = clock.nowMillis() + deadlineMs;
        String lastError = upstreams == null || upstreams.isEmpty() ? "no upstream available" : "unknown";
        if (upstreams != null) {
            outer:
            for (Upstream upstream : upstreams) {
                byte[] wire = (upstream.encrypted() && !clientHasEdns)
                        ? addEdnsPadding(query, EDNS_PADDING_MAX)
                        : query;
                for (int attempt = 0; attempt <= retriesPerUpstream; attempt++) {
                    if (clock.nowMillis() >= deadline) {
                        lastError = "deadline exceeded";
                        break outer;
                    }
                    try {
                        byte[] response = upstream.exchange(wire);
                        if (!DnsMessage.isValidResponseFor(wire, response)) {
                            lastError = "invalid response";
                            if (listener != null) listener.onUpstreamError(q.name, upstream.label(), lastError);
                            continue;
                        }
                        // SERVFAIL/REFUSED from one upstream: try the next one.
                        int rcode = DnsMessage.rcode(response);
                        if (rcode == DnsMessage.RCODE_SERVFAIL || rcode == DnsMessage.RCODE_REFUSED) {
                            lastError = "rcode " + rcode;
                            if (listener != null) listener.onUpstreamError(q.name, upstream.label(), lastError);
                            continue outer;
                        }
                        byte[] forClient = clientHasEdns ? response : DnsMessage.stripOpt(response);
                        if (cache != null) cache.put(q.name, q.type, q.qclass, DnsMessage.stripOpt(response));
                        if (listener != null) listener.onResolved(q.name, upstream.label());
                        return DnsMessage.withId(forClient, id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        lastError = "interrupted";
                        break outer;
                    } catch (Exception e) {
                        lastError = e.getClass().getSimpleName()
                                + (e.getMessage() != null ? ": " + e.getMessage() : "");
                        if (listener != null) listener.onUpstreamError(q.name, upstream.label(), lastError);
                    }
                }
            }
        }
        if (listener != null) listener.onFailed(q.name, lastError);
        return DnsMessage.buildErrorResponse(query, DnsMessage.RCODE_SERVFAIL);
    }

    /** RFC 7830 / RFC 8467 block-length-agnostic random padding in a new OPT RR. */
    public static byte[] addEdnsPadding(byte[] query, int maxPaddingLen) {
        if (query.length < DnsMessage.HEADER_LENGTH || DnsMessage.arCount(query) > 0) return query;
        int padLen = ThreadLocalRandom.current().nextInt(1, Math.max(2, maxPaddingLen + 1));
        int optionLen = 4 + padLen;
        int optRecordLen = 1 + 2 + 2 + 4 + 2 + optionLen;
        byte[] out = new byte[query.length + optRecordLen];
        System.arraycopy(query, 0, out, 0, query.length);
        int p = query.length;
        out[p++] = 0;                         // root name
        DnsMessage.put16(out, p, DnsMessage.TYPE_OPT); p += 2;
        DnsMessage.put16(out, p, 1232); p += 2; // UDP payload size
        p += 4;                               // ext-rcode, version, flags = 0
        DnsMessage.put16(out, p, optionLen); p += 2;
        DnsMessage.put16(out, p, 0x000C); p += 2; // PADDING option
        DnsMessage.put16(out, p, padLen);
        DnsMessage.put16(out, 10, 1);
        return out;
    }
}
