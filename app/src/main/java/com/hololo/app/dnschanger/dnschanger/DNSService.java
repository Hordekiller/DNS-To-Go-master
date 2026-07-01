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
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

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
import com.hololo.app.dnschanger.resolver.ProtectedSocketFactory;
import com.hololo.app.dnschanger.resolver.DnsRouter;
import com.hololo.app.dnschanger.resolver.DnsServerSelector;
import com.hololo.app.dnschanger.resolver.ResolverConfig;
import com.hololo.app.dnschanger.model.DnsServer;
import com.hololo.app.dnschanger.model.DnsServerRepository;
import com.hololo.app.dnschanger.model.DnsType;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import io.reactivex.disposables.Disposable;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

public class DNSService extends VpnService {
    public final static String DNS_MODEL = "DNSModelIntent";
    public static final String ACTION_DISCONNECT = "com.hololo.app.dnschanger.action.DISCONNECT";
    public static final String ACTION_RECONNECT = "com.hololo.app.dnschanger.action.RECONNECT";
    private static final String CHANNEL_ID = "dns_changer_channel";
    private static final int NOTIF_ID = 1903;
    private static final long STOP_JOIN_TIMEOUT_MS = 1500L;
    private static final int DNS_HEADER_LENGTH = 12;
    private static final int UDP_HEADER_LENGTH = 8;
    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int RESOLVE_THREADS = 4;
    private static final int UPSTREAM_MAX_RETRIES = 1;
    private static final String FALLBACK_DOH_URL = "https://1.1.1.1/dns-query";

    private static final String PREF_RESOLVER_MAX_RETRIES = "resolver_max_retries";

    @Inject
    RxBus rxBus;
    @Inject
    Context context;
    @Inject
    Gson gson;

    private volatile OkHttpClient okHttpClient;
    private volatile ExecutorService resolveExecutor;
    private volatile DnsRouter dnsRouter;
    private volatile DnsServerSelector dnsServerSelector;
    private volatile DnsServer selectedServer;
    private volatile String selectionReport;
    private final BlockManager blockManager = new BlockManager();
    private final DNSCache dnsCache = new DNSCache();
    private StatsManager statsManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long lastHandoverTime = 0;

    private ParcelFileDescriptor fileDescriptor;
    private FileOutputStream outputStream;
    private Thread mThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean vpnOperational = new AtomicBoolean(false);
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);
    private volatile DNSModel dnsModel;
    private SharedPreferences preferences;

    private final java.util.concurrent.atomic.AtomicLong downloadedBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong uploadedBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private long lastRxBytes = TrafficStats.UNSUPPORTED;
    private long lastTxBytes = TrafficStats.UNSUPPORTED;
    private static final long NOTIFICATION_INTERVAL_MS = 5000;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning.get()) {
                showNotification();
                updateHandler.postDelayed(this, NOTIFICATION_INTERVAL_MS);
            }
        }
    };

    private PendingIntent cachedDisconnectIntent;
    private PendingIntent cachedReconnectIntent;
    private PendingIntent cachedContentIntent;
    private Disposable subscriber;

    @Override
    public void onRevoke() {
        super.onRevoke();
        vpnOperational.set(false);
        Timber.i("VPN revoked by system");
        stopThisService();
    }

    private void stopThisService() {
        stopTunnel(true);
    }

    @Override
    public void onDestroy() {
        stopTunnel(false);
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        Timber.e("Servis kapandı.");
        if (subscriber != null) {
            subscriber.dispose();
            subscriber = null;
        }
        super.onDestroy();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        DNSChangerApp.getApplicationComponent().inject(this);
        statsManager = new StatsManager(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "DNS Changer Service", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        registerNetworkCallback();
        subscribe();
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && connectivityManager != null) {
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
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

    private void bindCurrentUnderlyingNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && connectivityManager != null) {
            Network activeNetwork = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activeNetwork = connectivityManager.getActiveNetwork();
            }
            if (activeNetwork != null) {
                setUnderlyingNetworks(new Network[]{activeNetwork});
            } else {
                setUnderlyingNetworks(null);
            }
        }
    }

    private synchronized void rebindUpstream() {
        OkHttpClient client = okHttpClient;
        okHttpClient = null;
        if (client != null) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        if (dnsRouter != null) {
            dnsRouter.close();
            dnsRouter = null;
        }
        selectedServer = null;
        if (fileDescriptor != null) {
            initOkHttp();
            initDnsRouter();
            selectBestServer();
        }
        Timber.i("Upstream rebonded to new network");
    }

    private synchronized void initOkHttp() {
        okHttpClient = new OkHttpClient.Builder()
                .socketFactory(new ProtectedSocketFactory(this, vpnOperational))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private synchronized void initDnsRouter() {
        if (okHttpClient != null) {
            dnsRouter = new DnsRouter(okHttpClient, this, ResolverConfig.defaults());
            dnsServerSelector = new DnsServerSelector(this, ResolverConfig.defaults());
        }
    }

    private synchronized void selectBestServer() {
        if (dnsServerSelector == null || dnsModel == null) {
            return;
        }
        try {
            DnsServerSelector.Selection selection = dnsServerSelector.select(dnsModel);
            selectedServer = selection.getServer();
            selectionReport = selection.getReport();
            Timber.i("DNS server selection: %s | %s | %dms | verified=%b",
                    selectedServer != null ? selectedServer.getName() : "none",
                    selection.getProtocol(), selection.getLatencyMs(), selection.isVerified());
            LogManager.addLog(this, "Server Select: " +
                    (selectedServer != null ? selectedServer.getName() : "none") +
                    " via " + selection.getProtocol() +
                    " (" + selection.getLatencyMs() + "ms)");
        } catch (Exception e) {
            Timber.e(e, "Failed to select best DNS server");
            selectedServer = null;
            selectionReport = "selection error: " + e.getMessage();
        }
    }

    private synchronized void initResolveExecutor() {
        if (resolveExecutor == null || resolveExecutor.isShutdown()) {
            resolveExecutor = Executors.newFixedThreadPool(RESOLVE_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "DNS Resolver");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private void subscribe() {
        subscriber = rxBus.getEvents().subscribe(o -> {
            if (o instanceof StopEvent) {
                if (isRunning.get()) {
                    stopThisService();
                }
            } else if (o instanceof GetServiceInfo) {
                rxBus.sendEvent(new ServiceInfo(dnsModel));
            }
        });
    }

    private void showNotification() {
        Notification notification = buildNotification();
        if (foregroundStarted.compareAndSet(false, true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } else {
            updateNotification(notification);
        }
    }

    private Notification buildNotification() {
        updateTrafficCounters();

        String downUsage = formatBytes(downloadedBytes.get());
        String upUsage = formatBytes(uploadedBytes.get());

        String status = isRunning.get()
                ? (dnsModel != null ? getString(R.string.connected_to, dnsModel.getName()) : getString(R.string.dns_turbo_active))
                : getString(R.string.disconnected);

        String contentText = status
                + " | ↓ " + downUsage + " ↑ " + upUsage;

        if (cachedContentIntent == null) {
            Intent intent = new Intent(this, MainActivity.class);
            cachedContentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        }
        if (cachedDisconnectIntent == null) {
            cachedDisconnectIntent = PendingIntent.getService(
                    this, 1,
                    new Intent(this, DNSService.class).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE
            );
        }
        if (cachedReconnectIntent == null) {
            cachedReconnectIntent = PendingIntent.getService(
                    this, 2,
                    new Intent(this, DNSService.class).setAction(ACTION_RECONNECT),
                    PendingIntent.FLAG_IMMUTABLE
            );
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                .setContentIntent(cachedContentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(isRunning.get())
                .addAction(R.drawable.ic_vpn_key_black_24dp, getString(R.string.disconnect), cachedDisconnectIntent)
                .addAction(R.drawable.ic_vpn_key_black_24dp, getString(R.string.reconnect), cachedReconnectIntent);

        return notificationBuilder.build();
    }

    private void updateNotification(Notification notification) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIF_ID, notification);
        }
    }

    private void updateTrafficCounters() {
        long currentRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long currentTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());

        if (currentRxBytes != TrafficStats.UNSUPPORTED && lastRxBytes != TrafficStats.UNSUPPORTED && currentRxBytes >= lastRxBytes) {
            downloadedBytes.addAndGet(currentRxBytes - lastRxBytes);
        }
        if (currentTxBytes != TrafficStats.UNSUPPORTED && lastTxBytes != TrafficStats.UNSUPPORTED && currentTxBytes >= lastTxBytes) {
            uploadedBytes.addAndGet(currentTxBytes - lastTxBytes);
        }

        lastRxBytes = currentRxBytes;
        lastTxBytes = currentTxBytes;
    }

    private void resetTrafficCounters() {
        downloadedBytes.set(0);
        uploadedBytes.set(0);
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private void closeResources() {
        closeTunInterface();
        closeOutputStream();
    }

    private void closeTunInterface() {
        ParcelFileDescriptor descriptor = fileDescriptor;
        fileDescriptor = null;
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
                Timber.d(e);
            }
        }
    }

    private void closeOutputStream() {
        FileOutputStream stream = outputStream;
        outputStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                Timber.d(e);
            }
        }
    }

    private void closeAllResolvers() {
        OkHttpClient client = okHttpClient;
        okHttpClient = null;
        if (client != null) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }

        if (dnsRouter != null) {
            dnsRouter.close();
            dnsRouter = null;
        }
        if (dnsServerSelector != null) {
            dnsServerSelector.close();
            dnsServerSelector = null;
        }
        selectedServer = null;

        ExecutorService executor = resolveExecutor;
        resolveExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void markServiceStopped() {
        if (preferences != null) {
            preferences.edit()
                    .putBoolean("isStarted", false)
                    .remove("dnsModel")
                    .apply();
        }
    }

    private void showDisconnectedNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                Notification disconnectedNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.disconnected))
                        .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .build();
                manager.notify(NOTIF_ID, disconnectedNotification);
            }
        } catch (Exception e) {
            Timber.d(e, "Failed to show disconnect notification");
        }
    }

    private void removeForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            foregroundStarted.set(false);
        } catch (Exception e) {
            Timber.d(e, "Foreground notification already stopped");
        }
    }

    private void handleDisconnectAction() {
        Timber.i("Notification disconnect requested");
        stopTunnel(true);
        rxBus.sendEvent(new StopEvent());
    }

    private void handleReconnectAction() {
        Timber.i("Notification reconnect requested");
        DNSModel modelToRestart = dnsModel;
        if (modelToRestart == null && preferences != null) {
            String modelJSON = preferences.getString("dnsModel", "");
            if (!modelJSON.isEmpty()) {
                modelToRestart = gson.fromJson(modelJSON, DNSModel.class);
            }
        }

        stopTunnel(false, true);
        bindCurrentUnderlyingNetwork();
        rebindUpstream();

        if (modelToRestart != null) {
            Intent restartIntent = new Intent(this, DNSService.class);
            restartIntent.putExtra(DNS_MODEL, modelToRestart);
            onStartCommand(restartIntent, 0, 0);
        } else {
            Timber.e("Reconnect requested without a saved DNS model");
            stopSelf();
        }
    }

    private void stopTunnel(boolean stopService) {
        stopTunnel(stopService, false);
    }

    private void stopTunnel(boolean stopService, boolean keepDnsModel) {
        // Stop order is important: close the TUN first so a blocking read() exits before join().
        isRunning.set(false);
        vpnOperational.set(false);
        updateHandler.removeCallbacks(updateRunnable);
        if (keepDnsModel) {
            if (preferences != null) {
                preferences.edit().putBoolean("isStarted", false).apply();
            }
        } else {
            markServiceStopped();
            dnsModel = null;
        }
        LogManager.flush(this);
        if (statsManager != null) statsManager.persistNow(this);
        closeTunInterface();

        Thread tunnelThread = mThread;
        if (tunnelThread != null) {
            tunnelThread.interrupt();
            if (tunnelThread != Thread.currentThread()) {
                try {
                    tunnelThread.join(STOP_JOIN_TIMEOUT_MS);
                    if (tunnelThread.isAlive()) {
                        Timber.w("Tunnel thread did not stop within %d ms", STOP_JOIN_TIMEOUT_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Timber.w(e, "Interrupted while waiting for tunnel thread to stop");
                }
            }
        }

        closeOutputStream();

        // Show disconnect notification before removing foreground
        showDisconnectedNotification();

        closeAllResolvers();
        removeForegroundNotification();

        if (stopService) {
            stopSelf();
        }
    }

    private void setFileDescriptor(ParcelFileDescriptor fileDescriptor) {
        this.fileDescriptor = fileDescriptor;
    }

    @Override
    public int onStartCommand(final Intent paramIntent, int p1, int p2) {
        Timber.i("onStartCommand called");
        String action = paramIntent != null ? paramIntent.getAction() : null;
        if (ACTION_DISCONNECT.equals(action)) {
            handleDisconnectAction();
            return Service.START_NOT_STICKY;
        } else if (ACTION_RECONNECT.equals(action)) {
            handleReconnectAction();
            return Service.START_NOT_STICKY;
        }

        if (paramIntent != null) {
            dnsModel = paramIntent.getParcelableExtra(DNS_MODEL);
        }

        if (dnsModel == null && preferences != null) {
            String modelJSON = preferences.getString("dnsModel", "");
            if (!modelJSON.isEmpty()) {
                dnsModel = gson.fromJson(modelJSON, DNSModel.class);
            }
        }

        if (dnsModel == null) {
            Timber.e("Cannot start DNS tunnel without a DNS model");
            stopTunnel(true);
            return Service.START_NOT_STICKY;
        }

        if (isRunning.get()) {
            stopTunnel(false);
        }

        initResolveExecutor();

        isRunning.set(true);
        resetTrafficCounters();
        bindCurrentUnderlyingNetwork();

        if (preferences != null) {
            preferences.edit().putBoolean("isStarted", true).apply();
        }
        rxBus.sendEvent(new StartEvent());

        updateHandler.post(updateRunnable);

        showNotification();

        mThread = new Thread(() -> {
            try {
                String modelJSON = gson.toJson(dnsModel);
                preferences.edit().putString("dnsModel", modelJSON).apply();

                VpnService.Builder tunnelBuilder = new VpnService.Builder();
                tunnelBuilder.setSession(DNSService.this.getText(R.string.app_name).toString())
                        .addAddress("192.168.0.1", 24)
                        .addRoute("0.0.0.0", 0)
                        .addAddress("fd00:1::1", 128)
                        .addRoute("::", 0)
                        .addDnsServer(dnsModel.getFirstDns());

                applyAppFilter(tunnelBuilder);
                
                Timber.i("Starting VPN with DNS: %s", dnsModel.getFirstDns());

                if (dnsModel.getSecondDns() != null && !dnsModel.getSecondDns().isEmpty()) {
                    tunnelBuilder.addDnsServer(dnsModel.getSecondDns());
                    Timber.i("Secondary DNS added: %s", dnsModel.getSecondDns());
                }

                setFileDescriptor(tunnelBuilder.establish());

                if (fileDescriptor == null) {
                    Timber.e("Failed to establish VPN");
                    stopThisService();
                    return;
                }
                vpnOperational.set(true);
                Log.d("DNSDebug", "establish OK vpnRef=" + System.identityHashCode(DNSService.this));
                initOkHttp();
                initDnsRouter();
                selectBestServer();

                resetTrafficCounters();

                try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
                    outputStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
                    ByteBuffer packet = ByteBuffer.allocate(32767);

                    // Exit condition must observe both app stop and thread interrupt; never use while(true).
                    while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            if (inputStream.available() == 0) {
                                try { Thread.sleep(50); } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                continue;
                            }
                            int length = inputStream.read(packet.array());
                            if (length < 0) {
                                break;
                            } else if (length > 0) {
                                packet.limit(length);
                                packet.rewind();
                                handlePacket(packet, length);
                                packet.clear();
                            }
                        } catch (java.io.InterruptedIOException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            if (isRunning.get()) Timber.e(e, "Error processing TUN packet");
                        }
                    }
                }
            } catch (Exception exception) {
                if (isRunning.get()) Timber.e(exception);
            } finally {
                isRunning.set(false);
                closeResources();
                if (Thread.currentThread() == mThread) {
                    mThread = null;
                }
            }
        }, "DNS Changer");
        mThread.start();
        return Service.START_NOT_STICKY;
    }

    private void handlePacket(ByteBuffer packet, int length) {
        if (length < IPV4_MIN_HEADER_LENGTH) return;

        int version = (packet.get(0) >> 4) & 0x0F;
        if (version == 4) {
            handleIPv4(packet, length);
        } else if (version == 6 && length >= 40) {
            handleIPv6(packet, length);
        }
    }

    private void handleIPv4(ByteBuffer packet, int length) {
        int ihl = (packet.get(0) & 0x0F) * 4;
        int totalLength = packet.getShort(2) & 0xFFFF;
        if (ihl < IPV4_MIN_HEADER_LENGTH || ihl > length) return;
        if (totalLength < ihl + UDP_HEADER_LENGTH || totalLength > length) return;
        if (totalLength < ihl + UDP_HEADER_LENGTH) return; // bounds-check before UDP read
        
        byte protocol = packet.get(9);
        if (protocol == 17) { // UDP
            int udpOffset = ihl;
            if (udpOffset + UDP_HEADER_LENGTH > totalLength) return;
            int destPort = packet.getShort(ihl + 2) & 0xFFFF;
            int udpLength = packet.getShort(ihl + 4) & 0xFFFF;
            if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > totalLength) return;
            if (destPort == 53) {
                parseDNS(packet, ihl + UDP_HEADER_LENGTH, udpOffset + udpLength);
            }
        }
    }

    private void handleIPv6(ByteBuffer packet, int length) {
        if (length < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) return; // IPv6 Header + UDP Header
        int payloadLength = packet.getShort(4) & 0xFFFF;
        int packetLength = IPV6_HEADER_LENGTH + payloadLength;
        if (payloadLength < UDP_HEADER_LENGTH || packetLength > length) return;
        byte nextHeader = packet.get(6);
        if (nextHeader == 17) { // UDP
            int udpOffset = IPV6_HEADER_LENGTH;
            int destPort = packet.getShort(udpOffset + 2) & 0xFFFF;
            int udpLength = packet.getShort(udpOffset + 4) & 0xFFFF;
            if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > packetLength) return;
            if (destPort == 53) {
                parseDNS(packet, udpOffset + UDP_HEADER_LENGTH, udpOffset + udpLength);
            }
        }
    }

    private void parseDNS(ByteBuffer packet, int dnsOffset, int dnsEnd) {
        if (dnsOffset < 0 || dnsEnd > packet.limit() || dnsEnd - dnsOffset < DNS_HEADER_LENGTH) return; // DNS Header is 12 bytes
        ByteBuffer dnsBuffer = packet.duplicate();
        dnsBuffer.position(dnsOffset);
        dnsBuffer.limit(dnsEnd);
        
        int transactionId = dnsBuffer.getShort() & 0xFFFF;
        dnsBuffer.getShort(); // Flags
        int qdCount = dnsBuffer.getShort() & 0xFFFF;

        if (qdCount > 0 && dnsBuffer.remaining() > 0) {
            // Position is already at dnsOffset + 6, we need to skip 6 more bytes to get to Question
            // QD (2) + AN (2) + NS (2) + AR (2) = 8 bytes after ID(2) and Flags(2).
            // Currently at Offset+6 (ID, Flags, QD). Next are AN, NS, AR (6 bytes).
            if (dnsBuffer.remaining() < 6) return;
            dnsBuffer.getShort(); // AN
            dnsBuffer.getShort(); // NS
            dnsBuffer.getShort(); // AR
            
            String domain = parseDomainName(dnsBuffer);
            if (dnsBuffer.remaining() >= 4) { // QTYPE (2) + QCLASS (2)
                int type = dnsBuffer.getShort() & 0xFFFF;
                dnsBuffer.getShort(); // QCLASS
                
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
                    if (responseBuf.remaining() >= 2) {
                        responseBuf.putShort((short) transactionId);
                        handleDoHResponse(responseBuf.array(), packet, dnsOffset);
                    }
                    return;
                }

                int rawQueryLength = dnsEnd - dnsOffset;
                if (rawQueryLength <= 0) return;
                byte[] rawQuery = new byte[rawQueryLength];
                ByteBuffer rawQueryBuffer = packet.duplicate();
                rawQueryBuffer.position(dnsOffset);
                rawQueryBuffer.limit(dnsEnd);
                rawQueryBuffer.get(rawQuery);

                byte[] originalPacket = Arrays.copyOf(packet.array(), packet.limit());

                submitResolve(rawQuery, originalPacket, dnsOffset, domain, type);
            }
        }
    }

    private void submitResolve(byte[] rawQuery, byte[] originalPacket, int dnsOffset, String domain, int type) {
        ExecutorService executor = resolveExecutor;
        if (executor == null || executor.isShutdown()) {
            Timber.w("Resolve executor is not available");
            return;
        }
        try {
            executor.execute(() -> resolveDns(rawQuery, originalPacket, dnsOffset, domain, type));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Timber.w("Resolve task rejected: %s", e.getMessage());
        }
    }

    private void resolveDns(byte[] rawQuery, byte[] originalPacket, int dnsOffset, String domain, int type) {
        DnsServer server = selectedServer;
        DnsRouter router = dnsRouter;

        // Try with the pre-selected best server first
        if (server != null && router != null) {
            if (tryResolveWithRouter(router, server, rawQuery, originalPacket, dnsOffset, domain, type)) {
                return;
            }
        }

        // Fallback: construct fallback candidates from model & repo
        if (dnsModel != null) {
            String primaryIp = dnsModel.getFirstDns();
            String secondaryIp = dnsModel.getSecondDns();
            DnsType primaryType = inferDnsType(dnsModel);

            if (primaryIp != null && !primaryIp.isEmpty()) {
                DnsServer fallbackServer = new DnsServer(
                        "fallback_primary", dnsModel.getName(), dnsModel.getCategory(),
                        primaryType, null, null, primaryIp, null,
                        primaryType == DnsType.DOH ? 443 : (primaryType == DnsType.DOT ? 853 : 53),
                        primaryIp.startsWith("http") ? primaryIp : null
                );
                if (router != null && tryResolveWithRouter(router, fallbackServer, rawQuery, originalPacket, dnsOffset, domain, type)) {
                    return;
                }
            }
            if (secondaryIp != null && !secondaryIp.isEmpty() && !secondaryIp.equals(primaryIp)) {
                DnsServer fallbackServer2 = new DnsServer(
                        "fallback_secondary", dnsModel.getName(), dnsModel.getCategory(),
                        DnsType.PLAIN_UDP, null, null, secondaryIp, null, 53, null
                );
                if (router != null && tryResolveWithRouter(router, fallbackServer2, rawQuery, originalPacket, dnsOffset, domain, type)) {
                    return;
                }
            }
        }

        // Last resort: Cloudflare DoH
        fallbackToCloudflareDoh(rawQuery, originalPacket, dnsOffset, domain, type);
    }

    private boolean tryResolveWithRouter(DnsRouter router, DnsServer server, byte[] rawQuery,
                                          byte[] originalPacket, int dnsOffset, String domain, int type) {
        int maxRetries = getResolverMaxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                byte[] dnsResponse = router.resolve(server, rawQuery);
                if (dnsResponse == null || dnsResponse.length < 12) continue;
                LogManager.addLog(DNSService.this, domain + " | ALLOWED | " + server.getName() + " (" + server.getType() + ")");
                long ttl = extractMinTTL(dnsResponse);
                dnsCache.put(domain, type, dnsResponse, ttl);
                handleDoHResponse(dnsResponse, ByteBuffer.wrap(originalPacket), dnsOffset);
                return true;
            } catch (Exception e) {
                Timber.w(e, "DNS resolve failed (attempt %d/%d) for %s via %s",
                        attempt + 1, maxRetries + 1, domain, server.getName());
            }
        }
        return false;
    }

    private void fallbackToCloudflareDoh(byte[] rawQuery, byte[] originalPacket, int dnsOffset, String domain, int type) {
        int maxRetries = getResolverMaxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                byte[] dnsResponse = executeDoH(rawQuery, FALLBACK_DOH_URL);
                LogManager.addLog(DNSService.this, domain + " | ALLOWED | Cloudflare Fallback");
                long ttl = extractMinTTL(dnsResponse);
                dnsCache.put(domain, type, dnsResponse, ttl);
                handleDoHResponse(dnsResponse, ByteBuffer.wrap(originalPacket), dnsOffset);
                return;
            } catch (Exception e) {
                Timber.w(e, "Cloudflare DoH fallback failed (attempt %d/%d) for %s",
                        attempt + 1, maxRetries + 1, domain);
            }
        }
        LogManager.addLog(DNSService.this, domain + " | FAILED | All upstreams exhausted");
    }

    private DnsType inferDnsType(DNSModel model) {
        String protocol = model.getBestProtocol();
        if ("DoT".equals(protocol)) return DnsType.DOT;
        if ("DoH".equals(protocol)) return DnsType.DOH;
        if (model.getFirstDns() != null && model.getFirstDns().startsWith("http")) return DnsType.DOH;
        return DnsType.PLAIN_UDP;
    }

    private int getResolverMaxRetries() {
        int retries = preferences != null ? preferences.getInt(PREF_RESOLVER_MAX_RETRIES, UPSTREAM_MAX_RETRIES) : UPSTREAM_MAX_RETRIES;
        return Math.max(0, Math.min(retries, 2));
    }

    private byte[] executeDoH(byte[] rawQuery, String dohUrl) throws IOException {
        RequestBody body = RequestBody.create(rawQuery, MediaType.parse("application/dns-message"));
        Request.Builder builder = new Request.Builder()
                .url(dohUrl)
                .post(body)
                .addHeader("Accept", "application/dns-message");
        // Extract host from URL for correct Host header
        try {
            java.net.URI uri = new java.net.URI(dohUrl);
            String host = uri.getHost();
            if (host != null) {
                builder.header("Host", host);
            }
        } catch (Exception ignored) {}
        Request request = builder.build();

        OkHttpClient client = okHttpClient;
        if (client == null) {
            throw new IOException("OkHttp resolver is closed");
        }
        try (Response response = client.newCall(request).execute();
             ResponseBody responseBody = response.body()) {
            if (!response.isSuccessful() || responseBody == null) {
                throw new IOException("DoH error: " + response.code());
            }
            return responseBody.bytes();
        }
    }

    private long extractMinTTL(byte[] response) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(response);
            if (buf.remaining() < 12) return 60;
            buf.position(4);
            int qdCount = buf.getShort() & 0xFFFF;
            int anCount = buf.getShort() & 0xFFFF;
            if (buf.remaining() < 4) return 60;
            buf.position(12);
            
            // Skip questions safely
            for (int i = 0; i < qdCount; i++) {
                if (!buf.hasRemaining()) break;
                skipDomainName(buf);
                if (buf.remaining() < 4) break;
                buf.getShort(); // Type
                buf.getShort(); // Class
            }
            
            // Parse Answers safely
            long minTtl = 300; // Default 5 mins
            for (int i = 0; i < anCount; i++) {
                if (!buf.hasRemaining()) break;
                skipDomainName(buf);
                if (buf.remaining() < 10) break; // Type(2) + Class(2) + TTL(4) + RDLen(2)
                buf.getShort(); // Type
                buf.getShort(); // Class
                long ttl = buf.getInt() & 0xFFFFFFFFL;
                if (i == 0 || ttl < minTtl) minTtl = ttl;
                int rdLength = buf.getShort() & 0xFFFF;
                if (buf.remaining() < rdLength) break;
                buf.position(buf.position() + rdLength);
            }
            return Math.max(minTtl, 10); // Minimum 10 seconds
        } catch (Exception e) {
            Timber.e(e, "Error extracting TTL");
            return 60; // Fallback 1 minute
        }
    }

    private void skipDomainName(ByteBuffer buf) {
        int depth = 0;
        while (buf.hasRemaining() && depth++ < 10) {
            int len = buf.get() & 0xFF;
            if (len == 0) return;
            if ((len & 0xC0) == 0xC0) {
                if (buf.hasRemaining()) buf.get(); // Skip pointer
                return;
            }
            if (buf.remaining() < len) break;
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
        if (outputStream != null && isRunning.get()) {
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
                    packet.get(); // Skip pointer byte
                    if (domain.length() == 0) domain.append("[compressed]");
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
        return domain.length() == 0 ? "unknown" : domain.toString();
    }
}
