package com.hololo.app.dnschanger.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DNSCache {
    private static final int MAX_ENTRIES = 1000;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final byte[] response;
        final long expiryTime;

        CacheEntry(byte[] response, long ttlSeconds) {
            this.response = response;
            this.expiryTime = System.currentTimeMillis() + (ttlSeconds * 1000);
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }

    public byte[] get(String domain, int type) {
        String key = domain + "|" + type;
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            if (entry.isExpired()) {
                cache.remove(key);
                return null;
            }
            return entry.response;
        }
        return null;
    }

    public void put(String domain, int type, byte[] response, long ttlSeconds) {
        if (cache.size() >= MAX_ENTRIES) {
            // Simple eviction: clear everything if full to keep it lightweight
            cache.clear();
        }
        String key = domain + "|" + type;
        cache.put(key, new CacheEntry(response, ttlSeconds));
    }
}
