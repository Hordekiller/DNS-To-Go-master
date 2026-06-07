package com.hololo.app.dnschanger.dnschanger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.utils.RxBus;
import com.hololo.app.dnschanger.utils.event.StopEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import butterknife.BindView;
import butterknife.ButterKnife;

public class AppFilterActivity extends AppCompatActivity {

    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.appRecyclerView)
    RecyclerView appRecyclerView;
    @BindView(R.id.progressBar)
    ProgressBar progressBar;

    private AppAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String PREF_SELECTED_APPS = "selected_apps";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_filter);
        ButterKnife.bind(this);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        appRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        loadApps();
    }

    private void loadApps() {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> filteredApps = new ArrayList<>();
            
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            Set<String> selectedApps = prefs.getStringSet(PREF_SELECTED_APPS, new HashSet<>());

            for (ApplicationInfo app : allApps) {
                try {
                    PackageInfo pi = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS);
                    if (pi.requestedPermissions != null) {
                        for (String perm : pi.requestedPermissions) {
                            if ("android.permission.INTERNET".equals(perm)) {
                                String name = app.loadLabel(pm).toString();
                                Drawable icon = app.loadIcon(pm);
                                filteredApps.add(new AppInfo(name, app.packageName, icon, selectedApps.contains(app.packageName)));
                                break;
                            }
                        }
                    }
                } catch (PackageManager.NameNotFoundException ignored) {}
            }

            Collections.sort(filteredApps, (a, b) -> a.name.compareToIgnoreCase(b.name));

            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                adapter = new AppAdapter(filteredApps, this::onAppToggled);
                appRecyclerView.setAdapter(adapter);
            });
        });
    }

    private void onAppToggled(AppInfo app, boolean isChecked) {
        app.isSelected = isChecked;
        saveSelection();
    }

    private void saveSelection() {
        if (adapter == null) return;
        Set<String> selected = new HashSet<>();
        for (AppInfo app : adapter.apps) {
            if (app.isSelected) selected.add(app.packageName);
        }
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putStringSet(PREF_SELECTED_APPS, selected)
                .apply();
        
        // If VPN is running, we might want to notify or restart
        checkAndNotifyRestart();
    }

    private void checkAndNotifyRestart() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("isStarted", false)) {
            Toast.makeText(this, R.string.apps_updated, Toast.LENGTH_SHORT).show();
            // Optional: Auto-restart logic here
            // RxBus.instanceOf().sendEvent(new StopEvent());
            // Then start again from MainActivity or handle in Service
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    static class AppInfo {
        String name;
        String packageName;
        Drawable icon;
        boolean isSelected;

        AppInfo(String name, String packageName, Drawable icon, boolean isSelected) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
            this.isSelected = isSelected;
        }
    }

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {
        private final List<AppInfo> apps;
        private final OnAppToggledListener listener;

        interface OnAppToggledListener {
            void onToggled(AppInfo app, boolean isChecked);
        }

        AppAdapter(List<AppInfo> apps, OnAppToggledListener listener) {
            this.apps = apps;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppInfo app = apps.get(position);
            holder.icon.setImageDrawable(app.icon);
            holder.name.setText(app.name);
            holder.packageName.setText(app.packageName);
            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(app.isSelected);
            holder.checkbox.setOnCheckedChangeListener((btn, isChecked) -> listener.onToggled(app, isChecked));
            holder.itemView.setOnClickListener(v -> holder.checkbox.toggle());
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name, packageName;
            CheckBox checkbox;

            ViewHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.appIcon);
                name = v.findViewById(R.id.appName);
                packageName = v.findViewById(R.id.appPackage);
                checkbox = v.findViewById(R.id.appCheckbox);
            }
        }
    }
}
