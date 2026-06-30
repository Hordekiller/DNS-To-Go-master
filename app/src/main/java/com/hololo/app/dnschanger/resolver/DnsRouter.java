package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;
import com.hololo.app.dnschanger.model.DnsType;

import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

public class DnsRouter {
    private final Map<String, DnsResolver> resolverMap = new HashMap<>();
    private final OkHttpClient client;
    private final VpnService vpnService;
    private final ResolverConfig config;

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
        this.client = client;
        this.vpnService = vpnService;
        this.config = config;
    }

    public byte[] resolve(DnsServer server, byte[] rawQuery) throws Exception {
        DnsResolver resolver = getResolver(server);
        return resolver.query(rawQuery);
    }

    private synchronized DnsResolver getResolver(DnsServer server) throws Exception {
        String key = server.getId();
        if (resolverMap.containsKey(key)) {
            return resolverMap.get(key);
        }

        DnsResolver resolver;
        DnsType type = server.getType();
        if (type == DnsType.DOH) {
            resolver = new DohResolver(server, client);
        } else if (type == DnsType.DOT) {
            resolver = new DotResolver(server, vpnService);
        } else if (type == DnsType.PLAIN_TCP) {
            resolver = new TcpDnsResolver(server, vpnService);
        } else {
            if (vpnService != null) {
                resolver = new UdpDnsResolver(server, new ProtectedDatagramSocketProvider(vpnService), vpnService);
            } else {
                resolver = new UdpDnsResolver(server);
            }
        }

        resolverMap.put(key, resolver);
        return resolver;
    }

    public void close() {
        synchronized (this) {
            for (DnsResolver resolver : resolverMap.values()) {
                resolver.close();
            }
            resolverMap.clear();
        }
    }
}
