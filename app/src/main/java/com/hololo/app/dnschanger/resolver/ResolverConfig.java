package com.hololo.app.dnschanger.resolver;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ResolverConfig {
    private final int udpTimeoutMs;
    private final int tcpTimeoutMs;
    private final int dohTimeoutMs;
    private final int dotTimeoutMs;
    private final Map<String, List<String>> certificatePins;

    public ResolverConfig(int udpTimeoutMs, int tcpTimeoutMs, int dohTimeoutMs, int dotTimeoutMs) {
        this(udpTimeoutMs, tcpTimeoutMs, dohTimeoutMs, dotTimeoutMs, Collections.emptyMap());
    }

    public ResolverConfig(int udpTimeoutMs, int tcpTimeoutMs, int dohTimeoutMs, int dotTimeoutMs, Map<String, List<String>> certificatePins) {
        this.udpTimeoutMs = udpTimeoutMs;
        this.tcpTimeoutMs = tcpTimeoutMs;
        this.dohTimeoutMs = dohTimeoutMs;
        this.dotTimeoutMs = dotTimeoutMs;
        this.certificatePins = certificatePins;
    }

    public static ResolverConfig defaults() {
        return new ResolverConfig(2000, 5000, 5000, 5000);
    }

    public ResolverConfig withCertificatePin(String hostname, String pin) {
        Map<String, List<String>> pins = new ConcurrentHashMap<>(certificatePins);
        List<String> existing = pins.get(hostname);
        if (existing != null) {
            List<String> updated = new java.util.ArrayList<>(existing);
            updated.add(pin);
            pins.put(hostname, Collections.unmodifiableList(updated));
        } else {
            pins.put(hostname, Collections.singletonList(pin));
        }
        return new ResolverConfig(udpTimeoutMs, tcpTimeoutMs, dohTimeoutMs, dotTimeoutMs, pins);
    }

    public int getUdpTimeoutMs() {
        return udpTimeoutMs;
    }

    public int getTcpTimeoutMs() {
        return tcpTimeoutMs;
    }

    public int getDohTimeoutMs() {
        return dohTimeoutMs;
    }

    public int getDotTimeoutMs() {
        return dotTimeoutMs;
    }

    public Map<String, List<String>> getCertificatePins() {
        return certificatePins;
    }
}
