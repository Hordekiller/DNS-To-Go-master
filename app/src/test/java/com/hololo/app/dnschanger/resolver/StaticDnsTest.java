package com.hololo.app.dnschanger.resolver;

import static org.junit.Assert.*;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StaticDnsTest {
    @Test
    public void systemFailureFallsBackToBootstrapLiterals() throws Exception {
        StaticDns dns = new StaticDns("dns.google", Arrays.asList("8.8.8.8", "8.8.4.4", "not-an-ip"),
                host -> { throw new UnknownHostException(host); });
        List<InetAddress> r = dns.lookup("DNS.google");
        assertEquals(Arrays.asList(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")), r);
    }

    @Test
    public void systemAnswerComesFirstThenBootstrap() throws Exception {
        InetAddress sys = InetAddress.getByName("203.0.113.7");
        StaticDns dns = new StaticDns("dns.google", Collections.singletonList("8.8.8.8"),
                host -> Collections.singletonList(sys));
        assertEquals(Arrays.asList(sys, InetAddress.getByName("8.8.8.8")), dns.lookup("dns.google"));
    }

    @Test
    public void otherHostsUseSystemOnly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StaticDns dns = new StaticDns("dns.google", Collections.singletonList("8.8.8.8"),
                host -> { calls.incrementAndGet(); return Collections.singletonList(InetAddress.getByName("192.0.2.1")); });
        assertEquals(1, dns.lookup("example.com").size());
        assertEquals(1, calls.get());
    }

    @Test(expected = UnknownHostException.class)
    public void noAddressesAtAllThrows() throws Exception {
        new StaticDns("dns.google", Collections.<String>emptyList(),
                host -> { throw new UnknownHostException(host); }).lookup("dns.google");
    }

    @Test
    public void ipLiterals() {
        for (String ok : new String[]{"1.1.1.1", "255.255.255.255", "0.0.0.0", "::1", "2606:4700:4700::1111",
                "fd66:f83a:c650::3", "::ffff:1.2.3.4", "1:2:3:4:5:6:7:8"}) {
            assertTrue(ok, IpLiterals.isIpLiteral(ok));
        }
        for (String bad : new String[]{"", "1.1.1", "1.1.1.1.1", "256.1.1.1", "01.1.1.1", "dns.google",
                "https://1.1.1.1/dns-query", "1:2:3:4:5:6:7:8:9", "1::2::3", "fe80::1%wlan0", "١.١.١.١", " 1.1.1.1"}) {
            assertFalse(bad, IpLiterals.isIpLiteral(bad));
        }
        assertFalse(IpLiterals.isIpLiteral(null));
    }
}
