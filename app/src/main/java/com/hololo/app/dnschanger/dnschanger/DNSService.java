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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.hololo.app.dnschanger.DNSChangerApp;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.dns.AppRoutingPolicy;
import com.hololo.app.dnschanger.dns.DnsQueryProcessor;
import com.hololo.app.dnschanger.dns.TunnelSession;
import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.model.DnsServer;
import com.hololo.app.dnschanger.model.DnsType;
import com.hololo.app.dnschanger.resolver.DnsRouter;
import com.hololo.app.dnschanger.resolver.DnsServerSelector;
import com.hololo.app.dnschanger.resolver.ProtectedSocketFactory;
import com.hololo.app.dnschanger.resolver.ResolverConfig;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;
import okhttp3.OkHttpClient;
import timber.log.Timber;

/**
 * DNS-only VPN.
 *
 * <p>Lifecycle: every start / stop / reconnect / network change is executed on a
 * single {@link #control} thread, so transitions are strictly serialized. The
 * per-tunnel state (descriptor, reader/writer threads, resolvers) lives in a
 * {@link Session}; a session that is shutting down only ever touches its own
 * resources, so it cannot tear down its successor.
 *
 * <p>Routing: the tunnel advertises private virtual DNS addresses and routes only
 * those /32 and /128 prefixes, so nothing except DNS enters the tunnel. (Previously
 * the real resolver IPs were routed, which black-holed all other traffic to them,
 * e.g. HTTPS to 1.1.1.1 or DoT to 8.8.8.8 from other apps.)
 */
public class DNSService extends VpnService {
    public static final String DNS_MODEL = "DNSModelIntent";
    public static final String ACTION_DISCONNECT = "com.hololo.app.dnschanger.action.DISCONNECT";
    public static final String ACTION_RECONNECT = "com.hololo.app.dnschanger.action.RECONNECT";

    public static final String PREF_DNS_MODEL = "dnsModel";
    public static final String PREF_IS_STARTED = "isStarted";
    public static final String PREF_SELECTED_APPS = "selected_apps";
    public static final String PREF_STRICT_ENCRYPTED = "strict_encrypted_dns";
    public static final String PREF_ALLOW_FALLBACK = "allow_fallback_resolver";
    private static final String PREF_RESOLVER_MAX_RETRIES = "resolver_max_retries";

    // Virtual addresses (same /24 that Intra uses; very unlikely to clash with a LAN).
    static final String TUN_ADDRESS_V4 = "10.111.222.1";
    static final int TUN_PREFIX_V4 = 24;
    static final String VIRTUAL_DNS_V4 = "10.111.222.3";
    static final String TUN_ADDRESS_V6 = "fd66:f83a:c650::1";
    static final int TUN_PREFIX_V6 = 120;
    static final String VIRTUAL_DNS_V6 = "fd66:f83a:c650::3";

    private static final String CHANNEL_ID = "dns_changer_channel";
    private static final int NOTIF_ID = 1903;
    private static final long NOTIFICATION_INTERVAL_MS = 5000;
    private static final long HANDOVER_SETTLE_MS = 400;
    private static final int UPSTREAM_MAX_RETRIES = 1;
    private static final long QUERY_DEADLINE_MS = 8000;
    private static final int MAX_EXTRA_CANDIDATES = 2;
    private static final String FALLBACK_DOH_URL = "https://1.1.1.1/dns-query";

    @Inject RxBus rxBus;
    @Inject Context context;
    @Inject Gson gson;

    private final BlockManager blockManager = new BlockManager();
    private final DNSCache dnsCache = new DNSCache();
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DNSService-control");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean foregroundStarted = new AtomicBoolean(false);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private final AtomicLong uploadedBytes = new AtomicLong(0);

    private SharedPreferences preferences;
    private StatsManager statsManager;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network defaultNetwork;
    private Disposable subscriber;
    private long lastRxBytes = TrafficStats.UNSUPPORTED;
    private long lastTxBytes = TrafficStats.UNSUPPORTED;

    /** Only written on the control thread; read anywhere. */
    private volatile Session session;
    /** Last model requested (survives session restarts). */
    private volatile DNSModel activeModel;

    private PendingIntent cachedDisconnectIntent;
    private PendingIntent cachedReconnectIntent;
    private PendingIntent cachedContentIntent;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            if (session != null) {
                showNotification();
                mainHandler.postDelayed(this, NOTIFICATION_INTERVAL_MS);
            }
        }
    };

    private final Runnable handoverRunnable = () -> runOnControl(this::applyNetworkChange);

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        DNSChangerApp.getApplicationComponent().inject(this);
        statsManager = new StatsManager(this);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Not fatal: a foreground service still runs, its notification is just hidden.
            Timber.w("POST_NOTIFICATIONS not granted; VPN notification will be hidden");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "DNS Changer Service", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        startForegroundCompat(buildNotification());
        registerNetworkCallback();
        subscribe();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        // Every start must be followed by startForeground() (done in onCreate for the
        // first one; re-assert it for later ones in case we were demoted).
        startForegroundCompat(buildNotification());
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISCONNECT.equals(action)) {
            runOnControl(() -> stopCompletely(true));
            return START_NOT_STICKY;
        }
        if (ACTION_RECONNECT.equals(action)) {
            runOnControl(() -> {
                DNSModel model = activeModel != null ? activeModel : loadSavedModel();
                if (model == null) {
                    Timber.e("Reconnect requested without a saved DNS model");
                    stopCompletely(true);
                } else {
                    startSession(model);
                }
            });
            return START_STICKY;
        }

        DNSModel requested = null;
        if (intent != null) {
            requested = intent.getParcelableExtra(DNS_MODEL);
        }
        final DNSModel fromIntent = requested;
        // Null intent: sticky restart after process death. VpnService action: always-on.
        runOnControl(() -> {
            DNSModel model = fromIntent != null ? fromIntent : loadSavedModel();
            if (model == null) {
                Timber.e("Cannot start DNS tunnel without a DNS model");
                stopCompletely(true);
                return;
            }
            startSession(model);
        });
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        Timber.i("VPN revoked by system or another VPN app");
        runOnControl(() -> stopCompletely(true));
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
            }
            networkCallback = null;
        }
        if (subscriber != null) {
            subscriber.dispose();
            subscriber = null;
        }
        // Stop synchronously enough that no session outlives the service object.
        runOnControl(() -> stopSession("service destroyed"));
        control.shutdown();
        try {
            control.awaitTermination(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        super.onDestroy();
    }

    private void runOnControl(Runnable task) {
        try {
            control.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    Timber.e(e, "Control task failed");
                }
            });
        } catch (RejectedExecutionException e) {
            Timber.w("Control executor already shut down; dropping task");
        }
    }

    // ------------------------------------------------------------------ sessions

    /** Control thread only. Replaces any running session with one for {@code model}. */
    private void startSession(DNSModel model) {
        stopSession("restart");
        activeModel = model;
        boolean strict = preferences.getBoolean(PREF_STRICT_ENCRYPTED, false);

        List<DnsServer> candidates = DnsServerSelector.candidatesFor(model, strict);
        if (candidates.isEmpty()) {
            String reason = strict
                    ? "Strict encrypted DNS is on, but " + model.getName() + " has no DoH/DoT endpoint"
                    : "No usable DNS endpoint for " + model.getName();
            Timber.e(reason);
            LogManager.addLog(this, "START FAILED | " + reason);
            stopCompletely(true);
            return;
        }

        // Keep the last model: it is what always-on VPN, sticky restarts, the
        // notification "Reconnect" action and boot recovery need.
        preferences.edit().putString(PREF_DNS_MODEL, gson.toJson(model)).apply();
        dnsCache.clear(); // answers from a previous provider/filter must not leak through

        ParcelFileDescriptor fd;
        try {
            fd = buildTunnel(model).establish();
        } catch (RuntimeException e) {
            Timber.e(e, "VPN establish() failed");
            fd = null;
        }
        if (fd == null) {
            // null means the user has not (or no longer) consented via VpnService.prepare().
            LogManager.addLog(this, "START FAILED | VPN permission missing or tunnel rejected");
            stopCompletely(true);
            return;
        }

        Session s = new Session(model, fd, strict);
        session = s;
        s.start();

        resetTrafficCounters();
        preferences.edit().putBoolean(PREF_IS_STARTED, true).apply();
        rxBus.sendEvent(new StartEvent());
        mainHandler.removeCallbacks(updateRunnable);
        mainHandler.post(updateRunnable);
        Timber.i("Tunnel %s started for %s (strict=%b)", s.tunnel.name(), model.getName(), strict);
    }

    private VpnService.Builder buildTunnel(DNSModel model) {
        VpnService.Builder builder = new VpnService.Builder();
        builder.setSession(getText(R.string.app_name).toString())
                .addAddress(TUN_ADDRESS_V4, TUN_PREFIX_V4)
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addDnsServer(VIRTUAL_DNS_V4)
                .addDnsServer(VIRTUAL_DNS_V6)
                .addRoute(VIRTUAL_DNS_V4, 32)
                .addRoute(VIRTUAL_DNS_V6, 128)
                .setBlocking(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Default is "metered" for targetSdk >= 29, which would make every network
            // look metered to other apps. Inherit meteredness from the real network.
            builder.setMetered(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            Network n = defaultNetwork;
            builder.setUnderlyingNetworks(n != null ? new Network[]{n} : null);
        }

        Set<String> selected = preferences.getStringSet(PREF_SELECTED_APPS, new HashSet<>());
        AppRoutingPolicy.Result routing = AppRoutingPolicy.apply(new AppRoutingPolicy.Target() {
            @Override
            public boolean addAllowed(String pkg) {
                try {
                    builder.addAllowedApplication(pkg);
                    return true;
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                }
            }

            @Override
            public boolean addDisallowed(String pkg) {
                try {
                    builder.addDisallowedApplication(pkg);
                    return true;
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                }
            }
        }, selected, getPackageName());
        Timber.i("App routing: %s %s (missing: %s)", routing.mode, routing.applied, routing.missing);
        return builder;
    }

    /** Control thread only. Stops the current session but keeps the service alive. */
    private void stopSession(String reason) {
        Session s = session;
        session = null;
        if (s != null) {
            Timber.i("Stopping tunnel %s: %s", s.tunnel.name(), reason);
            s.stop();
        }
        mainHandler.removeCallbacks(updateRunnable);
    }

    /** Control thread only. Stops everything and (optionally) the service itself. */
    private void stopCompletely(boolean stopService) {
        stopSession("stop requested");
        activeModel = null;
        if (preferences != null) preferences.edit().putBoolean(PREF_IS_STARTED, false).apply();
        LogManager.flush(this);
        if (statsManager != null) statsManager.persistNow(this);
        removeForegroundNotification();
        showDisconnectedNotification();
        // Always tell the UI, including after a failed start, so it never stays "connecting".
        if (rxBus != null) rxBus.sendEvent(new StopEvent());
        if (stopService) stopSelf();
    }

    private void onSessionTerminated(Session s, boolean requested, Throwable cause) {
        if (requested) return;
        Timber.w(cause, "Tunnel %s ended unexpectedly", s.tunnel.name());
        runOnControl(() -> {
            if (session == s) stopCompletely(true);
        });
    }

    /** Everything that belongs to one established tunnel. */
    private final class Session {
        final DNSModel model;
        final boolean strict;
        final List<DnsServer> candidates;
        final ParcelFileDescriptor fd;
        final AtomicBoolean operational = new AtomicBoolean(true);
        final TunnelSession tunnel;
        final ExecutorService selectionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DNS selector");
            t.setDaemon(true);
            return t;
        });
        private volatile OkHttpClient okHttpClient;
        private volatile DnsRouter router;
        private volatile DnsServerSelector selector;
        private volatile DnsServer selectedServer;
        private final DnsServer fallbackServer = new DnsServer(
                "global_fallback_doh", "Cloudflare Fallback", "FALLBACK", DnsType.DOH,
                null, null, null, null, 443, FALLBACK_DOH_URL);

        Session(DNSModel model, ParcelFileDescriptor fd, boolean strict) {
            this.model = model;
            this.fd = fd;
            this.strict = strict;
            this.candidates = DnsServerSelector.candidatesFor(model, strict);
            FileInputStream in = new FileInputStream(fd.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
            DnsQueryProcessor processor = new DnsQueryProcessor(
                    blockManager, dnsCache, new QueryLogger(), System::currentTimeMillis,
                    resolverMaxRetries(), QUERY_DEADLINE_MS);
            this.tunnel = new TunnelSession(
                    new TunSource(in, fd),
                    (packet, length) -> out.write(packet, 0, length),
                    processor,
                    this::upstreams,
                    TunnelSession.Config.defaults(),
                    (t, requested, cause) -> onSessionTerminated(this, requested, cause));
            initUpstream();
        }

        void start() {
            tunnel.start();
            submitSelection();
        }

        void stop() {
            operational.set(false);
            tunnel.stop();
            selectionExecutor.shutdownNow();
            closeUpstream();
            try {
                fd.close();
            } catch (IOException ignored) {
            }
        }

        private synchronized void initUpstream() {
            OkHttpClient client = new OkHttpClient.Builder()
                    .socketFactory(new ProtectedSocketFactory(DNSService.this, operational))
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
            okHttpClient = client;
            router = new DnsRouter(client, DNSService.this, ResolverConfig.defaults());
            selector = new DnsServerSelector(DNSService.this, ResolverConfig.defaults());
        }

        private synchronized void closeUpstream() {
            OkHttpClient client = okHttpClient;
            okHttpClient = null;
            if (client != null) {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdownNow();
            }
            DnsRouter r = router;
            router = null;
            if (r != null) r.close();
            DnsServerSelector sel = selector;
            selector = null;
            if (sel != null) sel.close();
            selectedServer = null;
        }

        /** New network: drop pooled connections bound to the old one and re-probe. */
        void rebindUpstream() {
            if (!operational.get()) return;
            closeUpstream();
            initUpstream();
            submitSelection();
        }

        private void submitSelection() {
            try {
                selectionExecutor.execute(() -> {
                    DnsServerSelector sel = selector;
                    if (sel == null || !operational.get()) return;
                    try {
                        DnsServerSelector.Selection selection = sel.select(model, strict);
                        if (!operational.get()) return;
                        selectedServer = selection.getServer();
                        LogManager.addLog(DNSService.this, "Server Select: "
                                + (selectedServer != null ? selectedServer.getName() : "none")
                                + " via " + selection.getProtocol()
                                + " (" + selection.getLatencyMs() + "ms"
                                + (selection.isVerified() ? "" : ", unverified") + ")");
                    } catch (Exception e) {
                        Timber.e(e, "Failed to select best DNS server");
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // session is stopping
            }
        }

        /**
         * Ordered upstreams for one query: the probed best server, then up to two
         * other candidates of the same provider (strict mode: encrypted only), then —
         * only if allowed and not strict — a clearly-labelled global fallback.
         */
        List<DnsQueryProcessor.Upstream> upstreams() {
            DnsRouter r = router;
            if (r == null) return Collections.emptyList();
            List<DnsQueryProcessor.Upstream> list = new ArrayList<>();
            DnsServer best = selectedServer;
            if (best != null) list.add(new RouterUpstream(r, best));
            int extra = 0;
            for (DnsServer candidate : candidates) {
                if (extra >= MAX_EXTRA_CANDIDATES) break;
                if (best != null && candidate.getId().equals(best.getId())) continue;
                list.add(new RouterUpstream(r, candidate));
                extra++;
            }
            if (!strict && preferences.getBoolean(PREF_ALLOW_FALLBACK, true)) {
                list.add(new RouterUpstream(r, fallbackServer));
            }
            return list;
        }
    }

    private static final class RouterUpstream implements DnsQueryProcessor.Upstream {
        private final DnsRouter router;
        private final DnsServer server;

        RouterUpstream(DnsRouter router, DnsServer server) {
            this.router = router;
            this.server = server;
        }

        @Override
        public String label() {
            return server.getName() + " (" + server.getType() + ")";
        }

        @Override
        public boolean encrypted() {
            return DnsServerSelector.isEncrypted(server.getType());
        }

        @Override
        public byte[] exchange(byte[] query) throws Exception {
            return router.resolve(server, query);
        }
    }

    /** Blocking TUN reader; {@link #close()} or a thread interrupt unblocks it. */
    private static final class TunSource implements TunnelSession.PacketSource {
        private final FileInputStream in;
        private final FileChannel channel;
        private final ParcelFileDescriptor fd;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        TunSource(FileInputStream in, ParcelFileDescriptor fd) {
            this.in = in;
            this.channel = in.getChannel();
            this.fd = fd;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            // FileChannel reads are interruptible (ClosedByInterruptException).
            return channel.read(ByteBuffer.wrap(buffer));
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { channel.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
            try { fd.close(); } catch (IOException ignored) {}
        }
    }

    private final class QueryLogger implements DnsQueryProcessor.Listener {
        @Override
        public void onQuery(String domain, int type) {
            statsManager.incrementTotal(DNSService.this, rxBus);
        }

        @Override
        public void onBlocked(String domain) {
            LogManager.addLog(DNSService.this, domain + " | BLOCKED | Local");
            statsManager.incrementBlocked(DNSService.this, rxBus);
        }

        @Override
        public void onCacheHit(String domain) {
            LogManager.addLog(DNSService.this, domain + " | ALLOWED | Cache");
        }

        @Override
        public void onResolved(String domain, String upstreamLabel) {
            LogManager.addLog(DNSService.this, domain + " | ALLOWED | " + upstreamLabel);
        }

        @Override
        public void onUpstreamError(String domain, String upstreamLabel, String reason) {
            Timber.d("Upstream %s failed for %s: %s", upstreamLabel, domain, reason);
        }

        @Override
        public void onFailed(String domain, String reason) {
            LogManager.addLog(DNSService.this, domain + " | FAILED | " + reason);
        }
    }

    private int resolverMaxRetries() {
        int retries = preferences != null ? preferences.getInt(PREF_RESOLVER_MAX_RETRIES, UPSTREAM_MAX_RETRIES) : UPSTREAM_MAX_RETRIES;
        return Math.max(0, Math.min(retries, 2));
    }

    @Nullable
    private DNSModel loadSavedModel() {
        String json = preferences != null ? preferences.getString(PREF_DNS_MODEL, "") : "";
        if (json == null || json.isEmpty()) return null;
        try {
            return gson.fromJson(json, DNSModel.class);
        } catch (RuntimeException e) {
            Timber.w(e, "Saved DNS model is corrupt");
            return null;
        }
    }

    // ------------------------------------------------------------------ network

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                defaultNetwork = network;
                scheduleHandover();
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (network.equals(defaultNetwork)) defaultNetwork = null;
                scheduleHandover();
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
        } catch (RuntimeException e) {
            Timber.w(e, "Could not register network callback");
            networkCallback = null;
        }
    }

    /**
     * Coalesces bursts of callbacks (lost+available during Wi-Fi/mobile handover) and
     * always applies the <em>latest</em> state. The old 500 ms "ignore" debounce could
     * drop the onAvailable that follows an onLost, leaving the VPN with no network.
     */
    private void scheduleHandover() {
        mainHandler.removeCallbacks(handoverRunnable);
        mainHandler.postDelayed(handoverRunnable, HANDOVER_SETTLE_MS);
    }

    /** Control thread only. */
    private void applyNetworkChange() {
        Network n = defaultNetwork;
        Timber.i("Default network now %s", n);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                setUnderlyingNetworks(n != null ? new Network[]{n} : null);
            } catch (RuntimeException e) {
                Timber.w(e, "setUnderlyingNetworks failed");
            }
        }
        Session s = session;
        if (s != null && n != null) s.rebindUpstream();
    }

    // ------------------------------------------------------------------ rx bus

    private void subscribe() {
        subscriber = rxBus.getEvents().subscribe(
                o -> {
                    if (o instanceof StopEvent) {
                        if (session != null) runOnControl(() -> stopCompletely(true));
                    } else if (o instanceof GetServiceInfo) {
                        DNSModel model = activeModel;
                        if (model != null) rxBus.sendEvent(new ServiceInfo(model));
                    }
                },
                throwable -> Timber.e(throwable, "Error in DNSService RxBus subscription")
        );
    }

    // ------------------------------------------------------------------ notifications

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
            foregroundStarted.set(true);
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException (API 31+) or SecurityException.
            Timber.e(e, "startForeground failed");
        }
    }

    private void showNotification() {
        Notification notification = buildNotification();
        if (!foregroundStarted.get()) {
            startForegroundCompat(notification);
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIF_ID, notification);
    }

    private Notification buildNotification() {
        updateTrafficCounters();
        boolean running = session != null;
        String status = running
                ? getString(R.string.connected_to, serverName())
                : getString(R.string.dns_turbo_active);
        String contentText = status + " | ↓ " + formatBytes(downloadedBytes.get())
                + " ↑ " + formatBytes(uploadedBytes.get());

        if (cachedContentIntent == null) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            cachedContentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        }
        if (cachedDisconnectIntent == null) {
            cachedDisconnectIntent = PendingIntent.getService(this, 1,
                    new Intent(this, DNSService.class).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE);
        }
        if (cachedReconnectIntent == null) {
            cachedReconnectIntent = PendingIntent.getService(this, 2,
                    new Intent(this, DNSService.class).setAction(ACTION_RECONNECT),
                    PendingIntent.FLAG_IMMUTABLE);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                .setContentIntent(cachedContentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_vpn_key_black_24dp, getString(R.string.disconnect), cachedDisconnectIntent)
                .addAction(R.drawable.ic_vpn_key_black_24dp, getString(R.string.reconnect), cachedReconnectIntent)
                .build();
    }

    private void removeForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException e) {
            Timber.d(e, "Foreground notification already stopped");
        }
        foregroundStarted.set(false);
    }

    /** Posted after stopForeground(REMOVE), otherwise it is removed immediately. */
    private void showDisconnectedNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.disconnected))
                    .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                    .setContentIntent(cachedContentIntent)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .build();
            manager.notify(NOTIF_ID, n);
        } catch (RuntimeException e) {
            Timber.d(e, "Failed to show disconnect notification");
        }
    }

    private String serverName() {
        Session s = session;
        if (s != null) {
            DnsServer server = s.selectedServer;
            if (server != null && server.getName() != null) return server.getName();
            return s.model.getName();
        }
        DNSModel model = activeModel;
        return model != null ? model.getName() : getString(R.string.dns_turbo_active);
    }

    private void updateTrafficCounters() {
        long rx = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long tx = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        if (rx != TrafficStats.UNSUPPORTED && lastRxBytes != TrafficStats.UNSUPPORTED && rx >= lastRxBytes) {
            downloadedBytes.addAndGet(rx - lastRxBytes);
        }
        if (tx != TrafficStats.UNSUPPORTED && lastTxBytes != TrafficStats.UNSUPPORTED && tx >= lastTxBytes) {
            uploadedBytes.addAndGet(tx - lastTxBytes);
        }
        lastRxBytes = rx;
        lastTxBytes = tx;
    }

    private void resetTrafficCounters() {
        downloadedBytes.set(0);
        uploadedBytes.set(0);
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.ENGLISH, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
