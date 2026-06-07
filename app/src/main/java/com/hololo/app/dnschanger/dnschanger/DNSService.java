package com.hololo.app.dnschanger.dnschanger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.hololo.app.dnschanger.DNSChangerApp;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.utils.RxBus;
import com.hololo.app.dnschanger.utils.event.GetServiceInfo;
import com.hololo.app.dnschanger.utils.event.ServiceInfo;
import com.hololo.app.dnschanger.utils.event.StartEvent;
import com.hololo.app.dnschanger.utils.event.StopEvent;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.util.Locale;

import javax.inject.Inject;

import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
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

    private final VpnService.Builder builder = new VpnService.Builder();
    private ParcelFileDescriptor fileDescriptor;
    private Thread mThread;
    private volatile boolean shouldRun = true;
    private DatagramChannel tunnel;
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
        subscribe();
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
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
                setFileDescriptor(null);
            } catch (IOException e) {
                Timber.d(e);
            }
        }
        if (tunnel != null) {
            try {
                tunnel.close();
                setTunnel(null);
            } catch (IOException e) {
                Timber.d(e);
            }
        }
    }

    private void setTunnel(DatagramChannel tunnel) {
        this.tunnel = tunnel;
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
                        .addDnsServer(dnsModel.getFirstDns());
                
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

                while (shouldRun) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception exception) {
                Timber.e(exception);
            } finally {
                closeResources();
            }
        }, "DNS Changer");
        mThread.start();
        return Service.START_STICKY;
    }
}
