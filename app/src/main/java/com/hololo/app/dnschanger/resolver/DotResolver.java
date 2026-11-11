package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import com.hololo.app.dnschanger.model.DnsServer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;

import timber.log.Timber;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class DotResolver implements DnsResolver {
    private final String hostname;
    private final String ip;
    private final int port;
    private final VpnService vpnService;
    private final int timeoutMs;
    private volatile SSLSocket socket;
    private volatile boolean closed;

    public DotResolver(DnsServer server) {
        this(server, null, 5000);
    }

    public DotResolver(DnsServer server, VpnService vpnService) {
        this(server, vpnService, 5000);
    }

    public DotResolver(DnsServer server, VpnService vpnService, int timeoutMs) {
        this.ip = server.getBootstrapIp() != null ? server.getBootstrapIp() : server.getPrimaryIp();
        this.port = server.getPort();
        this.hostname = server.getHostname();
        this.vpnService = vpnService;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public synchronized byte[] query(byte[] rawQuery) throws Exception {
        if (closed) throw new IOException("DoT resolver closed");
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
            // Drop the broken connection; the next query reconnects.
            closeSocket();
            throw e;
        }
    }

    private void connect() throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sock = (SSLSocket) factory.createSocket();

        if (vpnService != null) {
            sock.bind(new InetSocketAddress(0));
            if (!vpnService.protect(sock)) {
                sock.close();
                throw new IOException("VpnService.protect(DoT socket) failed");
            }
            Timber.d("DoT resolver socket protected and bound");
        }

        if (ip == null && (hostname == null || hostname.isEmpty())) {
            sock.close();
            throw new IOException("DoT server has neither IP nor hostname");
        }
        // No published IP: resolve the hostname (our app is excluded from the VPN, so
        // this uses the underlying network's resolver). Identity is verified below.
        InetSocketAddress target = ip != null ? new InetSocketAddress(ip, port)
                : new InetSocketAddress(hostname, port);
        sock.connect(target, timeoutMs);
        sock.setSoTimeout(timeoutMs);

        if (hostname != null && !hostname.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            javax.net.ssl.SSLParameters params = sock.getSSLParameters();
            params.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName(hostname)));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            sock.setSSLParameters(params);
        }

        sock.startHandshake();

        // Always verify the peer identity after the handshake. Without a hostname the
        // certificate must be valid for the IP we connected to (IP SAN), otherwise any
        // publicly trusted certificate for any domain would be accepted.
        String expectedIdentity = (hostname != null && !hostname.isEmpty()) ? hostname : ip;
        javax.net.ssl.HostnameVerifier verifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
        if (expectedIdentity == null || !verifier.verify(expectedIdentity, sock.getSession())) {
            sock.close();
            throw new javax.net.ssl.SSLPeerUnverifiedException("DoT identity verification failed for " + expectedIdentity);
        }

        if (closed) {
            sock.close();
            throw new IOException("DoT resolver closed during connect");
        }
        this.socket = sock;
    }

    private void closeSocket() {
        SSLSocket s = socket;
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
