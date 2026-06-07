package com.hololo.app.dnschanger.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogManager {
    private static final String PREF_LOGS = "app_logs";
    private static final int MAX_LOGS = 200;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public static void addLog(Context context, String message) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Gson gson = new Gson();
        
        String logsJson = prefs.getString(PREF_LOGS, "[]");
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> logs = gson.fromJson(logsJson, listType);
        
        String timestamp = dateFormat.format(new Date());
        logs.add(0, "[" + timestamp + "] " + message);
        
        if (logs.size() > MAX_LOGS) {
            logs = logs.subList(0, MAX_LOGS);
        }
        
        prefs.edit().putString(PREF_LOGS, gson.toJson(logs)).apply();
    }

    public static List<String> getLogs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Gson gson = new Gson();
        String logsJson = prefs.getString(PREF_LOGS, "[]");
        Type listType = new TypeToken<ArrayList<String>>(){}.getType();
        return gson.fromJson(logsJson, listType);
    }

    public static void clearLogs(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_LOGS).apply();
    }
}
