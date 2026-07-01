package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.model.DnsServer;
import com.hololo.app.dnschanger.model.DnsServerRepository;
import com.hololo.app.dnschanger.model.DnsType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import timber.log.Timber;

public class DnsServerSelector {
    private static final String TEST_DOMAIN = "google.com";
    private static final long PROBE_FAILURE_MS = 5000L;

    private final VpnService vpnService;
    private final ResolverConfig config;
    private final OkHttpClient probeClient;

    public DnsServerSelector(VpnService vpnService) {
        this(vpnService, ResolverConfig.defaults());
    }

    public DnsServerSelector(VpnService vpnService, ResolverConfig config) {
        this.vpnService = vpnService;
        this.config = config;
        OkHttpClient.Builder probeBuilder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);

        if (vpnService != null) {
            probeBuilder.socketFactory(new ProtectedSocketFactory(vpnService));
            Timber.i("DnsServerSelector: probe client wrapped with ProtectedSocketFactory");
        }
        this.probeClient = probeBuilder.build();
    }

    public Selection select(DNSModel model) {
        List<DnsServer> candidates = candidatesFor(model);
        if (candidates.isEmpty()) {
            return Selection.unavailable("No DNS endpoint candidates");
        }

        DnsRouter router = vpnService != null ? new DnsRouter(probeClient, vpnService, config) : new DnsRouter(probeClient);
        byte[] probeQuery = DnsQueryFactory.buildAQuery(TEST_DOMAIN);
        Probe bestProbe = null;
        List<Probe> probes = new ArrayList<>();
        try {
            for (DnsServer server : candidates) {
                Probe probe = probe(router, server, probeQuery);
                probes.add(probe);
                if (probe.isSuccessful() && (bestProbe == null || probe.score() < bestProbe.score())) {
                    bestProbe = probe;
                }
            }
        } finally {
            router.close();
        }

        if (bestProbe != null) {
            return new Selection(bestProbe.server, bestProbe.latencyMs, bestProbe.getProtocolLabel(), true, summary(probes));
        }

        DnsServer fallback = candidates.get(0);
        return new Selection(fallback, PROBE_FAILURE_MS, protocolLabel(fallback.getType()), false, summary(probes));
    }

    public static List<DnsServer> candidatesFor(DNSModel model) {
        List<DnsServer> allServers = DnsServerRepository.getAllServers();
        List<DnsServer> matches = new ArrayList<>();

        String groupId = model != null ? model.getServerGroupId() : null;
        if (groupId != null && !groupId.isEmpty()) {
            for (DnsServer server : allServers) {
                if (groupId.equals(DnsServerRepository.groupIdFromServerId(server.getId()))) {
                    matches.add(server);
                }
            }
        }

        if (matches.isEmpty() && model != null) {
            String firstDns = model.getFirstDns();
            String secondDns = model.getSecondDns();
            for (DnsServer server : allServers) {
                if (matchesIp(server, firstDns) || matchesIp(server, secondDns)) {
                    matches.add(server);
                }
            }
        }

        if (matches.isEmpty() && model != null && model.getFirstDns() != null && !model.getFirstDns().isEmpty()) {
            matches.add(new DnsServer(
                    "custom_plain_udp",
                    model.getName(),
                    model.getCategory(),
                    DnsType.PLAIN_UDP,
                    null,
                    null,
                    model.getFirstDns(),
                    null,
                    53,
                    null
            ));
            matches.add(new DnsServer(
                    "custom_plain_tcp",
                    model.getName(),
                    model.getCategory(),
                    DnsType.PLAIN_TCP,
                    null,
                    null,
                    model.getFirstDns(),
                    null,
                    53,
                    null
            ));
        }

        matches.sort(Comparator.comparingInt(server -> protocolPriority(server.getType())));
        return dedupe(matches);
    }

    private Probe probe(DnsRouter router, DnsServer server, byte[] probeQuery) {
        long start = System.nanoTime();
        try {
            byte[] response = router.resolve(server, probeQuery);
            long latencyMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
            if (isUsableDnsResponse(response)) {
                return Probe.success(server, latencyMs);
            }
            return Probe.failure(server, "Invalid response");
        } catch (Exception e) {
            return Probe.failure(server, e.getClass().getSimpleName());
        }
    }

    private static boolean isUsableDnsResponse(byte[] response) {
        if (response == null || response.length < 12) {
            return false;
        }
        int flags = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
        boolean isResponse = (flags & 0x8000) != 0;
        int rcode = flags & 0x000F;
        return isResponse && rcode != 2;
    }

    private static boolean matchesIp(DnsServer server, String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        return ip.equals(server.getPrimaryIp()) || ip.equals(server.getSecondaryIp());
    }

    private static List<DnsServer> dedupe(List<DnsServer> servers) {
        List<DnsServer> result = new ArrayList<>();
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (DnsServer server : servers) {
            if (ids.add(server.getId())) {
                result.add(server);
            }
        }
        return result;
    }

    private static int protocolPriority(DnsType type) {
        if (type == DnsType.DOH) {
            return 0;
        } else if (type == DnsType.DOT) {
            return 1;
        } else if (type == DnsType.PLAIN_UDP) {
            return 2;
        }
        return 3;
    }

    private static String summary(List<Probe> probes) {
        StringBuilder builder = new StringBuilder();
        for (Probe probe : probes) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(probe.getProtocolLabel()).append(":");
            if (probe.successful) {
                builder.append(probe.latencyMs).append("ms");
            } else {
                builder.append("fail");
            }
        }
        return builder.toString();
    }

    public void close() {
        probeClient.dispatcher().executorService().shutdownNow();
        probeClient.connectionPool().evictAll();
    }

    private static String protocolLabel(DnsType type) {
        if (type == DnsType.DOH) {
            return "DoH";
        } else if (type == DnsType.DOT) {
            return "DoT";
        } else if (type == DnsType.PLAIN_TCP) {
            return "TCP";
        }
        return "UDP";
    }

    public static final class Selection {
        private final DnsServer server;
        private final long latencyMs;
        private final String protocol;
        private final boolean verified;
        private final String report;

        private Selection(DnsServer server, long latencyMs, String protocol, boolean verified, String report) {
            this.server = server;
            this.latencyMs = latencyMs;
            this.protocol = protocol;
            this.verified = verified;
            this.report = report;
        }

        private static Selection unavailable(String report) {
            return new Selection(null, PROBE_FAILURE_MS, "", false, report);
        }

        public DnsServer getServer() {
            return server;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public String getProtocol() {
            return protocol;
        }

        public boolean isVerified() {
            return verified;
        }

        public String getReport() {
            return report;
        }
    }

    private static final class Probe {
        private final DnsServer server;
        private final long latencyMs;
        private final boolean successful;
        private final String error;

        private Probe(DnsServer server, long latencyMs, boolean successful, String error) {
            this.server = server;
            this.latencyMs = latencyMs;
            this.successful = successful;
            this.error = error;
        }

        private static Probe success(DnsServer server, long latencyMs) {
            return new Probe(server, latencyMs, true, "");
        }

        private static Probe failure(DnsServer server, String error) {
            return new Probe(server, PROBE_FAILURE_MS, false, error);
        }

        private boolean isSuccessful() {
            return successful;
        }

        private long score() {
            return latencyMs + protocolPriority(server.getType()) * 25L;
        }

        private String getProtocolLabel() {
            String label = protocolLabel(server.getType());
            if (!successful && error != null && !error.isEmpty()) {
                return label + "(" + error + ")";
            }
            return label;
        }
    }
}
