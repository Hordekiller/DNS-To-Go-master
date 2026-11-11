package com.hololo.app.dnschanger.model;

import static org.junit.Assert.*;

import com.google.gson.Gson;

import org.junit.Test;

public class DnsModelSelectionTest {

    private static DNSModel picked() {
        DNSModel m = new DNSModel();
        m.setName("Cloudflare");
        m.setServerGroupId("cloudflare");
        m.setFirstDns("1.1.1.1");
        m.setSecondDns("1.0.0.1");
        m.setCategory("PUBLIC");
        return m;
    }

    /** Regression: the UI rebuilt a bare model and dropped the provider group. */
    @Test
    public void pickedProviderKeepsItsGroup() {
        DNSModel start = DnsModelSelection.forStart(picked(), " 1.1.1.1 ", "1.0.0.1", "Custom");
        assertEquals("cloudflare", start.getServerGroupId());
        assertEquals("Cloudflare", start.getName());
        assertNotSame(picked(), start);
        assertTrue(DnsModelSelection.isStartable(start));
    }

    @Test
    public void editedFieldsBecomeACustomModel() {
        DNSModel start = DnsModelSelection.forStart(picked(), "9.9.9.9", "", "Custom");
        assertEquals("", start.getServerGroupId());
        assertEquals("Custom", start.getName());
        assertEquals("9.9.9.9", start.getFirstDns());
        assertTrue(DnsModelSelection.isStartable(start));
    }

    @Test
    public void dohOnlyProviderIsStartableWithoutIps() {
        DNSModel nextdns = new DNSModel();
        nextdns.setName("NextDNS");
        nextdns.setServerGroupId("nextdns");
        DNSModel start = DnsModelSelection.forStart(nextdns, "", "", "Custom");
        assertTrue(DnsModelSelection.isStartable(start));
    }

    @Test
    public void invalidInputIsRejectedWithoutDnsLookups() {
        assertFalse(DnsModelSelection.isStartable(DnsModelSelection.forStart(null, "dns.google", "", "C")));
        assertFalse(DnsModelSelection.isStartable(DnsModelSelection.forStart(null, "", "", "C")));
        assertFalse(DnsModelSelection.isStartable(DnsModelSelection.forStart(null, "1.1.1.1", "garbage", "C")));
        DNSModel unknownGroup = new DNSModel();
        unknownGroup.setServerGroupId("does_not_exist");
        assertFalse(DnsModelSelection.isStartable(unknownGroup));
        assertFalse(DnsModelSelection.isStartable(null));
    }

    /** Settings saved by v2.6.x (no serverGroupId field) must still load. */
    @Test
    public void legacySavedJsonStillLoads() {
        String legacy = "{\"name\":\"Custom DNS Server\",\"firstDNS\":\"8.8.8.8\",\"secondDNS\":\"8.8.4.4\"}";
        DNSModel m = new Gson().fromJson(legacy, DNSModel.class);
        assertEquals("8.8.8.8", m.getFirstDns());
        assertEquals("", m.getServerGroupId());
        assertTrue(DnsModelSelection.isStartable(m));
        DNSModel round = new Gson().fromJson(new Gson().toJson(picked()), DNSModel.class);
        assertEquals("cloudflare", round.getServerGroupId());
    }
}
