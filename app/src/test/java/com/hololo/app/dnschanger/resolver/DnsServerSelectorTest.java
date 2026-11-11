package com.hololo.app.dnschanger.resolver;

import static org.junit.Assert.*;

import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.model.DnsServer;
import com.hololo.app.dnschanger.model.DnsType;

import org.junit.Test;

import java.util.List;

public class DnsServerSelectorTest {

    private static DNSModel group(String id) {
        DNSModel m = new DNSModel();
        m.setName(id);
        m.setServerGroupId(id);
        return m;
    }

    @Test
    public void groupCandidatesComeFromTheSameProviderEncryptedFirst() {
        List<DnsServer> c = DnsServerSelector.candidatesFor(group("google"));
        assertFalse(c.isEmpty());
        for (DnsServer s : c) assertTrue(s.getId(), s.getId().startsWith("google_"));
        assertEquals(DnsType.DOH, c.get(0).getType());
        assertEquals(DnsType.DOT, c.get(1).getType());
    }

    @Test
    public void strictModeExcludesPlaintext() {
        List<DnsServer> c = DnsServerSelector.candidatesFor(group("cloudflare"), true);
        assertFalse(c.isEmpty());
        for (DnsServer s : c) assertTrue(DnsServerSelector.isEncrypted(s.getType()));
    }

    @Test
    public void strictModeWithPlaintextOnlyProviderHasNoCandidates() {
        assertTrue(DnsServerSelector.candidatesFor(group("electro"), true).isEmpty());
        DNSModel custom = new DNSModel();
        custom.setFirstDns("9.9.9.9");
        custom.setFirstDns("203.0.113.5");
        assertTrue(DnsServerSelector.candidatesFor(custom, true).isEmpty());
        assertFalse(DnsServerSelector.candidatesFor(custom, false).isEmpty());
    }

    @Test
    public void dohOnlyProviderHasCandidatesWithoutPlainIps() {
        List<DnsServer> c = DnsServerSelector.candidatesFor(group("nextdns"));
        assertEquals(2, c.size());
        for (DnsServer s : c) {
            assertTrue(DnsServerSelector.isEncrypted(s.getType()));
            // Regression: DoT for providers without IPs used Cloudflare's 1.1.1.1 as bootstrap.
            assertNull(s.getId(), s.getBootstrapIp());
        }
    }

    /** Old behaviour (group lost) still works through IP matching. */
    @Test
    public void legacyModelWithoutGroupMatchesByIp() {
        DNSModel m = new DNSModel();
        m.setFirstDns("8.8.8.8");
        boolean hasDoh = false;
        for (DnsServer s : DnsServerSelector.candidatesFor(m)) hasDoh |= s.getType() == DnsType.DOH;
        assertTrue(hasDoh);
    }
}
