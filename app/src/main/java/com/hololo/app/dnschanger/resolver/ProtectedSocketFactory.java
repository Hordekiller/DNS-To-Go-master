package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

import timber.log.Timber;

public class ProtectedSocketFactory extends SocketFactory {
    private final VpnService vpnService;

    public ProtectedSocketFactory(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public Socket createSocket() throws IOException {
        Socket socket = new LoggingProtectedSocket();
        // protect() must run before connect() so upstream traffic does not loop into the TUN.
        boolean ok = vpnService != null && vpnService.protect(socket);
        Log.d("DNSDebug", "protect ok=" + ok
                + " vpnRef=" + (vpnService == null ? "NULL" : System.identityHashCode(vpnService))
                + " bound=" + socket.isBound()
                + " connected=" + socket.isConnected());
        if (!ok) {
            Timber.e("Failed to protect upstream TCP socket; skipping this resolver socket");
            socket.close();
            throw new IOException("VpnService.protect(Socket) returned false");
        }
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw new UnsupportedOperationException("Use createSocket() so protect() happens before connect()");
    }

    @Override
    public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws IOException {
        throw new UnsupportedOperationException("Use createSocket() so protect() happens before connect()");
    }

    @Override
    public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
        throw new UnsupportedOperationException("Use createSocket() so protect() happens before connect()");
    }

    @Override
    public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws IOException {
        throw new UnsupportedOperationException("Use createSocket() so protect() happens before connect()");
    }

    private static class LoggingProtectedSocket extends Socket {
        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            super.connect(endpoint);
            Timber.d("Protected upstream local address: %s", getLocalAddress());
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            super.connect(endpoint, timeout);
            Timber.d("Protected upstream local address: %s", getLocalAddress());
        }
    }
}
