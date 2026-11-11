package com.hololo.app.dnschanger.utils;

import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import timber.log.Timber;

public class BlockManager {
    // ConcurrentHashMap.newKeySet() is API 24+; this form works on minSdk 21.
    private final Set<String> blockedDomains =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public BlockManager() {
        // Default blocked domains (Example: known trackers/ads)
        blockedDomains.add("doubleclick.net");
        blockedDomains.add("google-analytics.com");
    }

    public void importFromStream(InputStream inputStream) {
        try {
            Set<String> imported = BlockListParser.parse(inputStream);
            imported.remove("");
            blockedDomains.addAll(imported);
            Timber.i("Imported %d domains to blocklist", imported.size());
        } catch (Exception e) {
            Timber.e(e, "Failed to import blocklist");
        }
    }

    /**
     * True if {@code domain} or any parent domain is blocked. Walks the label suffixes
     * (O(labels) set lookups) instead of scanning the whole list for every query.
     */
    public boolean isBlocked(String domain) {
        if (domain == null || domain.isEmpty()) return false;
        String d = domain.toLowerCase(Locale.US);
        if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        int from = 0;
        while (from >= 0 && from < d.length()) {
            if (blockedDomains.contains(d.substring(from))) return true;
            int dot = d.indexOf('.', from);
            from = dot < 0 ? -1 : dot + 1;
        }
        return false;
    }

    public void addBlockedDomain(String domain) {
        if (domain != null && !domain.isEmpty()) blockedDomains.add(domain.toLowerCase(Locale.US));
    }

    public void removeBlockedDomain(String domain) {
        if (domain != null) blockedDomains.remove(domain.toLowerCase(Locale.US));
    }
}
