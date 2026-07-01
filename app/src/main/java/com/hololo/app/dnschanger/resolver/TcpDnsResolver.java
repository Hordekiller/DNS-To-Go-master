package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpDnsResolver implements DnsResolver {
    private final String ip;
    private final int port;
    private final VpnService vpnService;
    private Socket socket;

    public TcpDnsResolver(DnsServer server) {
        this(server, null);
    }

    public TcpDnsResolver(DnsServer server, VpnService vpnService) {
        this.ip = server.getPrimaryIp();
        this.port = server.getPort();
        this.vpnService = vpnService;
    }

    @Override
    public synchronized byte[] query(byte[] rawQuery) throws Exception {
        if (socket == null || socket.isClosed()) {
            connect();
        }

        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(rawQuery.length);
            out.write(rawQuery);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int responseLength = in.readUnsignedShort();
            byte[] response = new byte[responseLength];
            in.readFully(response);
            return response;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void connect() throws IOException {
        Socket sock = new Socket();
        if (vpnService != null && !vpnService.protect(sock)) {
            sock.close();
            throw new IOException("VpnService.protect(TCP socket) failed");
        }
        sock.connect(new InetSocketAddress(ip, port), 5000);
        sock.setSoTimeout(5000);
        this.socket = sock;
    }

    @Override
    public void close() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }
}
