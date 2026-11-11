package com.hololo.app.dnschanger.resolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Dns;

/**
 * Bootstrap resolver for a DoH endpoint.
 *
 * <p>For the configured hostname it returns the system resolver's answer followed by
 * the provider's published IP literals. OkHttp tries the addresses in order, so a
 * failing, filtered or poisoned system lookup no longer makes the DoH endpoint
 * unreachable, while the previous (system-first) behaviour is preserved when it works.
 * Bootstrap entries must be IP literals; they are never resolved through DNS.
 */
public class StaticDns implements Dns {
    public interface SystemLookup {
        List<InetAddress> lookup(String hostname) throws UnknownHostException;
    }

    private final String hostname;
    private final List<String> bootstrapIps;
    private final SystemLookup system;

    public StaticDns(String hostname, String bootstrapIp) {
        this(hostname, bootstrapIp == null ? Collections.<String>emptyList()
                : Collections.singletonList(bootstrapIp), Dns.SYSTEM::lookup);
    }

    public StaticDns(String hostname, List<String> bootstrapIps, SystemLookup system) {
        this.hostname = hostname;
        this.bootstrapIps = bootstrapIps != null ? bootstrapIps : Collections.<String>emptyList();
        this.system = system;
    }

    @Override
    public List<InetAddress> lookup(String requestedHostname) throws UnknownHostException {
        if (hostname == null || !hostname.equalsIgnoreCase(requestedHostname)) {
            return system.lookup(requestedHostname);
        }
        Set<InetAddress> result = new LinkedHashSet<>();
        UnknownHostException systemError = null;
        try {
            result.addAll(system.lookup(requestedHostname));
        } catch (UnknownHostException e) {
            systemError = e;
        }
        for (String ip : bootstrapIps) {
            if (ip == null || !IpLiterals.isIpLiteral(ip)) continue;
            try {
                result.add(InetAddress.getByName(ip)); // literal: no DNS lookup
            } catch (UnknownHostException ignored) {
            }
        }
        if (result.isEmpty()) {
            throw systemError != null ? systemError : new UnknownHostException(requestedHostname);
        }
        return new ArrayList<>(result);
    }
}
