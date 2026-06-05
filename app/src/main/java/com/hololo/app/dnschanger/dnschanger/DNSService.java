package com.hololo.app.dnschanger.dnschanger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;

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
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;

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

    private VpnService.Builder builder = new VpnService.Builder();
    private ParcelFileDescriptor fileDescriptor;
    private Thread mThread;
    private boolean shouldRun = true;
    private DatagramChannel tunnel;
    private DNSModel dnsModel;
    private SharedPreferences preferences;

    private Disposable subscriber;

    private void stopThisService() {
        this.shouldRun = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
        subscriber = rxBus.getEvents().subscribe(new Consumer<Object>() {
            @Override
            public void accept(Object o) throws Exception {
                if (o instanceof StopEvent) {
                    stopThisService();
                } else if (o instanceof GetServiceInfo) {
                    rxBus.sendEvent(new ServiceInfo(dnsModel));
                }
            }
        });
    }

    private void showNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "DNS Changer Service", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(dnsModel != null ? getString(R.string.connected_to, dnsModel.getName()) : getString(R.string.dns_turbo_active))
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
        if (paramIntent != null) {
            dnsModel = paramIntent.getParcelableExtra(DNS_MODEL);
        }

        if (dnsModel == null && preferences != null) {
            String modelJSON = preferences.getString("dnsModel", "");
            if (!modelJSON.isEmpty()) {
                dnsModel = gson.fromJson(modelJSON, DNSModel.class);
            }
        }

        showNotification();

        mThread = new Thread(new Runnable() {
            public void run() {
                try {
                    if (dnsModel == null) return;

                    String modelJSON = gson.toJson(dnsModel);
                    preferences.edit().putString("dnsModel", modelJSON).apply();

                    builder.setSession(DNSService.this.getText(R.string.app_name).toString())
                            .addAddress("192.168.0.1", 24)
                            .addDnsServer(dnsModel.getFirstDns());

                    if (dnsModel.getSecondDns() != null && !dnsModel.getSecondDns().isEmpty()) {
                        builder.addDnsServer(dnsModel.getSecondDns());
                    }

                    setFileDescriptor(builder.establish());
                    
                    if (fileDescriptor == null) {
                        Timber.e("Failed to establish VPN");
                        stopThisService();
                        return;
                    }

                    while (shouldRun) {
                        Thread.sleep(100L);
                    }
                } catch (Exception exception) {
                    Timber.e(exception);
                } finally {
                    closeResources();
                }
            }
        }, "DNS Changer");
        mThread.start();
        rxBus.sendEvent(new StartEvent());
        preferences.edit().putBoolean("isStarted", true).apply();
        return Service.START_STICKY;
    }
}
