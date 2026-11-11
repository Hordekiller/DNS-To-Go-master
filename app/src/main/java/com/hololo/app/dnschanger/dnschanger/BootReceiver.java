package com.hololo.app.dnschanger.dnschanger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.model.DnsModelSelection;

import timber.log.Timber;

/**
 * Restores the tunnel after reboot if it was running and "auto start" is enabled.
 *
 * <p>If the user's VPN consent is still valid ({@code VpnService.prepare} returns null)
 * the service is started directly (BOOT_COMPLETED is exempt from background-start
 * limits for this FGS type). Otherwise a notification asks the user to open the app.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String BOOT_CHANNEL_ID = "dns_changer_boot";
    private static final int BOOT_NOTIFICATION_ID = 1904;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean autoStart = preferences.getBoolean("startBoot", true);
        boolean wasStarted = preferences.getBoolean(DNSService.PREF_IS_STARTED, false);
        String json = preferences.getString(DNSService.PREF_DNS_MODEL, "");
        if (!autoStart || !wasStarted || json == null || json.isEmpty()) return;

        DNSModel model;
        try {
            model = new Gson().fromJson(json, DNSModel.class);
        } catch (RuntimeException e) {
            Timber.w(e, "Saved DNS model is corrupt; skipping boot start");
            return;
        }
        if (!DnsModelSelection.isStartable(model)) return;

        if (VpnService.prepare(context) == null) {
            try {
                Intent start = new Intent(context, DNSService.class).putExtra(DNSService.DNS_MODEL, model);
                ContextCompat.startForegroundService(context, start);
                return;
            } catch (RuntimeException e) {
                Timber.w(e, "Direct start at boot not allowed; falling back to notification");
            }
        }
        sendNotification(context);
    }

    private void sendNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Without a channel the notification is silently dropped on Android 8+.
            manager.createNotificationChannel(new NotificationChannel(
                    BOOT_CHANNEL_ID, context.getString(R.string.service_ready),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
                .setSmallIcon(R.drawable.dns_changer_ico_inverse)
                .setContentTitle(context.getString(R.string.service_ready))
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_vpn_key_black_24dp, context.getString(R.string.turn_on), pendingIntent)
                .setAutoCancel(true)
                .build();
        try {
            manager.notify(BOOT_NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Timber.w(e, "Cannot post boot notification (permission denied)");
        }
    }
}
