package com.hololo.app.dnschanger.model;

import androidx.annotation.Nullable;

import com.hololo.app.dnschanger.resolver.IpLiterals;

/**
 * Decides which {@link DNSModel} the UI hands to the service.
 *
 * <p>Previously the UI always built a fresh model from the two text fields, so the
 * provider picked in the list lost its {@code serverGroupId} (and with it its
 * DoH/DoT endpoints), and providers without plain IPs (NextDNS, Mullvad, ...) could
 * not be started at all.
 */
public final class DnsModelSelection {
    private DnsModelSelection() {}

    /**
     * @return a copy of {@code selected} if the fields still show its addresses;
     *         otherwise a custom model built from the fields.
     */
    public static DNSModel forStart(@Nullable DNSModel selected, @Nullable String primaryText,
                                    @Nullable String secondaryText, String customName) {
        String primary = trim(primaryText);
        String secondary = trim(secondaryText);
        if (selected != null && primary.equals(trim(selected.getFirstDns()))
                && secondary.equals(trim(selected.getSecondDns()))) {
            return new DNSModel(selected);
        }
        DNSModel custom = new DNSModel();
        custom.setName(customName);
        custom.setFirstDns(primary);
        custom.setSecondDns(secondary);
        custom.setServerGroupId("");
        return custom;
    }

    /** A model can start if it names a catalog provider or carries valid IP literals. */
    public static boolean isStartable(@Nullable DNSModel model) {
        if (model == null) return false;
        String secondary = trim(model.getSecondDns());
        if (!secondary.isEmpty() && !IpLiterals.isIpLiteral(secondary)) return false;
        String group = model.getServerGroupId();
        if (group != null && !group.isEmpty() && hasCatalogGroup(group)) return true;
        return IpLiterals.isIpLiteral(trim(model.getFirstDns()));
    }

    private static boolean hasCatalogGroup(String groupId) {
        for (DnsServer server : DnsServerRepository.getAllServers()) {
            if (groupId.equals(DnsServerRepository.groupIdFromServerId(server.getId()))) return true;
        }
        return false;
    }

    private static String trim(@Nullable String s) {
        return s == null ? "" : s.trim();
    }
}
