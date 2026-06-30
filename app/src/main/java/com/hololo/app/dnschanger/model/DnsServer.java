package com.hololo.app.dnschanger.model;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

@Keep
public class DnsServer {
    private final String id;
    private final String name;
    private final String category;
    private final DnsType type;
    @Nullable
    private final String hostname;
    @Nullable
    private final String bootstrapIp;
    @Nullable
    private final String primaryIp;
    @Nullable
    private final String secondaryIp;
    private final int port;
    @Nullable
    private final String dohUrl;

    public DnsServer(
            String id,
            String name,
            String category,
            DnsType type,
            @Nullable String hostname,
            @Nullable String bootstrapIp,
            @Nullable String primaryIp,
            @Nullable String secondaryIp,
            int port,
            @Nullable String dohUrl
    ) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.type = type;
        this.hostname = hostname;
        this.bootstrapIp = bootstrapIp;
        this.primaryIp = primaryIp;
        this.secondaryIp = secondaryIp;
        this.port = port;
        this.dohUrl = dohUrl;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public DnsType getType() { return type; }
    @Nullable public String getHostname() { return hostname; }
    @Nullable public String getBootstrapIp() { return bootstrapIp; }
    @Nullable public String getPrimaryIp() { return primaryIp; }
    @Nullable public String getSecondaryIp() { return secondaryIp; }
    public int getPort() { return port; }
    @Nullable public String getDohUrl() { return dohUrl; }
}
