package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;
import com.hololo.app.dnschanger.model.DnsType;

import java.net.DatagramSocket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import okhttp3.CertificatePinner;
import okhttp3.OkHttpClient;

public class DnsRouter {
    private final java.util.Map<String, DnsResolver> resolverMap = new ConcurrentHashMap<>();
    private final OkHttpClient client;
    private final VpnService vpnService;
    private final ResolverConfig config;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public DnsRouter() {
        this(null, null, ResolverConfig.defaults());
    }

    public DnsRouter(OkHttpClient client) {
        this(client, null, ResolverConfig.defaults());
    }

    public DnsRouter(VpnService vpnService, ResolverConfig config) {
        this(null, vpnService, config);
    }

    public DnsRouter(OkHttpClient client, VpnService vpnService, ResolverConfig config) {
        if (client != null) {
            this.client = client;
        } else {
            this.client = new OkHttpClient.Builder()
                    .connectTimeout(config.getDohTimeoutMs(), TimeUnit.MILLISECONDS)
                    .readTimeout(config.getDohTimeoutMs(), TimeUnit.MILLISECONDS)
                    .writeTimeout(config.getDohTimeoutMs(), TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
        }
        this.vpnService = vpnService;
        this.config = config;
    }

    public byte[] resolve(DnsServer server, byte[] rawQuery) throws Exception {
        DnsResolver resolver = getResolver(server);
        return resolver.query(rawQuery);
    }

    private DnsResolver getResolver(DnsServer server) throws Exception {
        String key = server.getId();
        lock.readLock().lock();
        try {
            DnsResolver existing = resolverMap.get(key);
            if (existing != null) {
                return existing;
            }
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            DnsResolver existing = resolverMap.get(key);
            if (existing != null) {
                return existing;
            }

            DnsResolver resolver;
            DnsType type = server.getType();
            if (type == DnsType.DOH) {
                CertificatePinner pinner = buildCertificatePinner(server);
                resolver = new DohResolver(server, client, pinner);
            } else if (type == DnsType.DOT) {
                resolver = new DotResolver(server, vpnService, config.getDotTimeoutMs());
            } else if (type == DnsType.PLAIN_TCP) {
                resolver = new TcpDnsResolver(server, vpnService, config.getTcpTimeoutMs());
            } else {
                if (vpnService != null) {
                    resolver = new UdpDnsResolver(server, new ProtectedDatagramSocketProvider(vpnService), vpnService, config.getUdpTimeoutMs());
                } else {
                    resolver = new UdpDnsResolver(server, DatagramSocket::new, null, config.getUdpTimeoutMs());
                }
            }

            resolverMap.put(key, resolver);
            return resolver;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void close() {
        lock.writeLock().lock();
        try {
            for (DnsResolver resolver : resolverMap.values()) {
                resolver.close();
            }
            resolverMap.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private CertificatePinner buildCertificatePinner(DnsServer server) {
        String hostname = server.getHostname();
        if (hostname == null || hostname.isEmpty()) return null;
        List<String> pins = config.getCertificatePins().get(hostname);
        if (pins == null || pins.isEmpty()) return null;
        CertificatePinner.Builder builder = new CertificatePinner.Builder();
        for (String pin : pins) {
            builder.add(hostname, pin);
        }
        return builder.build();
    }
}
