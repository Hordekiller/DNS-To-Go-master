package com.hololo.app.dnschanger.resolver;

public final class ResolverConfig {
    private final int udpTimeoutMs;
    private final int tcpTimeoutMs;
    private final int dohTimeoutMs;
    private final int dotTimeoutMs;
    private final int maxRetries;

    public ResolverConfig(int udpTimeoutMs, int tcpTimeoutMs, int dohTimeoutMs, int dotTimeoutMs, int maxRetries) {
        this.udpTimeoutMs = udpTimeoutMs;
        this.tcpTimeoutMs = tcpTimeoutMs;
        this.dohTimeoutMs = dohTimeoutMs;
        this.dotTimeoutMs = dotTimeoutMs;
        this.maxRetries = Math.max(0, Math.min(maxRetries, 2));
    }

    public static ResolverConfig defaults() {
        return new ResolverConfig(2000, 5000, 5000, 5000, 1);
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

    public int getMaxRetries() {
        return maxRetries;
    }
}
