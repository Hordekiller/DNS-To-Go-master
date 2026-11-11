package com.hololo.app.dnschanger.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class LogManager {

    public static class DnsLogEntry {
        public long id;
        public String timestamp;
        public String message;

        public DnsLogEntry() {}

        public DnsLogEntry(long id, String timestamp, String message) {
            this.id = id;
            this.timestamp = timestamp;
            this.message = message;
        }
    }

    private static final String PREF_LOGS = "app_logs";
    private static final int MAX_LOGS = 500;
    private static final int BATCH_FLUSH_SIZE = 50;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private static final Gson gson = new GsonBuilder().create();

    private static final List<DnsLogEntry> pendingLogs = new ArrayList<>();
    /** In-memory copy of the persisted history, loaded once (newest first). */
    private static List<DnsLogEntry> history;
    private static final AtomicInteger sequenceId = new AtomicInteger(0);
    private static final ExecutorService flushExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LogManager");
        t.setDaemon(true);
        return t;
    });

    public static synchronized void addLog(Context context, String message) {
        long uniqueId = (System.currentTimeMillis() * 1000) + (sequenceId.incrementAndGet() % 1000);
        String ts = dateFormat.format(new Date());
        pendingLogs.add(0, new DnsLogEntry(uniqueId, ts, message));

        if (pendingLogs.size() >= BATCH_FLUSH_SIZE) {
            flush(context);
        }
    }

    public static synchronized void flush(Context context) {
        if (pendingLogs.isEmpty()) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (history == null) {
            history = readPersisted(app); // one-time parse instead of on every flush
        }
        history.addAll(0, pendingLogs);
        pendingLogs.clear();
        if (history.size() > MAX_LOGS) {
            history = new ArrayList<>(history.subList(0, MAX_LOGS));
        }
        final List<DnsLogEntry> snapshot = new ArrayList<>(history);
        flushExecutor.execute(() -> PreferenceManager.getDefaultSharedPreferences(app)
                .edit()
                .putString(PREF_LOGS, gson.toJson(snapshot))
                .apply());
    }

    public static List<DnsLogEntry> getLogs(Context context) {
        synchronized (LogManager.class) {
            if (history != null) {
                List<DnsLogEntry> all = new ArrayList<>(pendingLogs);
                all.addAll(history);
                return all.size() > MAX_LOGS ? new ArrayList<>(all.subList(0, MAX_LOGS)) : all;
            }
        }
        return readPersisted(context);
    }

    private static List<DnsLogEntry> readPersisted(Context context) {
        String logsJson = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LOGS, "[]");
        if (logsJson == null || logsJson.equals("[]")) {
            return new ArrayList<>();
        }
        try {
            if (logsJson.trim().startsWith("[")) {
                String trimmed = logsJson.trim();
                if (trimmed.length() > 1 && !trimmed.substring(1).trim().startsWith("{")) {
                    Type oldType = new TypeToken<ArrayList<String>>(){}.getType();
                    List<String> oldLogs = gson.fromJson(logsJson, oldType);
                    if (oldLogs != null) {
                        List<DnsLogEntry> converted = new ArrayList<>(oldLogs.size());
                        for (int i = 0; i < oldLogs.size(); i++) {
                            converted.add(new DnsLogEntry(i, "", oldLogs.get(i)));
                        }
                        return converted;
                    }
                }
            }
            Type listType = new TypeToken<ArrayList<DnsLogEntry>>(){}.getType();
            List<DnsLogEntry> logs = gson.fromJson(logsJson, listType);
            return logs != null ? logs : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static void clearLogs(Context context) {
        synchronized (LogManager.class) {
            pendingLogs.clear();
            history = new ArrayList<>();
        }
        sequenceId.set(0);
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_LOGS).apply();
    }

    public static void exportToCSV(Context context) {
        List<DnsLogEntry> logs = getLogs(context);
        File file = new File(context.getExternalFilesDir(null), "dns_logs.csv");
        try (FileWriter writer = new FileWriter(file)) {
            writer.append("Timestamp,Message\n");
            for (DnsLogEntry entry : logs) {
                String ts = entry.timestamp != null ? entry.timestamp : "";
                String msg = entry.message != null ? entry.message.replace(",", ";") : "";
                writer.append(ts).append(",").append(msg).append("\n");
            }
            writer.flush();
            shareFile(context, file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void shareFile(Context context, File file) {
        Uri uri = FileProvider.getUriForFile(context, "com.hololo.app.dnschanger.provider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "Export Logs"));
    }
}
