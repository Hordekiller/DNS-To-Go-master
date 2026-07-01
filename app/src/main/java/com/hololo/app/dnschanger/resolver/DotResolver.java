package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class DotResolver implements DnsResolver {
    private final String hostname;
    private final String ip;
    private final int port;
    private final VpnService vpnService;
    private SSLSocket socket;

    public DotResolver(DnsServer server) {
        this(server, null);
    }

    public DotResolver(DnsServer server, VpnService vpnService) {
        this.ip = server.getBootstrapIp() != null ? server.getBootstrapIp() : server.getPrimaryIp();
        this.port = server.getPort();
        this.hostname = server.getHostname();
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
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sock = (SSLSocket) factory.createSocket();

        if (vpnService != null && !vpnService.protect(sock)) {
            sock.close();
            throw new IOException("VpnService.protect(DoT socket) failed");
        }

        sock.connect(new InetSocketAddress(ip, port), 5000);
        sock.setSoTimeout(5000);
        if (hostname != null && !hostname.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            javax.net.ssl.SSLParameters params = sock.getSSLParameters();
            params.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName(hostname)));
            sock.setSSLParameters(params);
        }
        sock.startHandshake();
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
