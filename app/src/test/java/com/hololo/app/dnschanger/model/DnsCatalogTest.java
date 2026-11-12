package com.hololo.app.dnschanger.model;

import static org.junit.Assert.*;

import com.hololo.app.dnschanger.resolver.DnsServerSelector;
import com.hololo.app.dnschanger.resolver.IpLiterals;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DnsCatalogTest {
    private static final String[] SHECAN_IPS = {"178.22.122.100", "185.51.200.2"};

    @Test
    public void shecanIsRemovedEverywhere() {
        for (DnsServer s : DnsServerRepository.getAllServers()) {
            assertFalse(s.getId(), s.getId().toLowerCase().contains("shecan"));
            assertFalse(s.getName(), s.getName().toLowerCase().contains("shecan"));
            for (String ip : SHECAN_IPS) {
                assertNotEquals(s.getId(), ip, s.getPrimaryIp());
                assertNotEquals(s.getId(), ip, s.getSecondaryIp());
                assertNotEquals(s.getId(), ip, s.getBootstrapIp());
            }
            if (s.getDohUrl() != null) assertFalse(s.getDohUrl().contains("shecan"));
        }
    }

    @Test
    public void replacementProvidersArePresent() {
        Set<String> groups = new HashSet<>();
        for (DnsServer s : DnsServerRepository.getAllServers()) groups.add(DnsServerRepository.groupIdFromServerId(s.getId()));
        assertTrue(groups.contains("dns_pro"));
        assertTrue(groups.contains("quad101"));
        assertTrue(groups.contains("dns4eu"));
        DNSModel quad101 = new DNSModel();
        quad101.setServerGroupId("quad101");
        assertEquals(2, DnsServerSelector.candidatesFor(quad101, true).size()); // DoH + DoT
    }

    @Test
    public void catalogIsWellFormed() {
        Set<String> ids = new HashSet<>();
        for (DnsServer s : DnsServerRepository.getAllServers()) {
            assertTrue("duplicate id " + s.getId(), ids.add(s.getId()));
            for (String ip : new String[]{s.getPrimaryIp(), s.getSecondaryIp(), s.getBootstrapIp()}) {
                if (ip != null) assertTrue(s.getId() + " bad IP " + ip, IpLiterals.isIpLiteral(ip));
            }
            if (s.getType() == DnsType.DOH) assertTrue(s.getId(), s.getDohUrl().startsWith("https://"));
            if (s.getType() == DnsType.DOT) assertNotNull(s.getId(), s.getHostname());
        }
    }

    /** Users whose saved choice was Shecan must still be able to start (as custom IPs). */
    @Test
    public void savedShecanModelStillStartsAsCustomIps() {
        DNSModel saved = new DNSModel();
        saved.setName("Shecan");
        saved.setServerGroupId("shecan");
        saved.setFirstDns("178.22.122.100");
        saved.setSecondDns("185.51.200.2");
        assertTrue(DnsModelSelection.isStartable(saved));
        List<DnsServer> c = DnsServerSelector.candidatesFor(saved);
        assertFalse(c.isEmpty());
        assertEquals("178.22.122.100", c.get(0).getPrimaryIp());
    }
}
