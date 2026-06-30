package com.hololo.app.dnschanger.resolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import okhttp3.Dns;

public class StaticDns implements Dns {
    private final String hostname;
    private final String bootstrapIp;

    public StaticDns(String hostname, String bootstrapIp) {
        this.hostname = hostname;
        this.bootstrapIp = bootstrapIp;
    }

    @Override
    public List<InetAddress> lookup(String requestedHostname) throws UnknownHostException {
        if (hostname != null && hostname.equalsIgnoreCase(requestedHostname)
                && bootstrapIp != null && !bootstrapIp.isEmpty()) {
            return Collections.singletonList(InetAddress.getByName(bootstrapIp));
        }
        return Dns.SYSTEM.lookup(requestedHostname);
    }
}
