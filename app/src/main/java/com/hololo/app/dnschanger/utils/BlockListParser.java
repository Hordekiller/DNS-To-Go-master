package com.hololo.app.dnschanger.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

public class BlockListParser {

    public static Set<String> parse(InputStream inputStream) throws IOException {
        Set<String> domains = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue; // Skip comments and empty lines
                }

                // 1. hosts format: 0.0.0.0 domain.com
                if (line.matches("^(0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+.+$")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        domains.add(cleanDomain(parts[1]));
                    }
                }
                // 2. dnsmasq format: address=/domain.com/0.0.0.0
                else if (line.startsWith("address=/") || line.startsWith("server=/")) {
                    String[] parts = line.split("/");
                    if (parts.length >= 2) {
                        domains.add(cleanDomain(parts[1]));
                    }
                }
                // 3. AdGuard/Simple format: ||domain.com^
                else if (line.startsWith("||")) {
                    int end = line.indexOf('^', 2);
                    if (end != -1) {
                        domains.add(cleanDomain(line.substring(2, end)));
                    } else {
                        domains.add(cleanDomain(line.substring(2)));
                    }
                }
                // 4. Plain domain format: domain.com
                else if (line.matches("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
                    domains.add(cleanDomain(line));
                }
            }
        }
        return domains;
    }

    private static String cleanDomain(String domain) {
        if (domain == null) return "";
        domain = domain.toLowerCase().trim();
        // Remove trailing dot if exists
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain;
    }
}
