package com.hololo.app.dnschanger.resolver;

import android.net.VpnService;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;

import timber.log.Timber;

public class ProtectedDatagramSocketProvider implements UdpDnsResolver.DatagramSocketProvider {
    private final VpnService vpnService;

    public ProtectedDatagramSocketProvider(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public DatagramSocket createSocket() throws IOException {
        DatagramSocket socket = new DatagramSocket();
        // protect() must run before UDP send/connect so upstream traffic bypasses the VPN tunnel.
        if (!vpnService.protect(socket)) {
            Timber.e("Failed to protect upstream UDP socket; skipping this resolver socket");
            socket.close();
            throw new SocketException("VpnService.protect(DatagramSocket) returned false");
        }
        return socket;
    }
}
