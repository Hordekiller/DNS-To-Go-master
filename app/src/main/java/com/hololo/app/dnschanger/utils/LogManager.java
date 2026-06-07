package com.hololo.app.dnschanger.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogManager {
    private static final String PREF_LOGS = "app_logs";
    private static final int MAX_LOGS = 500;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public static void addLog(Context context, String message) {
        // Optimized structured message
        String timestamp = dateFormat.format(new Date());
        String entry = "[" + timestamp + "] " + message;
        
        List<String> logs = getLogs(context);
        logs.add(0, entry);
        
        if (logs.size() > MAX_LOGS) {
            logs = logs.subList(0, MAX_LOGS);
        }
        
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_LOGS, new Gson().toJson(logs))
                .apply();
    }

    public static List<String> getLogs(Context context) {
        String logsJson = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LOGS, "[]");
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        return new Gson().fromJson(logsJson, listType);
    }

    public static void clearLogs(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_LOGS).apply();
    }

    public static void exportToCSV(Context context) {
        List<String> logs = getLogs(context);
        File file = new File(context.getExternalFilesDir(null), "dns_logs.csv");
        try (FileWriter writer = new FileWriter(file)) {
            writer.append("Log Entry\n");
            for (String log : logs) {
                writer.append(log.replace(",", ";")).append("\n");
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
