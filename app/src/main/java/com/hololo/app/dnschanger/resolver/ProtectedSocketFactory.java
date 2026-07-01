package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.SocketFactory;

import timber.log.Timber;

public class ProtectedSocketFactory extends SocketFactory {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_MS = 50;

    private final VpnService vpnService;
    private final AtomicBoolean vpnOperational;

    public ProtectedSocketFactory(VpnService vpnService) {
        this(vpnService, null);
    }

    public ProtectedSocketFactory(VpnService vpnService, AtomicBoolean vpnOperational) {
        this.vpnService = vpnService;
        this.vpnOperational = vpnOperational;
    }

    @Override
    public Socket createSocket() throws IOException {
        failFastIfVpnIsDead();

        int attempt = 0;
        IOException lastError = null;

        while (attempt <= MAX_RETRIES) {
            attempt++;
            backoffBeforeRetry(attempt);

            Socket socket = new LoggingProtectedSocket();
            String attemptLabel = "attempt=" + attempt + "/" + (MAX_RETRIES + 1);

            try {
                enforcePreConnectState(socket, attemptLabel);

                if (vpnService == null) {
                    throw new IOException("VpnService reference is null");
                }

                boolean ok = vpnService.protect(socket);
                Log.d("DNSDebug", "protect " + attemptLabel
                        + " ok=" + ok
                        + " vpnRef=" + System.identityHashCode(vpnService)
                        + " bound=" + socket.isBound()
                        + " connected=" + socket.isConnected()
                        + " closed=" + socket.isClosed()
                        + " thread=" + Thread.currentThread().getName());

                if (ok) {
                    Timber.d("Socket protected successfully %s", attemptLabel);
                    return socket;
                }

                Timber.w("protect returned false %s", attemptLabel);
                lastError = new IOException("VpnService.protect(Socket) returned false on " + attemptLabel);
            } catch (Exception e) {
                Timber.w(e, "protect threw %s", attemptLabel);
                lastError = (e instanceof IOException)
                        ? (IOException) e
                        : new IOException("VpnService.protect threw exception on " + attemptLabel, e);
            }

            closeQuietly(socket);

            if (vpnOperational != null && !vpnOperational.get()) {
                Timber.e("VPN went down during retry; aborting");
                throw lastError;
            }
        }

        Timber.e(lastError, "All %d attempts to protect upstream TCP socket failed", MAX_RETRIES + 1);
        throw lastError;
    }

    private void failFastIfVpnIsDead() throws IOException {
        if (vpnOperational != null && !vpnOperational.get()) {
            throw new IOException("VpnService is not operational (vpnOperational=false)");
        }
    }

    private static void backoffBeforeRetry(int attempt) throws IOException {
        if (attempt <= 1) return;
        long delay = BASE_RETRY_MS << (attempt - 2);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during protect retry backoff", e);
        }
    }

    private static void enforcePreConnectState(Socket socket, String attemptLabel) throws IOException {
        if (socket.isClosed()) {
            throw new IOException("Socket already closed before protect " + attemptLabel);
        }
        if (socket.isConnected()) {
            throw new IOException("Socket already connected before protect " + attemptLabel
                    + "; protect() must precede connect()");
        }
        if (socket.isBound()) {
            Timber.w("Socket is already bound before protect %s; OS may reject protect()", attemptLabel);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // -----------------------------------------------------------------------
    //  All multi-arg variants throw to force OkHttp through the no-arg path
    //  where we can protect() the unconnected socket before connect().
    // -----------------------------------------------------------------------

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
            Timber.d("Protected upstream connected: local=%s remote=%s",
                    getLocalAddress(), getRemoteSocketAddress());
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            super.connect(endpoint, timeout);
            Timber.d("Protected upstream connected: local=%s remote=%s",
                    getLocalAddress(), getRemoteSocketAddress());
        }
    }
}
