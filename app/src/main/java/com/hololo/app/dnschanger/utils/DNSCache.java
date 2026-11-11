package com.hololo.app.dnschanger.utils;

import com.hololo.app.dnschanger.dns.DnsMessage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded LRU cache of upstream DNS responses.
 *
 * <ul>
 *   <li>Keyed by (name, type, class) so e.g. CHAOS queries never collide with IN.</li>
 *   <li>Only NOERROR/NXDOMAIN responses with TTL information are stored
 *       (see {@link DnsMessage#cacheTtlSeconds}); SERVFAIL, REFUSED and truncated
 *       responses are never cached.</li>
 *   <li>Served responses have their TTLs reduced by the time already spent in cache.</li>
 * </ul>
 */
public class DNSCache {
    public interface Clock {
        long nowMillis();
    }

    static final int DEFAULT_MAX_ENTRIES = 1000;
    static final long MAX_TTL_SECONDS = 86400;

    private final int maxEntries;
    private final Clock clock;
    private final LinkedHashMap<String, CacheEntry> cache;

    private static final class CacheEntry {
        final byte[] response;
        final long storedAt;
        final long expiresAt;

        CacheEntry(byte[] response, long storedAt, long ttlSeconds) {
            this.response = response.clone();
            this.storedAt = storedAt;
            this.expiresAt = storedAt + ttlSeconds * 1000L;
        }
    }

    public DNSCache() {
        this(DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    public DNSCache(int maxEntries, Clock clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
        this.cache = new LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > DNSCache.this.maxEntries;
            }
        };
    }

    /**
     * Returns a cached response for the query, with the transaction ID rewritten to
     * {@code transactionId} and TTLs aged, or null on miss/expiry.
     */
    public byte[] get(String domain, int type, int qclass, int transactionId) {
        String key = key(domain, type, qclass);
        CacheEntry entry;
        long now = clock.nowMillis();
        synchronized (cache) {
            entry = cache.get(key);
            if (entry == null) return null;
            if (now >= entry.expiresAt) {
                cache.remove(key);
                return null;
            }
        }
        long elapsedSeconds = Math.max(0L, (now - entry.storedAt) / 1000L);
        byte[] aged = DnsMessage.withTtlsReducedBy(entry.response, elapsedSeconds);
        return DnsMessage.withId(aged, transactionId);
    }

    /** Stores {@code response} if it is cacheable. Returns true if stored. */
    public boolean put(String domain, int type, int qclass, byte[] response) {
        if (domain == null || response == null || response.length == 0) return false;
        long ttl = DnsMessage.cacheTtlSeconds(response);
        if (ttl <= 0) return false;
        ttl = Math.min(ttl, MAX_TTL_SECONDS);
        CacheEntry entry = new CacheEntry(response, clock.nowMillis(), ttl);
        synchronized (cache) {
            cache.put(key(domain, type, qclass), entry);
        }
        return true;
    }

    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static String key(String domain, int type, int qclass) {
        return (domain == null ? "" : domain.toLowerCase(Locale.US)) + "|" + type + "|" + qclass;
    }
}
