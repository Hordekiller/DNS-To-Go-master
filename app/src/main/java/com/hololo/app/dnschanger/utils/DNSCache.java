package com.hololo.app.dnschanger.utils;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DNSCache {
    private static final int MAX_ENTRIES = 1000;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final byte[] response;
        final long expiryTime;

        CacheEntry(byte[] response, long ttlSeconds) {
            this.response = response.clone();
            long safeTtlSeconds = Math.max(1, Math.min(ttlSeconds, 86400));
            this.expiryTime = System.currentTimeMillis() + (safeTtlSeconds * 1000);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    public byte[] get(String domain, int type) {
        String key = key(domain, type);
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            if (entry.isExpired()) {
                cache.remove(key, entry);
                return null;
            }
            return entry.response.clone();
        }
        return null;
    }

    public synchronized void put(String domain, int type, byte[] response, long ttlSeconds) {
        if (domain == null || response == null || response.length == 0) {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            // Simple eviction: clear everything if full to keep it lightweight
            cache.clear();
        }
        String key = key(domain, type);
        cache.put(key, new CacheEntry(response, ttlSeconds));
    }

    private String key(String domain, int type) {
        return (domain == null ? "" : domain.toLowerCase(Locale.US)) + "|" + type;
    }
}
