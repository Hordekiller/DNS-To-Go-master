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
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int RESOLVE_CORE_THREADS = 4;
    private static final int RESOLVE_MAX_THREADS = 32;
    private static final int RESOLVE_KEEPALIVE_SEC = 30;
    private static final int EDNS_PADDING_MAX = 128;
    private static final int PACKET_POOL_SIZE = 64;
    private static final int PACKET_BUFFER_SIZE = 65535;
    private static final int UPSTREAM_MAX_RETRIES = 1;
    private static final int ESTABLISH_MAX_RETRIES = 3;
    private static final long ESTABLISH_RETRY_DELAY_MS = 500L;
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
    private volatile FileOutputStream outputStream;
    private Thread mThread;
    private PacketPool packetPool;
    private LinkedBlockingQueue<byte[]> tunWriteQueue;
    private Thread tunWriterThread;
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

    private static class PacketPool {
        private final byte[][] buffers;
        private final AtomicInteger head = new AtomicInteger();
        private final Semaphore semaphore;

        PacketPool(int size, int bufSize) {
            buffers = new byte[size][bufSize];
            semaphore = new Semaphore(size);
        }

        byte[] acquire() throws InterruptedException {
            semaphore.acquire();
            return buffers[head.getAndIncrement() & (buffers.length - 1)];
        }

        void release(byte[] buf) {
            semaphore.release();
        }
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        vpnOperational.set(false);
        Timber.i("VPN revoked by system");
        if (rxBus != null) {
            rxBus.sendEvent(new StopEvent());
        }
        stopThisService();
    }

    private void stopThisService() {
        stopTunnel(true);
    }

    @Override
    public void onDestroy() {
        isRunning.set(false);
        vpnOperational.set(false);
        stopTunnel(false);
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        closeResources();
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
        Timber.d("DNSService onCreate() — Starting service initialization");

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        DNSChangerApp.getApplicationComponent().inject(this);
        statsManager = new StatsManager(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // ============================================
        // ANDROID 14+ PERMISSION VALIDATION
        // ============================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.d("Android 14+ detected — Checking FOREGROUND_SERVICE_SPECIAL_USE permission");

            if (checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                    != PackageManager.PERMISSION_GRANTED) {
                Timber.e("CRITICAL: FOREGROUND_SERVICE_SPECIAL_USE permission not granted");
                Timber.e("Service cannot start as foreground service on Android 14+");

                showPermissionErrorNotification();
                stopSelf();
                return;
            }

            Timber.d("FOREGROUND_SERVICE_SPECIAL_USE permission verified ✓");
        }

        // ============================================
        // NOTIFICATION PERMISSION CHECK (Android 13+)
        // ============================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Timber.w("POST_NOTIFICATIONS permission not granted — Notification may not be visible");
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "DNS Changer Service", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        startForegroundNotification();
        registerNetworkCallback();
        subscribe();
    }

    private void showPermissionErrorNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("DNS Service Error")
                .setContentText("Required permissions not granted. Please restart app and allow all permissions.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(999, notification);
        }
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
        if (dnsServerSelector != null) {
            dnsServerSelector.close();
            dnsServerSelector = null;
        }
        selectedServer = null;
        if (fileDescriptor != null) {
            initOkHttp();
            initDnsRouter();
            submitServerSelection();
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
        DNSModel model = dnsModel;
        if (dnsServerSelector == null || model == null) {
            return;
        }
        try {
            DnsServerSelector.Selection selection = dnsServerSelector.select(model);
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

    private void submitServerSelection() {
        ExecutorService executor = resolveExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.execute(() -> {
            if (!isRunning.get()) return;
            selectBestServer();
        });
    }

    private synchronized void initResolveExecutor() {
        if (resolveExecutor == null || resolveExecutor.isShutdown()) {
            resolveExecutor = new ThreadPoolExecutor(
                    RESOLVE_CORE_THREADS,
                    RESOLVE_MAX_THREADS,
                    RESOLVE_KEEPALIVE_SEC, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "DNS Resolver");
                        thread.setDaemon(true);
                        return thread;
                    }
            );
        }
    }

    private void subscribe() {
        subscriber = rxBus.getEvents().subscribe(
            o -> {
                if (o instanceof StopEvent) {
                    if (isRunning.get()) {
                        stopThisService();
                    }
                } else if (o instanceof GetServiceInfo) {
                    DNSModel model = dnsModel;
                    rxBus.sendEvent(new ServiceInfo(model));
                }
            },
            throwable -> {
                Timber.e(throwable, "Error in DNSService RxBus subscription");
            }
        );
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

    private void startForegroundNotification() {
        if (foregroundStarted.get()) return;
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.dns_turbo_active))
                .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        foregroundStarted.set(true);
    }

    private Notification buildNotification() {
        updateTrafficCounters();

        String downUsage = formatBytes(downloadedBytes.get());
        String upUsage = formatBytes(uploadedBytes.get());

        DNSModel model = dnsModel;
        String status = isRunning.get()
                ? (model != null ? getString(R.string.connected_to, serverName()) : getString(R.string.dns_turbo_active))
                : getString(R.string.disconnected);

        String contentText = status
                + " | ↓ " + downUsage + " ↑ " + upUsage;

        if (cachedContentIntent == null) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
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

    private String serverName() {
        DnsServer server = selectedServer;
        if (server != null && server.getName() != null) return server.getName();
        DNSModel model = dnsModel;
        return model != null ? model.getName() : getString(R.string.dns_turbo_active);
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

        Thread writerThread = tunWriterThread;
        tunWriterThread = null;
        if (writerThread != null) {
            writerThread.interrupt();
            if (writerThread != Thread.currentThread()) {
                try {
                    writerThread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        tunWriteQueue = null;
        packetPool = null;

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

        // Keep the model alive across the stop below: stopTunnel(false) clears the
        // instance field, so capture it and restore it before the new thread starts.
        DNSModel incomingModel = dnsModel;

        if (isRunning.get()) {
            stopTunnel(false);
        }

        // Re-apply the model after stopTunnel(false) nulled the instance field.
        if (incomingModel != null) {
            dnsModel = incomingModel;
        }

        if (dnsModel == null) {
            Timber.e("Cannot restart DNS tunnel without a DNS model");
            stopTunnel(true);
            return Service.START_NOT_STICKY;
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
                DNSModel model = dnsModel;
                if (model == null) {
                    Timber.e("DNSModel is null at tunnel start");
                    stopThisService();
                    return;
                }
                String modelJSON = gson.toJson(model);
                preferences.edit().putString("dnsModel", modelJSON).apply();

                VpnService.Builder tunnelBuilder = new VpnService.Builder();
                tunnelBuilder.setSession(DNSService.this.getText(R.string.app_name).toString())
                        .addAddress("192.168.0.1", 24)
                        .addAddress("fd00:1::1", 128)
                        .addDnsServer(model.getFirstDns());

                addDnsRoute(tunnelBuilder, model.getFirstDns());

                applyAppFilter(tunnelBuilder);
                
                Timber.i("Starting VPN with DNS: %s", model.getFirstDns());

                if (model.getSecondDns() != null && !model.getSecondDns().isEmpty()) {
                    tunnelBuilder.addDnsServer(model.getSecondDns());
                    addDnsRoute(tunnelBuilder, model.getSecondDns());
                    Timber.i("Secondary DNS added: %s", model.getSecondDns());
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tunnelBuilder.setBlocking(true);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tunnelBuilder.addDisallowedApplication(getPackageName());
                }
                setFileDescriptor(null);

                int establishAttempts = 0;
                while (fileDescriptor == null && establishAttempts < ESTABLISH_MAX_RETRIES) {
                    establishAttempts++;
                    if (establishAttempts > 1) {
                        Timber.w("VPN establish attempt %d/%d — waiting before retry",
                                establishAttempts, ESTABLISH_MAX_RETRIES);
                        try {
                            Thread.sleep(ESTABLISH_RETRY_DELAY_MS * establishAttempts);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (!isRunning.get()) break;
                    }
                    setFileDescriptor(tunnelBuilder.establish());
                }

                if (fileDescriptor == null) {
                    Timber.e("Failed to establish VPN after %d attempts", establishAttempts);
                    stopThisService();
                    return;
                }
                vpnOperational.set(true);
                Log.d("DNSDebug", "establish OK vpnRef=" + System.identityHashCode(DNSService.this));
                initOkHttp();
                initDnsRouter();

                // Launch server selection asynchronously so the TUN reader starts immediately.
                // During selection the fallback path (user-entered IPs + Cloudflare DoH) handles queries.
                submitServerSelection();

                resetTrafficCounters();

                packetPool = new PacketPool(PACKET_POOL_SIZE, PACKET_BUFFER_SIZE);
                tunWriteQueue = new LinkedBlockingQueue<>();

                tunWriterThread = new Thread(() -> {
                    while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            byte[] data = tunWriteQueue.poll(500, TimeUnit.MILLISECONDS);
                            if (data != null) {
                                FileOutputStream out = outputStream;
                                if (out != null) {
                                    out.write(data);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (IOException e) {
                            if (isRunning.get()) Timber.w(e, "TUN write failed");
                        }
                    }
                }, "TUN Writer");
                tunWriterThread.setDaemon(true);
                tunWriterThread.start();

                FileInputStream inputStream = null;
                FileChannel readChannel = null;
                try {
                    inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
                    readChannel = inputStream.getChannel();
                    outputStream = new FileOutputStream(fileDescriptor.getFileDescriptor());
                    ByteBuffer directBuffer = ByteBuffer.allocateDirect(PACKET_BUFFER_SIZE);

                    while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            directBuffer.clear();
                            int length = readChannel.read(directBuffer);
                            if (length < 0) {
                                break;
                            }
                            if (length > 0) {
                                byte[] buffer = packetPool.acquire();
                                directBuffer.flip();
                                directBuffer.get(buffer, 0, length);

                                ExecutorService executor = resolveExecutor;
                                if (executor != null && !executor.isShutdown()) {
                                    executor.execute(() -> {
                                        try {
                                            handlePacket(ByteBuffer.wrap(buffer, 0, length), length);
                                        } finally {
                                            packetPool.release(buffer);
                                        }
                                    });
                                } else {
                                    packetPool.release(buffer);
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (java.io.InterruptedIOException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (java.io.IOException e) {
                            if (!isRunning.get()) break;
                            Timber.w("TUN read failed", e);
                        } catch (Exception e) {
                            if (isRunning.get()) Timber.e(e, "Error processing TUN packet");
                        }
                    }
                } catch (Exception exception) {
                    if (isRunning.get()) Timber.e(exception);
                } finally {
                    closeOutputStream();
                    if (readChannel != null) {
                        try { readChannel.close(); } catch (IOException ignored) {}
                    }
                    if (inputStream != null) {
                        try { inputStream.close(); } catch (IOException ignored) {}
                    }
                    isRunning.set(false);
                    if (Thread.currentThread() == mThread) {
                        mThread = null;
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
        return Service.START_STICKY;
    }

    private void handlePacket(ByteBuffer packet, int length) {
        DnsPacketHandler.DnsQueryResult result = DnsPacketHandler.parseDnsQuery(packet, length);
        if (result == null) return;

        Timber.d("DNS Query: ID=%d, Domain=%s, Type=%d", result.transactionId, result.domain, result.type);

        statsManager.incrementTotal(this, rxBus);

        if (blockManager.isBlocked(result.domain)) {
            LogManager.addLog(this, result.domain + " | BLOCKED | Local");
            statsManager.incrementBlocked(this, rxBus);
            byte[] nxDomain = DnsResponseBuilder.buildNxDomainResponse(result.transactionId, result.domain);
            byte[] wrapped = DnsResponseBuilder.wrapInIpPacket(nxDomain, ByteBuffer.wrap(result.originalPacket));
            if (wrapped != null) writeToTun(wrapped);
            return;
        }

        byte[] cachedResponse = dnsCache.get(result.domain, result.type);
        if (cachedResponse != null) {
            LogManager.addLog(this, result.domain + " | ALLOWED | Cache");
            ByteBuffer responseBuf = ByteBuffer.wrap(cachedResponse.clone());
            if (responseBuf.remaining() >= 2) {
                responseBuf.putShort((short) result.transactionId);
                byte[] wrapped = DnsResponseBuilder.wrapInIpPacket(responseBuf.array(),
                        ByteBuffer.wrap(result.originalPacket));
                if (wrapped != null) writeToTun(wrapped);
            }
            return;
        }

        submitResolve(result.rawQuery, result.originalPacket, result.dnsOffset, result.domain, result.type);
    }

    private void submitResolve(byte[] rawQuery, byte[] originalPacket, int dnsOffset, String domain, int type) {
        ExecutorService executor = resolveExecutor;
        if (executor == null || executor.isShutdown()) {
            Timber.w("Resolve executor is not available");
            return;
        }
        byte[] paddedQuery = DnsPacketHandler.addEdnsPadding(rawQuery, EDNS_PADDING_MAX);
        try {
            executor.execute(() -> resolveDns(paddedQuery, originalPacket, dnsOffset, domain, type));
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
        DNSModel model = dnsModel;
        if (model != null) {
            String primaryIp = model.getFirstDns();
            String secondaryIp = model.getSecondDns();
            DnsType primaryType = inferDnsType(model);

            if (primaryIp != null && !primaryIp.isEmpty()) {
                DnsServer fallbackServer = new DnsServer(
                        "fallback_primary", model.getName(), model.getCategory(),
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
                        "fallback_secondary", model.getName(), model.getCategory(),
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
                DnsPacketHandler.skipDomainName(buf);
                if (buf.remaining() < 4) break;
                buf.getShort(); // Type
                buf.getShort(); // Class
            }
            
            // Parse Answers safely
            long minTtl = 300; // Default 5 mins
            for (int i = 0; i < anCount; i++) {
                if (!buf.hasRemaining()) break;
                DnsPacketHandler.skipDomainName(buf);
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

    private void handleDoHResponse(byte[] dnsResponse, ByteBuffer originalPacket, int dnsOffset) {
        byte[] wrapped = DnsResponseBuilder.wrapInIpPacket(dnsResponse, originalPacket);
        if (wrapped != null) writeToTun(wrapped);
    }

    private void writeToTun(byte[] data) {
        LinkedBlockingQueue<byte[]> queue = tunWriteQueue;
        if (queue == null || !isRunning.get()) return;
        queue.offer(data);
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

    private void addDnsRoute(VpnService.Builder builder, String dnsIp) {
        try {
            boolean isIpv6 = dnsIp.contains(":");
            int prefix = isIpv6 ? 128 : 32;
            builder.addRoute(dnsIp, prefix);
            Timber.d("Added DNS route: %s/%d", dnsIp, prefix);
        } catch (Exception e) {
            Timber.e(e, "Failed to add DNS route for: %s", dnsIp);
        }
    }

}
