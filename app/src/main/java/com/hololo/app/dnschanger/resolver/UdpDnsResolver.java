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
            DatagramPacket queryPacket = new DatagramPacket(rawQuery, rawQuery.length, address, port);
            socket.send(queryPacket);

            byte[] buffer = new byte[4096];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(responsePacket);

            int responseLength = responsePacket.getLength();
            byte[] response = new byte[responseLength];
            System.arraycopy(buffer, 0, response, 0, responseLength);

            // Check TC (Truncated) bit - RFC 1035
            if (response.length >= 3 && (response[2] & 0x02) != 0) {
                TcpDnsResolver tcpFallback = new TcpDnsResolver(server, vpnService);
                try {
                    return tcpFallback.query(rawQuery);
                } finally {
                    tcpFallback.close();
                }
            }

            return response;
        }
    }

    @Override
    public void close() {
    }
}
