package com.hololo.app.dnschanger.utils;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import timber.log.Timber;

public class BlockManager {
    private final Set<String> blockedDomains = new HashSet<>();

    public BlockManager() {
        // Default blocked domains (Example: known trackers/ads)
        blockedDomains.add("doubleclick.net");
        blockedDomains.add("google-analytics.com");
    }

    public void importFromStream(InputStream inputStream) {
        try {
            Set<String> imported = BlockListParser.parse(inputStream);
            blockedDomains.addAll(imported);
            Timber.i("Imported %d domains to blocklist", imported.size());
        } catch (Exception e) {
            Timber.e(e, "Failed to import blocklist");
        }
    }

    public boolean isBlocked(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase();
        // Check exact match or subdomains
        if (blockedDomains.contains(d)) return true;
        
        for (String blocked : blockedDomains) {
            if (d.endsWith("." + blocked)) return true;
        }
        
        return false;
    }

    public void addBlockedDomain(String domain) {
        if (domain != null) blockedDomains.add(domain.toLowerCase());
    }

    public void removeBlockedDomain(String domain) {
        if (domain != null) blockedDomains.remove(domain.toLowerCase());
    }
}
