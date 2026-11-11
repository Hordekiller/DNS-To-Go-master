package com.hololo.app.dnschanger.dns;

import static com.hololo.app.dnschanger.dns.Fixtures.*;
import static org.junit.Assert.*;

import com.hololo.app.dnschanger.utils.BlockManager;
import com.hololo.app.dnschanger.utils.DNSCache;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Drives a real {@link TunnelSession} through an in-memory "TUN device". */
public class TunnelSessionTest {

    private static final byte[] EOF = new byte[0];

    /** In-memory TUN: tests push packets in, the session's replies are collected. */
    static final class FakeTun implements TunnelSession.PacketSource, TunnelSession.PacketSink {
        final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read(byte[] buffer) throws IOException {
            try {
                byte[] p = inbound.take();
                if (p == EOF || closed.get()) return -1;
                System.arraycopy(p, 0, buffer, 0, p.length);
                return p.length;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException();
            }
        }

        @Override
        public void close() {
            closed.set(true);
            inbound.offer(EOF);
        }

        @Override
        public void write(byte[] packet, int length) throws IOException {
            if (closed.get()) throw new IOException("closed");
            outbound.add(java.util.Arrays.copyOf(packet, length));
        }

        byte[] reply(long timeoutMs) throws InterruptedException {
            return outbound.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    static final class Terminations implements TunnelSession.TerminationListener {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean requested = new AtomicBoolean();

        @Override
        public void onTerminated(TunnelSession s, boolean req, Throwable cause) {
            requested.set(req);
            latch.countDown();
        }
    }

    private static DnsQueryProcessor processor(DNSCache cache) {
        return new DnsQueryProcessor(new BlockManager(), cache, null, System::currentTimeMillis, 0, 5000);
    }

    private static List<DnsQueryProcessor.Upstream> echoUpstream() {
        return Collections.<DnsQueryProcessor.Upstream>singletonList(new DnsQueryProcessor.Upstream() {
            @Override public String label() { return "echo"; }
            @Override public boolean encrypted() { return false; }
            @Override public byte[] exchange(byte[] q) { return answer(q, 60); }
        });
    }

    private static TunnelSession session(FakeTun tun, TunnelSession.UpstreamProvider up,
                                         TunnelSession.Config cfg, Terminations t) {
        return new TunnelSession(tun, tun, processor(new DNSCache()), up, cfg, t);
    }

    @Test
    public void answersManyConcurrentIpv4AndIpv6Queries() throws Exception {
        FakeTun tun = new FakeTun();
        TunnelSession s = session(tun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), null);
        s.start();
        int n = 400;
        for (int i = 0; i < n; i++) {
            byte[] q = query(i, "host" + i + ".example.com", 1);
            tun.inbound.add(i % 2 == 0
                    ? ipv4Udp(CLIENT_V4, VDNS_V4, 10000 + i, 53, q)
                    : ipv6Udp(CLIENT_V6, VDNS_V6, 10000 + i, 53, q, 0));
        }
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < n; i++) {
            byte[] reply = tun.reply(5000);
            assertNotNull("reply " + i + " missing", reply);
            IpPacket r = IpPacket.parse(reply, reply.length);
            assertNotNull(r);
            assertTrue(IpPacket.verifyTransportChecksum(reply, r));
            byte[] dns = r.copyPayload(reply);
            int id = DnsMessage.id(dns);
            assertEquals("reply routed to the querying port", 10000 + id, r.dstPort);
            assertEquals("host" + id + ".example.com", DnsMessage.parseQuestion(dns).name);
            ids.add(id);
        }
        assertEquals(n, ids.size());
        s.stop();
        assertTrue(s.awaitTermination(3000));
    }

    /**
     * Regression for executor rejection: the old reader leaked a pool buffer on every
     * RejectedExecutionException and dropped the query; after 64 leaks the reader
     * blocked forever. Overload must produce an immediate SERVFAIL instead.
     */
    @Test
    public void overloadAnswersServfailInsteadOfDroppingOrDeadlocking() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TunnelSession.UpstreamProvider blocking = () -> Collections.<DnsQueryProcessor.Upstream>singletonList(
                new DnsQueryProcessor.Upstream() {
                    @Override public String label() { return "slow"; }
                    @Override public boolean encrypted() { return false; }
                    @Override public byte[] exchange(byte[] q) throws Exception {
                        release.await(10, TimeUnit.SECONDS);
                        return answer(q, 60);
                    }
                });
        TunnelSession.Config cfg = TunnelSession.Config.defaults();
        cfg.workerThreads = 2;
        cfg.workQueueCapacity = 2;
        FakeTun tun = new FakeTun();
        TunnelSession s = session(tun, blocking, cfg, null);
        s.start();
        int n = 100;
        for (int i = 0; i < n; i++) {
            tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 20000 + i, 53, query(i, "q" + i + ".example", 1)));
        }
        int servfails = 0;
        for (int i = 0; i < n - 4; i++) {
            byte[] reply = tun.reply(3000);
            assertNotNull("overloaded query " + i + " got no reply", reply);
            IpPacket r = IpPacket.parse(reply, reply.length);
            if (DnsMessage.rcode(r.copyPayload(reply)) == DnsMessage.RCODE_SERVFAIL) servfails++;
        }
        assertEquals(n - 4, servfails);
        release.countDown();
        for (int i = 0; i < 4; i++) assertNotNull("in-flight queries complete", tun.reply(3000));
        assertEquals(n - 4, s.stats().overloadServfail.get());

        // Reader is still alive after the burst.
        tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 30000, 53, query(999, "after.example", 1)));
        assertNotNull(tun.reply(3000));
        s.stop();
    }

    @Test
    public void tcpDnsGetsResetAndOtherTrafficIsIgnored() throws Exception {
        FakeTun tun = new FakeTun();
        TunnelSession s = session(tun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), null);
        s.start();
        tun.inbound.add(ipv4Tcp(CLIENT_V4, VDNS_V4, 40000, 53, 10, 0, IpPacket.TCP_SYN));
        byte[] rst = tun.reply(3000);
        assertNotNull("TCP/53 must not be silently black-holed", rst);
        assertEquals(IpPacket.TCP_RST | IpPacket.TCP_ACK, IpPacket.parse(rst, rst.length).tcpFlags);

        tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 1, 123, new byte[48])); // NTP
        tun.inbound.add(new byte[]{0x45, 0, 0}); // garbage
        tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 2, 53, query(1, "ok.example", 1)));
        byte[] reply = tun.reply(3000);
        assertEquals(2, IpPacket.parse(reply, reply.length).dstPort);
        assertNull(tun.reply(200));
        assertEquals(2, s.stats().ignored.get());
        s.stop();
    }

    @Test
    public void stopIsIdempotentAndReportsRequestedTermination() throws Exception {
        FakeTun tun = new FakeTun();
        Terminations t = new Terminations();
        TunnelSession s = session(tun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), t);
        s.start();
        s.stop();
        s.stop();
        assertTrue(t.latch.await(3, TimeUnit.SECONDS));
        assertTrue(t.requested.get());
        assertTrue(s.awaitTermination(3000));
        assertEquals(TunnelSession.State.STOPPED, s.state());
    }

    @Test
    public void descriptorLossIsReportedAsUnexpected() throws Exception {
        FakeTun tun = new FakeTun();
        Terminations t = new Terminations();
        TunnelSession s = session(tun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), t);
        s.start();
        tun.inbound.add(EOF); // e.g. VPN revoked / fd closed by the system
        assertTrue(t.latch.await(3, TimeUnit.SECONDS));
        assertFalse(t.requested.get());
    }

    /**
     * Regression for the "works once" bug: the old tunnel thread's finally block reset
     * the shared isRunning flag and closed the shared descriptor, killing the session
     * started after it. Sessions must be fully isolated.
     */
    @Test
    public void stoppingAnOldSessionNeverAffectsTheNewOne() throws Exception {
        FakeTun oldTun = new FakeTun();
        TunnelSession old = session(oldTun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), null);
        old.start();
        FakeTun newTun = new FakeTun();
        TunnelSession fresh = session(newTun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), null);
        fresh.start();
        old.stop();
        assertTrue(old.awaitTermination(3000));

        newTun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 5, 53, query(5, "still.works", 1)));
        assertNotNull(newTun.reply(3000));
        assertTrue(fresh.isRunning());
        fresh.stop();
    }

    @Test
    public void repeatedConnectDisconnectCyclesLeakNoThreads() throws Exception {
        int before = liveTunThreads();
        for (int i = 0; i < 50; i++) {
            FakeTun tun = new FakeTun();
            TunnelSession s = session(tun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), null);
            s.start();
            tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 7, 53, query(i, "cycle.example", 1)));
            assertNotNull("cycle " + i, tun.reply(3000));
            s.stop();
            assertTrue("cycle " + i + " did not terminate", s.awaitTermination(3000));
        }
        // Allow idle resolver threads to exit.
        long deadline = System.currentTimeMillis() + 3000;
        while (liveTunThreads() > before && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue("leaked tunnel threads: " + liveTunThreads() + " > " + before, liveTunThreads() <= before);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotStartTwice() {
        FakeTun tun = new FakeTun();
        TunnelSession s = session(tun, TunnelSessionTest::echoUpstream, TunnelSession.Config.defaults(), null);
        s.start();
        try {
            s.start();
        } finally {
            s.stop();
        }
    }

    @Test
    public void upstreamProviderIsConsultedPerQuery() throws Exception {
        AtomicReference<List<DnsQueryProcessor.Upstream>> current = new AtomicReference<>(new ArrayList<>());
        FakeTun tun = new FakeTun();
        TunnelSession s = session(tun, current::get, TunnelSession.Config.defaults(), null);
        s.start();
        tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 9, 53, query(1, "a.example", 1)));
        byte[] r1 = tun.reply(3000);
        assertEquals("no upstream yet => SERVFAIL", DnsMessage.RCODE_SERVFAIL,
                DnsMessage.rcode(IpPacket.parse(r1, r1.length).copyPayload(r1)));
        current.set(echoUpstream()); // e.g. after network handover / selection
        tun.inbound.add(ipv4Udp(CLIENT_V4, VDNS_V4, 9, 53, query(2, "b.example", 1)));
        byte[] r2 = tun.reply(3000);
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(IpPacket.parse(r2, r2.length).copyPayload(r2)));
        s.stop();
    }

    private static int liveTunThreads() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("tun-")) count++;
        }
        return count;
    }
}
