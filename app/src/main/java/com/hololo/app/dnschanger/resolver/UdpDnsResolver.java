package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpDnsResolver implements DnsResolver {
    public interface DatagramSocketProvider {
        DatagramSocket createSocket() throws IOException;
    }

    private final InetAddress address;
    private final int port;
    private final DnsServer server;
    private final DatagramSocketProvider socketProvider;
    private final VpnService vpnService;
    private final int timeoutMs;

    public UdpDnsResolver(DnsServer server) throws Exception {
        this(server, DatagramSocket::new, null, 2000);
    }

    public UdpDnsResolver(DnsServer server, DatagramSocketProvider socketProvider) throws Exception {
        this(server, socketProvider, null, 2000);
    }

    public UdpDnsResolver(DnsServer server, DatagramSocketProvider socketProvider, VpnService vpnService) throws Exception {
        this(server, socketProvider, vpnService, 2000);
    }

    public UdpDnsResolver(DnsServer server, DatagramSocketProvider socketProvider, VpnService vpnService, int timeoutMs) throws Exception {
        this.server = server;
        this.address = InetAddress.getByName(server.getPrimaryIp());
        this.port = server.getPort();
        this.socketProvider = socketProvider;
        this.vpnService = vpnService;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public byte[] query(byte[] rawQuery) throws Exception {
        try (DatagramSocket socket = socketProvider.createSocket()) {
            socket.setSoTimeout(timeoutMs);
            // connect() makes the kernel discard datagrams from any other source,
            // which defeats trivial off-path spoofing of the reply.
            socket.connect(address, port);
            DatagramPacket queryPacket = new DatagramPacket(rawQuery, rawQuery.length, address, port);
            socket.send(queryPacket);

            int queryId = rawQuery.length >= 2 ? ((rawQuery[0] & 0xFF) << 8) | (rawQuery[1] & 0xFF) : -1;
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buffer = new byte[4096];
            while (true) {
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(responsePacket); // throws SocketTimeoutException
                int responseLength = responsePacket.getLength();
                if (responseLength < 12) continue;
                int responseId = ((buffer[0] & 0xFF) << 8) | (buffer[1] & 0xFF);
                if (responseId != queryId) {
                    // Stale or forged datagram; keep waiting for ours until the deadline.
                    int left = (int) (deadline - System.currentTimeMillis());
                    if (left <= 0) throw new java.net.SocketTimeoutException("No matching UDP DNS reply");
                    socket.setSoTimeout(left);
                    continue;
                }
                byte[] response = new byte[responseLength];
                System.arraycopy(buffer, 0, response, 0, responseLength);

                // Check TC (Truncated) bit - RFC 1035 / RFC 7766: retry over TCP.
                if ((response[2] & 0x02) != 0) {
                    TcpDnsResolver tcpFallback = new TcpDnsResolver(server, vpnService, Math.max(timeoutMs, 2000));
                    try {
                        return tcpFallback.query(rawQuery);
                    } finally {
                        tcpFallback.close();
                    }
                }
                return response;
            }
        }
    }

    @Override
    public void close() {
    }
}
