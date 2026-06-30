package com.hololo.app.dnschanger.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DnsServerRepository {
    private static final String IRAN = "IRAN";
    private static final String PUBLIC = "PUBLIC";
    private static final String PRIVACY = "PRIVACY";
    private static final String ADBLOCK = "ADBLOCK";
    private static final String INTERNATIONAL = "INTERNATIONAL";

    private static final String GENERIC_BOOTSTRAP = "1.1.1.1";

    private DnsServerRepository() {}

    public static List<DnsServer> getAllServers() {
        List<DnsServer> servers = new ArrayList<>();

        // --- Category 1: IRAN (Anti-Sanction) ---
        addProvider(servers, "shecan", "Shecan", IRAN, "178.22.122.100", "185.51.200.2", "https://free.shecan.ir/dns-query", "free.shecan.ir");
        addProvider(servers, "403_online", "403.online", IRAN, "10.202.10.202", "10.202.10.102", "https://dns.403.online/dns-query", "dns.403.online");
        addProvider(servers, "electro", "Electro", IRAN, "78.157.42.100", "78.157.42.101", null, null);
        addProvider(servers, "begzar", "Begzar", IRAN, "185.55.226.26", "185.55.225.25", null, null);
        addProvider(servers, "radar_game", "Radar Game", IRAN, "10.202.10.10", "10.202.10.11", null, null);
        addProvider(servers, "zeus", "Zeus", IRAN, "37.32.5.60", "37.32.5.61", null, null);
        addProvider(servers, "shelter", "Shelter", IRAN, "94.103.125.157", "94.103.125.158", null, null);
        addProvider(servers, "pishgaman", "Pishgaman", IRAN, "5.202.100.100", "5.202.100.101", null, null);
        addProvider(servers, "asiatech", "Asiatech", IRAN, "194.36.174.161", "178.22.122.100", null, null);
        addProvider(servers, "tci", "TCI (مخابرات)", IRAN, "217.218.155.155", "217.218.127.127", null, null);
        addProvider(servers, "shatel", "Shatel", IRAN, "85.15.1.14", "85.15.1.15", null, null);
        addProvider(servers, "hostiran", "Hostiran", IRAN, "172.29.0.100", "172.29.2.100", null, null);
        addProvider(servers, "server_ir", "Server.ir", IRAN, "194.104.158.48", "194.104.158.78", null, null);

        // --- Category 2: PUBLIC ---
        addProvider(servers, "google", "Google", PUBLIC, "8.8.8.8", "8.8.4.4", "https://dns.google/dns-query", "dns.google");
        addProvider(servers, "cloudflare", "Cloudflare", PUBLIC, "1.1.1.1", "1.0.0.1", "https://cloudflare-dns.com/dns-query", "1dot1dot1dot1.cloudflare-dns.com");
        addProvider(servers, "quad9", "Quad9", PUBLIC, "9.9.9.9", "149.112.112.112", "https://dns.quad9.net/dns-query", "dns.quad9.net");
        addProvider(servers, "opendns", "OpenDNS", PUBLIC, "208.67.222.222", "208.67.220.220", "https://doh.opendns.com/dns-query", null);
        addProvider(servers, "dns_watch", "DNS.WATCH", PUBLIC, "84.200.69.80", "84.200.70.40", null, null);
        addProvider(servers, "verisign", "Verisign", PUBLIC, "64.6.64.6", "64.6.65.6", null, null);
        addProvider(servers, "comodo_secure", "Comodo Secure", PUBLIC, "8.26.56.26", "8.20.247.20", null, null);
        addProvider(servers, "level3", "Level3", PUBLIC, "4.2.2.1", "4.2.2.2", null, null);
        addProvider(servers, "yandex", "Yandex", PUBLIC, "77.88.8.8", "77.88.8.1", "https://common.dot.dns.yandex.net/dns-query", "common.dot.dns.yandex.net");
        addProvider(servers, "neustar", "Neustar/UltraDNS", PUBLIC, "156.154.70.1", "156.154.71.1", null, null);
        addProvider(servers, "opennic", "OpenNIC", PUBLIC, "169.239.202.202", null, null, null);
        addProvider(servers, "hurricane", "Hurricane Electric", PUBLIC, "74.82.42.42", null, null, null);

        // --- Category 3: PRIVACY ---
        addProvider(servers, "cf_malware", "Cloudflare Malware", PRIVACY, "1.1.1.2", "1.0.0.2", "https://security.cloudflare-dns.com/dns-query", "security.cloudflare-dns.com");
        addProvider(servers, "cf_family", "Cloudflare Family", PRIVACY, "1.1.1.3", "1.0.0.3", "https://family.cloudflare-dns.com/dns-query", "family.cloudflare-dns.com");
        addProvider(servers, "q9_secured", "Quad9 Secured", PRIVACY, "9.9.9.11", "149.112.112.11", "https://dns11.quad9.net/dns-query", "dns11.quad9.net");
        addProvider(servers, "q9_unsecured", "Quad9 Unsecured", PRIVACY, "9.9.9.10", "149.112.112.10", "https://dns10.quad9.net/dns-query", "dns10.quad9.net");
        addProvider(servers, "nextdns", "NextDNS", PRIVACY, null, null, "https://dns.nextdns.io", "dns.nextdns.io");
        addProvider(servers, "controld", "ControlD", PRIVACY, "76.76.2.0", "76.76.10.0", "https://dns.controld.com/p0", "p0.freedns.controld.com");
        addProvider(servers, "mullvad", "Mullvad", PRIVACY, null, null, "https://dns.mullvad.net/dns-query", "dns.mullvad.net");
        addProvider(servers, "dns0_eu", "DNS0.eu", PRIVACY, "193.110.81.0", "185.253.5.0", "https://zero.dns0.eu", "zero.dns0.eu");
        addProvider(servers, "dns0_kids", "DNS0 Kids", PRIVACY, null, null, "https://kids.dns0.eu", "kids.dns0.eu");

        // --- Category 4: ADBLOCK ---
        addProvider(servers, "adguard_default", "AdGuard Default", ADBLOCK, "94.140.14.14", "94.140.15.15", "https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com");
        addProvider(servers, "adguard_family", "AdGuard Family", ADBLOCK, "94.140.14.15", "94.140.15.16", "https://family.adguard-dns.com/dns-query", "family.adguard-dns.com");
        addProvider(servers, "adguard_unfiltered", "AdGuard NonFilter", ADBLOCK, "94.140.14.140", "94.140.14.141", "https://unfiltered.adguard-dns.com/dns-query", "unfiltered.adguard-dns.com");
        addProvider(servers, "cb_security", "CleanBrowsing Security", ADBLOCK, "185.228.168.9", "185.228.169.9", "https://doh.cleanbrowsing.org/doh/security-filter", "security-filter-dns.cleanbrowsing.org");
        addProvider(servers, "cb_family", "CleanBrowsing Family", ADBLOCK, "185.228.168.168", "185.228.169.168", "https://doh.cleanbrowsing.org/doh/family-filter", "family-filter-dns.cleanbrowsing.org");
        addProvider(servers, "cb_adult", "CleanBrowsing Adult", ADBLOCK, "185.228.168.10", "185.228.169.11", "https://doh.cleanbrowsing.org/doh/adult-filter", "adult-filter-dns.cleanbrowsing.org");
        addProvider(servers, "opendns_family", "OpenDNS FamilyShield", ADBLOCK, "208.67.222.123", "208.67.220.123", null, null);
        addProvider(servers, "dns_sb", "dns.sb", ADBLOCK, "185.222.222.222", "45.11.45.11", "https://doh.dns.sb/dns-query", "dns.sb");
        addProvider(servers, "libredns", "LibreDNS", ADBLOCK, "88.198.92.222", null, "https://doh.libredns.gr/dns-query", "dot.libredns.gr");
        addProvider(servers, "rethinkdns", "RethinkDNS", ADBLOCK, null, null, "https://sky.rethinkdns.com/", null);

        // --- Category 5: INTERNATIONAL ---
        addProvider(servers, "cira", "CIRA (کانادا)", INTERNATIONAL, "149.112.121.10", "149.112.122.10", "https://protected.canadianshield.cira.ca/dns-query", "protected.canadianshield.cira.ca");
        addProvider(servers, "safedns", "SafeDNS", INTERNATIONAL, "195.46.39.39", "195.46.39.40", null, null);
        addProvider(servers, "alibaba", "Alibaba", INTERNATIONAL, "223.5.5.5", "223.6.6.6", "https://dns.alidns.com/dns-query", "dns.alidns.com");
        addProvider(servers, "tencent", "Tencent/DNSPod", INTERNATIONAL, "119.29.29.29", "182.254.116.116", "https://doh.pub/dns-query", "dot.pub");
        addProvider(servers, "360_secure", "360 Secure", INTERNATIONAL, "101.226.4.6", "218.30.118.6", null, null);
        addProvider(servers, "freenom", "Freenom World", INTERNATIONAL, "80.80.80.80", "80.80.81.81", null, null);
        addProvider(servers, "opennic_t2", "OpenNIC (Tier2)", INTERNATIONAL, "94.247.43.254", null, null, null);

        return Collections.unmodifiableList(servers);
    }

    private static void addProvider(List<DnsServer> servers, String idPrefix, String name, String category,
                                   String primaryIp, String secondaryIp, String dohUrl, String dotHostname) {
        String bootstrap = (primaryIp != null && !primaryIp.isEmpty()) ? primaryIp : GENERIC_BOOTSTRAP;

        if (dohUrl != null) {
            servers.add(new DnsServer(idPrefix + "_doh", name + " DoH", category, DnsType.DOH, 
                    extractHostname(dohUrl), bootstrap, primaryIp, secondaryIp, 443, dohUrl));
        }

        if (dotHostname != null) {
            servers.add(new DnsServer(idPrefix + "_dot", name + " DoT", category, DnsType.DOT, 
                    dotHostname, bootstrap, primaryIp, secondaryIp, 853, null));
        }

        if (primaryIp != null && !primaryIp.isEmpty()) {
            servers.add(new DnsServer(idPrefix + "_p1", name + " UDP", category, DnsType.PLAIN_UDP, 
                    null, null, primaryIp, null, 53, null));
        }

        if (secondaryIp != null && !secondaryIp.isEmpty()) {
            servers.add(new DnsServer(idPrefix + "_p2", name + " UDP (Secondary)", category, DnsType.PLAIN_UDP, 
                    null, null, secondaryIp, null, 53, null));
        }
    }

    public static String groupIdFromServerId(String serverId) {
        if (serverId == null) return "";
        return serverId.replaceFirst("_(doh|dot|p1|p2)$", "");
    }

    private static String extractHostname(String dohUrl) {
        try { return URI.create(dohUrl).getHost(); } catch (Exception e) { return null; }
    }
}
