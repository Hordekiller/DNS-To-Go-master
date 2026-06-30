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

    public UdpDnsResolver(DnsServer server) throws Exception {
        this(server, DatagramSocket::new, null);
    }

    public UdpDnsResolver(DnsServer server, DatagramSocketProvider socketProvider) throws Exception {
        this(server, socketProvider, null);
    }

    public UdpDnsResolver(DnsServer server, DatagramSocketProvider socketProvider, VpnService vpnService) throws Exception {
        this.server = server;
        this.address = InetAddress.getByName(server.getPrimaryIp());
        this.port = server.getPort();
        this.socketProvider = socketProvider;
        this.vpnService = vpnService;
    }

    @Override
    public byte[] query(byte[] rawQuery) throws Exception {
        try (DatagramSocket socket = socketProvider.createSocket()) {
            socket.setSoTimeout(3000);
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
                return new TcpDnsResolver(server, vpnService).query(rawQuery);
            }

            return response;
        }
    }

    @Override
    public void close() {
    }
}
