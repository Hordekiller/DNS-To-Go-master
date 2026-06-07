package com.hololo.app.dnschanger.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.hololo.app.dnschanger.utils.event.StatsUpdateEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class StatsManager {
    private static final String PREF_TOTAL = "stats_total";
    private static final String PREF_BLOCKED = "stats_blocked";
    private static final String PREF_LAST_DATE = "stats_last_date";
    
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong blockedQueries = new AtomicLong(0);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);

    public StatsManager(Context context) {
        load(context);
    }

    private void load(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String today = dayFormat.format(new Date());
        String lastDate = prefs.getString(PREF_LAST_DATE, "");

        if (!today.equals(lastDate)) {
            reset(context, today);
        } else {
            totalQueries.set(prefs.getLong(PREF_TOTAL, 0));
            blockedQueries.set(prefs.getLong(PREF_BLOCKED, 0));
        }
    }

    public void incrementTotal(Context context, RxBus rxBus) {
        long total = totalQueries.incrementAndGet();
        long blocked = blockedQueries.get();
        rxBus.sendEvent(new StatsUpdateEvent(total, blocked));
        persist(context);
    }

    public void incrementBlocked(Context context, RxBus rxBus) {
        long blocked = blockedQueries.incrementAndGet();
        long total = totalQueries.get();
        rxBus.sendEvent(new StatsUpdateEvent(total, blocked));
        persist(context);
    }

    private void persist(Context context) {
        // In a real app, this should be debounced. For now, we use apply() which is async.
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putLong(PREF_TOTAL, totalQueries.get())
                .putLong(PREF_BLOCKED, blockedQueries.get())
                .apply();
    }

    private void reset(Context context, String today) {
        totalQueries.set(0);
        blockedQueries.set(0);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(PREF_TOTAL, 0)
                .putLong(PREF_BLOCKED, 0)
                .putString(PREF_LAST_DATE, today)
                .apply();
    }

    public long getTotal() { return totalQueries.get(); }
    public long getBlocked() { return blockedQueries.get(); }
}
