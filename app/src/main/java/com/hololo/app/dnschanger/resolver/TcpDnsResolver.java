package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import timber.log.Timber;

public class TcpDnsResolver implements DnsResolver {
    private final String ip;
    private final int port;
    private final VpnService vpnService;
    private final int timeoutMs;
    private volatile Socket socket;
    private volatile boolean closed;

    public TcpDnsResolver(DnsServer server) {
        this(server, null, 5000);
    }

    public TcpDnsResolver(DnsServer server, VpnService vpnService) {
        this(server, vpnService, 5000);
    }

    public TcpDnsResolver(DnsServer server, VpnService vpnService, int timeoutMs) {
        this.ip = server.getBootstrapIp() != null ? server.getBootstrapIp() : server.getPrimaryIp();
        this.port = server.getPort();
        this.vpnService = vpnService;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public synchronized byte[] query(byte[] rawQuery) throws Exception {
        if (closed) throw new IOException("TCP resolver closed");
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
            closeSocket();
            throw e;
        }
    }

    private void connect() throws IOException {
        Socket sock = new Socket();
        if (vpnService != null) {
            sock.bind(new InetSocketAddress(0));
            if (!vpnService.protect(sock)) {
                sock.close();
                throw new IOException("VpnService.protect(TCP socket) failed");
            }
            Timber.d("TCP resolver socket protected and bound");
        }
        sock.connect(new InetSocketAddress(ip, port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        if (closed) {
            sock.close();
            throw new IOException("TCP resolver closed during connect");
        }
        this.socket = sock;
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void close() {
        closed = true;
        closeSocket();
    }

    /** True only after {@link #close()}; a not-yet-connected resolver is reusable. */
    @Override
    public boolean isClosed() {
        return closed;
    }
}
