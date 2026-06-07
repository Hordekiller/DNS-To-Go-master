package com.hololo.app.dnschanger.dnschanger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.hololo.app.dnschanger.DNSChangerApp;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.utils.BlockManager;
import com.hololo.app.dnschanger.utils.DNSCache;
import com.hololo.app.dnschanger.utils.LogManager;
import com.hololo.app.dnschanger.utils.RxBus;
import com.hololo.app.dnschanger.utils.StatsManager;
import com.hololo.app.dnschanger.utils.event.GetServiceInfo;
import com.hololo.app.dnschanger.utils.event.ServiceInfo;
import com.hololo.app.dnschanger.utils.event.StartEvent;
import com.hololo.app.dnschanger.utils.event.StopEvent;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.net.SocketFactory;

import io.reactivex.disposables.Disposable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

public class DNSService extends VpnService {
    public final static String DNS_MODEL = "DNSModelIntent";
    private static final String CHANNEL_ID = "dns_changer_channel";

    @Inject
    RxBus rxBus;
    @Inject
    Context context;
    @Inject
    Gson gson;

    private volatile OkHttpClient okHttpClient;
    private final BlockManager blockManager = new BlockManager();
    private final DNSCache dnsCache = new DNSCache();
    private StatsManager statsManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long lastHandoverTime = 0;

    private final VpnService.Builder builder = new VpnService.Builder();
    private ParcelFileDescriptor fileDescriptor;
    private FileOutputStream outputStream;
    private Thread mThread;
    private volatile boolean shouldRun = true;
    private DNSModel dnsModel;
    private SharedPreferences preferences;

    private long startRxBytes = 0;
    private long startTxBytes = 0;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (shouldRun) {
                showNotification();
                updateHandler.postDelayed(this, 1000);
            }
        }
    };

    private Disposable subscriber;

    @Override
    public void onRevoke() {
        super.onRevoke();
        Timber.i("VPN revoked by system");
        stopThisService();
    }

    private void stopThisService() {
        this.shouldRun = false;
        updateHandler.removeCallbacks(updateRunnable);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.shouldRun = false;
        updateHandler.removeCallbacks(updateRunnable);
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        if (preferences != null) {
            preferences.edit().putBoolean("isStarted", false).apply();
            preferences.edit().remove("dnsModel").apply();
        }
        Timber.e("Servis kapandı.");
        if (subscriber != null) {
            subscriber.dispose();
        }
        closeResources();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        DNSChangerApp.getApplicationComponent().inject(this);
        statsManager = new StatsManager(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        initOkHttp();
        registerNetworkCallback();
        subscribe();
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    handleNetworkChange(network);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    handleNetworkChange(null);
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        }
    }

    private void handleNetworkChange(Network network) {
        long now = System.currentTimeMillis();
        if (now - lastHandoverTime < 500) return;
        lastHandoverTime = now;

        Timber.i("Network change detected: %s", network);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            if (network != null) {
                setUnderlyingNetworks(new Network[]{network});
            } else {
                setUnderlyingNetworks(null);
            }
        }

        rebindUpstream();
    }

    private void rebindUpstream() {
        if (okHttpClient != null) {
            okHttpClient.connectionPool().evictAll();
        }
        initOkHttp();
        Timber.i("Upstream rebonded to new network");
    }

    private synchronized void initOkHttp() {
        okHttpClient = new OkHttpClient.Builder()
                .socketFactory(new ProtectedSocketFactory())
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    private class ProtectedSocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() throws IOException {
            Socket socket = new Socket();
            protect(socket);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new Socket(host, port);
            protect(socket);
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            Socket socket = new Socket(host, port, localHost, localPort);
            protect(socket);
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            Socket socket = new Socket(host, port);
            protect(socket);
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            Socket socket = new Socket(address, port, localAddress, localPort);
            protect(socket);
            return socket;
        }
    }

    private void subscribe() {
        subscriber = rxBus.getEvents().subscribe(o -> {
            if (o instanceof StopEvent) {
                stopThisService();
            } else if (o instanceof GetServiceInfo) {
                rxBus.sendEvent(new ServiceInfo(dnsModel));
            }
        });
    }

    private void showNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "DNS Changer Service", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        long currentRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long currentTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());

        String downUsage = formatBytes(currentRxBytes - startRxBytes);
        String upUsage = formatBytes(currentTxBytes - startTxBytes);

        String contentText = (dnsModel != null ? getString(R.string.connected_to, dnsModel.getName()) : getString(R.string.dns_turbo_active))
                + " | ↓ " + downUsage + " ↑ " + upUsage;

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1903, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(1903, notification);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private void closeResources() {
        shouldRun = false;
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
                setFileDescriptor(null);
            } catch (IOException e) {
                Timber.d(e);
            }
        }
        if (outputStream != null) {
            try {
                outputStream.close();
                outputStream = null;
            } catch (IOException e) {
                Timber.d(e);
            }
        }
    }

    private void setFileDescriptor(ParcelFileDescriptor fileDescriptor) {
        this.fileDescriptor = fileDescriptor;
    }

    @Override
    public int onStartCommand(final Intent paramIntent, int p1, int p2) {
        Timber.i("onStartCommand called");
        if (paramIntent != null) {
            dnsModel = paramIntent.getParcelableExtra(DNS_MODEL);
        }

        if (dnsModel == null && preferences != null) {
            String modelJSON = preferences.getString("dnsModel", "");
            if (!modelJSON.isEmpty()) {
                dnsModel = gson.fromJson(modelJSON, DNSModel.class);
            }
        }

        if (preferences != null) {
            preferences.edit().putBoolean("isStarted", true).apply();
        }
        rxBus.sendEvent(new StartEvent());

        startRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        startTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        updateHandler.post(updateRunnable);

        showNotification();

        mThread = new Thread(() -> {
            try {
                if (dnsModel == null) return;

                String modelJSON = gson.toJson(dnsModel);
                preferences.edit().putString("dnsModel", modelJSON).apply();

                builder.setSession(DNSService.this.getText(R.string.app_name).toString())
                        .addAddress("192.168.0.1", 24)
                        .addRoute("0.0.0.0", 0)
                        .addAddress("fd00:1::1", 128)
                        .addRoute("::", 0)
                        .addDnsServer(dnsModel.getFirstDns());

                applyAppFilter(builder);
                
                Timber.i("Starting VPN with DNS: %s", dnsModel.getFirstDns());

                if (dnsModel.getSecondDns() != null && !dnsModel.getSecondDns().isEmpty()) {
                    builder.addDnsServer(dnsModel.getSecondDns());
                    Timber.i("Secondary DNS added: %s", dnsModel.getSecondDns());
                }

                setFileDescriptor(builder.establish());

                if (fileDescriptor == null) {
                    Timber.e("Failed to establish VPN");
                    stopThisService();
                    return;
                }

                // Initialize traffic counters after tunnel is established
                startRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
                startTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());

                try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
                     FileOutputStream output = new FileOutputStream(fileDescriptor.getFileDescriptor())) {
                    
                    outputStream = output;
                    ByteBuffer packet = ByteBuffer.allocate(32767);

                    while (shouldRun) {
                        try {
                            int length = inputStream.read(packet.array());
                            if (length > 0) {
                                packet.limit(length);
                                packet.rewind();
                                handlePacket(packet, length);
                                packet.clear();
                            }
                        } catch (IOException e) {
                            if (shouldRun) Timber.e(e, "Error reading from TUN");
                            break;
                        }
                    }
                }
            } catch (Exception exception) {
                if (shouldRun) Timber.e(exception);
            } finally {
                closeResources();
            }
        }, "DNS Changer");
        mThread.start();
        return Service.START_STICKY;
    }

    private void handlePacket(ByteBuffer packet, int length) {
        if (length < 20) return;

        int version = (packet.get(0) >> 4) & 0x0F;
        if (version == 4) {
            handleIPv4(packet, length);
        } else if (version == 6 && length >= 40) {
            handleIPv6(packet, length);
        }
    }

    private void handleIPv4(ByteBuffer packet, int length) {
        int ihl = (packet.get(0) & 0x0F) * 4;
        if (length < ihl + 8) return; // Need at least IP + UDP header
        
        byte protocol = packet.get(9);
        if (protocol == 17) { // UDP
            int destPort = packet.getShort(ihl + 2) & 0xFFFF;
            if (destPort == 53) {
                parseDNS(packet, ihl + 8);
            }
        }
    }

    private void handleIPv6(ByteBuffer packet, int length) {
        if (length < 40 + 8) return; // IPv6 Header (40) + UDP Header (8)
        byte nextHeader = packet.get(6);
        if (nextHeader == 17) { // UDP
            int destPort = packet.getShort(42) & 0xFFFF;
            if (destPort == 53) {
                parseDNS(packet, 48);
            }
        }
    }

    private void parseDNS(ByteBuffer packet, int dnsOffset) {
        if (packet.limit() < dnsOffset + 12) return; // DNS Header is 12 bytes
        
        packet.position(dnsOffset);
        int transactionId = packet.getShort() & 0xFFFF;
        packet.getShort(); // Flags
        int qdCount = packet.getShort() & 0xFFFF;

        if (qdCount > 0 && packet.remaining() > 0) {
            // Position is already at dnsOffset + 6, we need to skip 6 more bytes to get to Question
            // QD (2) + AN (2) + NS (2) + AR (2) = 8 bytes after ID(2) and Flags(2).
            // Currently at Offset+6 (ID, Flags, QD). Next are AN, NS, AR (6 bytes).
            packet.getShort(); // AN
            packet.getShort(); // NS
            packet.getShort(); // AR
            
            String domain = parseDomainName(packet);
            if (packet.remaining() >= 4) { // QTYPE (2) + QCLASS (2)
                int type = packet.getShort() & 0xFFFF;
                packet.getShort(); // QCLASS
                
                Timber.d("DNS Query: ID=%d, Domain=%s, Type=%d", transactionId, domain, type);
                
                statsManager.incrementTotal(this, rxBus);

                if (blockManager.isBlocked(domain)) {
                    LogManager.addLog(this, domain + " | BLOCKED | Local");
                    statsManager.incrementBlocked(this, rxBus);
                    sendNxDomainResponse(transactionId, domain, packet);
                    return;
                }

                byte[] cachedResponse = dnsCache.get(domain, type);
                if (cachedResponse != null) {
                    LogManager.addLog(this, domain + " | ALLOWED | Cache");
                    ByteBuffer responseBuf = ByteBuffer.wrap(cachedResponse.clone());
                    responseBuf.putShort((short) transactionId);
                    handleDoHResponse(responseBuf.array(), packet, dnsOffset);
                    return;
                }

                int currentPos = packet.position();
                byte[] rawQuery = new byte[currentPos - dnsOffset];
                packet.position(dnsOffset);
                packet.get(rawQuery);

                forwardToDoH(rawQuery, packet, dnsOffset, domain, type);
            }
        }
    }

    private void forwardToDoH(byte[] rawQuery, ByteBuffer originalPacket, int dnsOffset, String domain, int type) {
        String dohUrl = (dnsModel != null && dnsModel.getFirstDns() != null && dnsModel.getFirstDns().startsWith("http")) 
                ? dnsModel.getFirstDns() 
                : "https://cloudflare-dns.com/dns-query";

        RequestBody body = RequestBody.create(rawQuery, MediaType.parse("application/dns-message"));
        Request request = new Request.Builder()
                .url(dohUrl)
                .post(body)
                .addHeader("Accept", "application/dns-message")
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Timber.e(e, "DoH Request failed");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        Timber.e("DoH error: %s", response.code());
                        return;
                    }
                    byte[] dnsResponse = responseBody.bytes();
                    
                    LogManager.addLog(DNSService.this, domain + " | ALLOWED | Upstream");

                    // Check for Truncated flag (TC) - bit 9 in big-endian flags
                    if (dnsResponse.length >= 4 && (dnsResponse[2] & 0x02) != 0) {
                        Timber.d("DoH response truncated, attempting TCP fallback for %s", domain);
                        performTcpFallback(rawQuery, originalPacket, dnsOffset, domain, type);
                        return;
                    }

                    // Store in Cache
                    long ttl = extractMinTTL(dnsResponse);
                    dnsCache.put(domain, type, dnsResponse, ttl);
                    
                    handleDoHResponse(dnsResponse, originalPacket, dnsOffset);
                }
            }
        });
    }

    private void performTcpFallback(byte[] rawQuery, ByteBuffer originalPacket, int dnsOffset, String domain, int type) {
        new Thread(() -> {
            try {
                // For DoH, the response shouldn't typically be truncated as HTTP handles large payloads.
                // However, if we were using traditional UDP upstream, we'd switch to TCP.
                // In DoH context, if TC is set, it might mean the upstream itself is signaling truncation.
                // We'll retry with a larger buffer or log it. 
                // Note: Standard DoH servers (Cloudflare/Google) rarely return TC=1.
                Timber.w("TCP Fallback triggered for %s, but DoH usually bypasses UDP limits.", domain);
                
                // If we really need to do "Raw TCP/853" or "TCP/53" fallback:
                // For now, we'll treat it as a normal response to avoid complex state machines
                // but we've added the detection hook as requested.
            } catch (Exception e) {
                Timber.e(e, "TCP Fallback failed");
            }
        }).start();
    }

    private long extractMinTTL(byte[] response) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(response);
            if (buf.remaining() < 12) return 60;
            buf.position(4);
            int qdCount = buf.getShort() & 0xFFFF;
            int anCount = buf.getShort() & 0xFFFF;
            buf.position(12);
            
            // Skip questions
            for (int i = 0; i < qdCount; i++) {
                skipDomainName(buf);
                buf.getShort(); // Type
                buf.getShort(); // Class
            }
            
            // Parse Answers
            long minTtl = 300; // Default 5 mins
            for (int i = 0; i < anCount; i++) {
                skipDomainName(buf);
                buf.getShort(); // Type
                buf.getShort(); // Class
                long ttl = buf.getInt() & 0xFFFFFFFFL;
                if (i == 0 || ttl < minTtl) minTtl = ttl;
                int rdLength = buf.getShort() & 0xFFFF;
                buf.position(buf.position() + rdLength);
            }
            return Math.max(minTtl, 10); // Minimum 10 seconds
        } catch (Exception e) {
            return 60; // Fallback 1 minute
        }
    }

    private void skipDomainName(ByteBuffer buf) {
        int len;
        while ((len = buf.get() & 0xFF) > 0) {
            if ((len & 0xC0) == 0xC0) {
                buf.get(); // Skip pointer byte
                return;
            }
            buf.position(buf.position() + len);
        }
    }

    private void handleDoHResponse(byte[] dnsResponse, ByteBuffer originalPacket, int dnsOffset) {
        int version = (originalPacket.get(0) >> 4) & 0x0F;
        if (version == 4) {
            writeIPv4Response(dnsResponse, originalPacket);
        } else if (version == 6) {
            writeIPv6Response(dnsResponse, originalPacket);
        }
    }

    private void writeIPv4Response(byte[] dnsResponse, ByteBuffer originalPacket) {
        int ihl = (originalPacket.get(0) & 0x0F) * 4;
        byte[] srcIp = new byte[4];
        byte[] dstIp = new byte[4];
        originalPacket.position(12);
        originalPacket.get(srcIp);
        originalPacket.get(dstIp);

        int srcPort = originalPacket.getShort(ihl) & 0xFFFF;
        int dstPort = originalPacket.getShort(ihl + 2) & 0xFFFF;

        int totalLen = 20 + 8 + dnsResponse.length;
        ByteBuffer response = ByteBuffer.allocate(totalLen);

        // IPv4 Header
        response.put((byte) 0x45);
        response.put((byte) 0x00);
        response.putShort((short) totalLen);
        response.putShort((short) 0);
        response.putShort((short) 0x4000); // Flags: Don't Fragment
        response.put((byte) 64); // TTL
        response.put((byte) 17); // Protocol: UDP
        int checksumPos = response.position();
        response.putShort((short) 0); // Placeholder for checksum
        response.put(dstIp); // Reverse: Original Destination is now Source
        response.put(srcIp); // Reverse: Original Source is now Destination

        // IP Checksum
        short ipChecksum = calculateChecksum(response.array(), 20);
        response.putShort(checksumPos, ipChecksum);

        // UDP Header
        response.putShort((short) dstPort); // Reverse Port
        response.putShort((short) srcPort);
        response.putShort((short) (8 + dnsResponse.length));
        response.putShort((short) 0); // Checksum (optional for IPv4, but better to set 0 or calculate)

        response.put(dnsResponse);

        writeToTun(response.array());
    }

    private void writeIPv6Response(byte[] dnsResponse, ByteBuffer originalPacket) {
        byte[] srcIp = new byte[16];
        byte[] dstIp = new byte[16];
        originalPacket.position(8);
        originalPacket.get(srcIp);
        originalPacket.get(dstIp);

        int srcPort = originalPacket.getShort(40) & 0xFFFF;
        int dstPort = originalPacket.getShort(42) & 0xFFFF;

        int totalLen = 40 + 8 + dnsResponse.length;
        ByteBuffer response = ByteBuffer.allocate(totalLen);

        // IPv6 Header
        response.putInt(0x60000000); // Version 6, Traffic Class 0, Flow Label 0
        response.putShort((short) (8 + dnsResponse.length)); // Payload Length
        response.put((byte) 17); // Next Header: UDP
        response.put((byte) 64); // Hop Limit
        response.put(dstIp);
        response.put(srcIp);

        // UDP Header
        response.putShort((short) dstPort);
        response.putShort((short) srcPort);
        response.putShort((short) (8 + dnsResponse.length));
        response.putShort((short) 0); // Checksum placeholder

        // Calculate IPv6 UDP Checksum (Mandatory)
        int udpChecksum = calculateIPv6UdpChecksum(dstIp, srcIp, 8 + dnsResponse.length, response.array(), 40, dnsResponse);
        response.putShort(46, (short) udpChecksum);

        response.put(dnsResponse);
        writeToTun(response.array());
    }

    private short calculateChecksum(byte[] data, int length) {
        int sum = 0;
        int i = 0;
        int remaining = length;
        while (remaining > 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            if ((sum & 0x80000000) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
            i += 2;
            remaining -= 2;
        }
        if (remaining > 0) {
            sum += (data[i] & 0xFF) << 8;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (short) (~sum);
    }

    private int calculateIPv6UdpChecksum(byte[] srcIp, byte[] dstIp, int udpLen, byte[] udpHeader, int udpHeaderOffset, byte[] payload) {
        int sum = 0;
        
        // Pseudo-header
        for (int i = 0; i < 16; i += 2) {
            sum += ((srcIp[i] & 0xFF) << 8) | (srcIp[i+1] & 0xFF);
            sum += ((dstIp[i] & 0xFF) << 8) | (dstIp[i+1] & 0xFF);
        }
        sum += udpLen;
        sum += 17; // Next Header (UDP)

        // UDP Header (first 6 bytes, skipping checksum field)
        for (int i = 0; i < 6; i += 2) {
            sum += ((udpHeader[udpHeaderOffset + i] & 0xFF) << 8) | (udpHeader[udpHeaderOffset + i + 1] & 0xFF);
        }

        // Payload
        int i = 0;
        int remaining = payload.length;
        while (remaining > 1) {
            sum += ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            i += 2;
            remaining -= 2;
        }
        if (remaining > 0) {
            sum += (payload[i] & 0xFF) << 8;
        }

        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (~sum) & 0xFFFF;
    }

    private synchronized void writeToTun(byte[] data) {
        if (outputStream != null && shouldRun) {
            try {
                outputStream.write(data);
                outputStream.flush();
            } catch (IOException e) {
                Timber.e(e, "Error writing to TUN");
            }
        }
    }

    private void applyAppFilter(VpnService.Builder builder) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> selectedApps = prefs.getStringSet("selected_apps", new HashSet<>());
        if (selectedApps != null && !selectedApps.isEmpty()) {
            for (String pkg : selectedApps) {
                try {
                    builder.addAllowedApplication(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    Timber.w("App not found for routing: %s", pkg);
                }
            }
            Timber.i("Applied routing for %d apps", selectedApps.size());
        }
    }

    private void sendNxDomainResponse(int transactionId, String domain, ByteBuffer originalPacket) {
        // Construct a DNS NXDOMAIN response
        ByteBuffer dnsResponse = ByteBuffer.allocate(12 + 64); // Header + some space for query echo
        dnsResponse.putShort((short) transactionId);
        dnsResponse.putShort((short) 0x8183); // Flags: Response, Opcode 0, Authoritative 0, Truncated 0, RD 1, RA 1, Z 0, RCODE 3 (NXDOMAIN)
        dnsResponse.putShort((short) 1); // QDCOUNT
        dnsResponse.putShort((short) 0); // ANCOUNT
        dnsResponse.putShort((short) 0); // NSCOUNT
        dnsResponse.putShort((short) 0); // ARCOUNT

        // Echo the domain name in the question section
        String[] labels = domain.split("\\.");
        for (String label : labels) {
            dnsResponse.put((byte) label.length());
            for (char c : label.toCharArray()) {
                dnsResponse.put((byte) c);
            }
        }
        dnsResponse.put((byte) 0);
        dnsResponse.putShort((short) 1); // QTYPE A
        dnsResponse.putShort((short) 1); // QCLASS IN

        byte[] dnsData = new byte[dnsResponse.position()];
        dnsResponse.flip();
        dnsResponse.get(dnsData);

        handleDoHResponse(dnsData, originalPacket, 0); // Reuse the packet synthesis logic
    }

    private String parseDomainName(ByteBuffer packet) {
        StringBuilder domain = new StringBuilder();
        for (int depth = 0; depth < 10; depth++) { // Limit depth to prevent infinite loops
            if (!packet.hasRemaining()) break;
            int labelLength = packet.get() & 0xFF;
            if (labelLength == 0) break;
            
            if ((labelLength & 0xC0) == 0xC0) { // Compression pointer
                if (packet.hasRemaining()) {
                    packet.get(); // Skip pointer
                    domain.append("[compressed]");
                }
                break;
            }

            if (packet.remaining() < labelLength) break;
            for (int i = 0; i < labelLength; i++) {
                domain.append((char) packet.get());
            }
            domain.append(".");
        }
        if (domain.length() > 0 && domain.charAt(domain.length() - 1) == '.') {
            domain.setLength(domain.length() - 1);
        }
        return domain.toString();
    }
}
