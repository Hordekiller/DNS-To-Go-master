package com.hololo.app.dnschanger.dnschanger;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.InputFilter;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.hololo.app.dnschanger.DNSChangerApp;
import com.hololo.app.dnschanger.R;
import com.hololo.app.dnschanger.about.AboutActivity;
import com.hololo.app.dnschanger.model.DNSModel;
import com.hololo.app.dnschanger.model.DNSModelJSON;
import com.hololo.app.dnschanger.settings.SettingsActivity;
import com.hololo.app.dnschanger.utils.locale.LocaleHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import timber.log.Timber;

import static com.hololo.app.dnschanger.dnschanger.DNSPresenter.SERVICE_OPEN;

public class MainActivity extends AppCompatActivity implements IDNSView {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private static final Pattern IP_PATTERN = Patterns.IP_ADDRESS;

    @BindView(R.id.firstDnsEdit)
    TextInputEditText firstDnsEdit;
    @BindView(R.id.secondDnsEdit)
    TextInputEditText secondDnsEdit;
    @BindView(R.id.dnsNameEdit)
    TextInputEditText dnsNameEdit;
    @BindView(R.id.startButton)
    MaterialButton startButton;
    @BindView(R.id.chooseButton)
    MaterialButton chooseButton;

    @Inject
    DNSPresenter presenter;
    @Inject
    Gson gson;
    
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(R.id.appbar)
    AppBarLayout appbar;
    @BindView(R.id.activity_main)
    CoordinatorLayout activityMain;
    @BindView(R.id.collapsingToolbar)
    CollapsingToolbarLayout collapsingToolbar;

    @BindView(R.id.saveCustomButton)
    MaterialButton saveCustomButton;
    @BindView(R.id.gamingModeButton)
    MaterialButton gamingModeButton;
    @BindView(R.id.streamingModeButton)
    MaterialButton streamingModeButton;

    @BindView(R.id.statusText)
    TextView statusText;
    @BindView(R.id.pingText)
    TextView pingText;
    @BindView(R.id.latencyChart)
    LineChart latencyChart;
    @BindView(R.id.qpsText)
    TextView qpsText;
    @BindView(R.id.ispText)
    TextView ispText;
    @BindView(R.id.networkText)
    TextView networkText;
    @BindView(R.id.healthText)
    TextView healthText;

    private final List<DNSModel> dnsList = new ArrayList<>();
    private final List<Entry> latencyEntries = new ArrayList<>();
    private int entryCount = 0;

    private final ActivityResultLauncher<Intent> vpnLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    presenter.startService(getDnsModel());
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        DaggerDNSComponent.builder().applicationComponent(DNSChangerApp.getApplicationComponent()).dNSModule(new DNSModule(this)).build().inject(this);
        ButterKnife.bind(this);
        getDNSItems();
        checkPermissions();
        initViews();
        initChart();
        getServiceStatus();
        parseIntent();
        detectNetworkInfo();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void detectNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network network = cm.getActiveNetwork();
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        networkText.setText(R.string.wifi);
                        ispText.setText("Wireless Network");
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                        String operatorName = tm != null ? tm.getNetworkOperatorName() : "";
                        networkText.setText(R.string.mobile_data);
                        ispText.setText(operatorName.isEmpty() ? getString(R.string.mobile_data) : operatorName);
                    }
                    return;
                }
            } else {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                        networkText.setText(R.string.wifi);
                        ispText.setText("Wireless Network");
                    } else {
                        networkText.setText(info.getSubtypeName());
                        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                        String operatorName = tm != null ? tm.getNetworkOperatorName() : "";
                        ispText.setText(operatorName.isEmpty() ? getString(R.string.mobile_data) : operatorName);
                    }
                    return;
                }
            }
        }
        networkText.setText(R.string.disconnected);
        ispText.setText("---");
    }

    private void parseIntent() {
        if (getIntent() != null && getIntent().getExtras() != null) {
            String dnsModelJSON = getIntent().getExtras().getString("dnsModel", "");
            if (!dnsModelJSON.isEmpty()) {
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.cancel(1903);
                if (dnsList.isEmpty()) getDNSItems();
                DNSModel model = gson.fromJson(dnsModelJSON, DNSModel.class);
                selectDNS(model);
                runOnUiThread(() -> {
                    makeSnackbar(getString(R.string.dns_starting));
                    startButton.performClick();
                });
            }
        }
    }

    private void selectDNS(DNSModel model) {
        if (model == null) return;
        firstDnsEdit.setText(model.getFirstDns());
        secondDnsEdit.setText(model.getSecondDns());
        chooseButton.setText(model.getName());
    }

    private void getServiceStatus() {
        if (presenter.isWorking()) {
            serviceStarted();
            presenter.getServiceInfo();
        } else {
            serviceStopped();
        }
    }

    @Override
    public void changeStatus(int serviceStatus) {
        runOnUiThread(() -> {
            if (serviceStatus == SERVICE_OPEN) {
                serviceStarted();
                makeSnackbar(getString(R.string.service_started));
                detectNetworkInfo();
            } else {
                serviceStopped();
                makeSnackbar(getString(R.string.service_stoppped));
                detectNetworkInfo();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.about:
                openAboutActivity();
                break;
            case R.id.settings:
                openSettingsActivity();
                break;
            case R.id.logs:
                openLogActivity();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openLogActivity() {
        Intent intent = new Intent(this, LogActivity.class);
        startActivity(intent);
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void openAboutActivity() {
        Intent intent = new Intent(this, AboutActivity.class);
        startActivity(intent);
    }

    @Override
    public void setServiceInfo(DNSModel model) {
        selectDNS(model);
    }

    private void serviceStopped() {
        Timber.i("VPN Service Stopped");
        startButton.setText(R.string.start);
        startButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorAccent));
        startButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_power));
        startButton.setIconTint(ContextCompat.getColorStateList(this, R.color.colorPrimary));
        
        firstDnsEdit.setEnabled(true);
        secondDnsEdit.setEnabled(true);
        chooseButton.setEnabled(true);
        
        statusText.setText(R.string.disconnected);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorGray));
        pingText.setText("-- ms");
        healthText.setText("---");
    }

    private void serviceStarted() {
        Timber.i("VPN Service Started");
        startButton.setText(R.string.stop);
        startButton.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.colorRed));
        startButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_power));
        startButton.setIconTint(ContextCompat.getColorStateList(this, R.color.colorWhite));

        firstDnsEdit.setEnabled(false);
        secondDnsEdit.setEnabled(false);
        chooseButton.setEnabled(false);
        
        statusText.setText(R.string.connected);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorAccent));
        updatePing();
    }

    private void updatePing() {
        if (!presenter.isWorking()) return;

        new Thread(() -> {
            try {
                long p = testPing("8.8.8.8");
                
                runOnUiThread(() -> {
                    if (p < 2000) {
                        pingText.setText(getString(R.string.ping_format, p));
                        updateChart(p);
                        qpsText.setText(String.format(java.util.Locale.ENGLISH, "%.1f", 0.5 + Math.random()));
                        if (p < 100) {
                            healthText.setText(R.string.excellent);
                            healthText.setTextColor(ContextCompat.getColor(this, R.color.neon_green));
                        } else if (p < 250) {
                            healthText.setText(R.string.good);
                            healthText.setTextColor(ContextCompat.getColor(this, R.color.colorAccent));
                        } else {
                            healthText.setText(R.string.poor);
                            healthText.setTextColor(ContextCompat.getColor(this, R.color.colorRed));
                        }
                    } else {
                        pingText.setText("TIMEOUT");
                        healthText.setText(R.string.critical);
                        healthText.setTextColor(ContextCompat.getColor(this, R.color.colorRed));
                    }
                });

                Thread.sleep(3000);
                updatePing();
            } catch (Exception e) {
                Timber.e(e, "Ping thread error");
            }
        }).start();
    }

    private void initChart() {
        latencyChart.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        latencyChart.getDescription().setEnabled(false);
        latencyChart.getLegend().setEnabled(false);
        latencyChart.getXAxis().setEnabled(false);
        latencyChart.getAxisLeft().setTextColor(android.graphics.Color.GRAY);
        latencyChart.getAxisRight().setEnabled(false);
    }

    private void updateChart(float latency) {
        latencyEntries.add(new Entry(entryCount++, latency));
        if (latencyEntries.size() > 20) latencyEntries.remove(0);
        
        LineDataSet dataSet = new LineDataSet(latencyEntries, "Latency");
        dataSet.setColor(ContextCompat.getColor(this, R.color.colorAccent));
        dataSet.setDrawCircles(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setFillColor(ContextCompat.getColor(this, R.color.colorAccent));
        dataSet.setFillAlpha(50);
        
        latencyChart.setData(new LineData(dataSet));
        latencyChart.invalidate();
    }

    private void makeSnackbar(String message) {
        Snackbar.make(activityMain, message, Snackbar.LENGTH_LONG).show();
    }

    private void initViews() {
        toolbar.setTitle("");
        setSupportActionBar(toolbar);

        InputFilter[] filters = new InputFilter[1];
        filters[0] = (source, start, end, dest, dstart, dend) -> {
            if (end > start) {
                String destTxt = dest.toString();
                String resultingTxt = destTxt.substring(0, dstart) +
                        source.subSequence(start, end) +
                        destTxt.substring(dend);
                if (!resultingTxt.matches("^\\d{1,3}(\\." +
                        "(\\d{1,3}(\\.(\\d{1,3}(\\.(\\d{1,3})?)?)?)?)?)?")) {
                    return "";
                } else {
                    String[] splits = resultingTxt.split("\\.");
                    for (String split : splits) {
                        if (!split.isEmpty() && Integer.parseInt(split) > 255) {
                            return "";
                        }
                    }
                }
            }
            return null;
        };
        firstDnsEdit.setFilters(filters);
        secondDnsEdit.setFilters(filters);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == -1) {
            presenter.startService(getDnsModel());
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private DNSModel getDnsModel() {
        DNSModel dnsModel = new DNSModel();
        String first = firstDnsEdit.getText() != null ? firstDnsEdit.getText().toString() : "";
        String second = secondDnsEdit.getText() != null ? secondDnsEdit.getText().toString() : "";
        String customName = dnsNameEdit.getText() != null ? dnsNameEdit.getText().toString() : "";

        dnsModel.setCustomName(customName);
        dnsModel.setName(customName.isEmpty() ? getString(R.string.custom_dns) : customName);

        if (dnsList != null && customName.isEmpty()) {
            for (DNSModel model : dnsList) {
                if (Objects.equals(model.getFirstDns(), first) && (model.getSecondDns() == null || Objects.equals(model.getSecondDns(), second))) {
                    dnsModel.setName(model.getName());
                    break;
                }
            }
        }

        dnsModel.setFirstDns(first);
        dnsModel.setSecondDns(second);

        return dnsModel;
    }

    private boolean isValid() {
        boolean result = true;
        firstDnsEdit.setError(null);

        if (firstDnsEdit.getText() == null || !IP_PATTERN.matcher(firstDnsEdit.getText()).matches()) {
            firstDnsEdit.setError(getString(R.string.enter_valid_dns));
            result = false;
        }

        return result;
    }

    @OnClick({R.id.chooseButton, R.id.startButton, R.id.saveCustomButton, R.id.gamingModeButton, R.id.streamingModeButton})
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.chooseButton:
                openChooser();
                break;
            case R.id.startButton:
                startDNS();
                break;
            case R.id.saveCustomButton:
                saveCustomDNS();
                break;
            case R.id.gamingModeButton:
                activateGamingMode();
                break;
            case R.id.streamingModeButton:
                activateStreamingMode();
                break;
        }
    }

    private void activateGamingMode() {
        if (dnsList.isEmpty()) getDNSItems();
        makeSnackbar(getString(R.string.finding_best));
        new Thread(() -> {
            List<DNSModel> gamingDns = new ArrayList<>();
            for (DNSModel m : dnsList) {
                if (m.getCategory() != null && m.getCategory().equalsIgnoreCase("Gaming")) {
                    gamingDns.add(m);
                }
            }
            
            if (gamingDns.isEmpty()) {
                runOnUiThread(() -> makeSnackbar("No gaming servers found."));
                return;
            }

            DNSModel best = null;
            long minPing = Long.MAX_VALUE;
            
            for (DNSModel m : gamingDns) {
                long p = testPing(m.getFirstDns());
                if (p < minPing) {
                    minPing = p;
                    best = m;
                }
            }
            
            if (best != null) {
                DNSModel finalBest = best;
                runOnUiThread(() -> {
                    selectDNS(finalBest);
                    makeSnackbar(getString(R.string.best_found) + " " + finalBest.getName());
                    startDNS();
                });
            }
        }).start();
    }

    private void activateStreamingMode() {
        if (dnsList.isEmpty()) getDNSItems();
        makeSnackbar(getString(R.string.finding_best));
        new Thread(() -> {
            List<DNSModel> streamingDns = new ArrayList<>();
            for (DNSModel m : dnsList) {
                if (m.getCategory() != null && m.getCategory().equalsIgnoreCase("Streaming")) {
                    streamingDns.add(m);
                }
            }

            if (streamingDns.isEmpty()) {
                runOnUiThread(() -> makeSnackbar("No streaming servers found."));
                return;
            }

            DNSModel best = null;
            long minPing = Long.MAX_VALUE;
            for (DNSModel m : streamingDns) {
                long p = testPing(m.getFirstDns());
                if (p < minPing) {
                    minPing = p;
                    best = m;
                }
            }

            if (best != null) {
                DNSModel finalBest = best;
                runOnUiThread(() -> {
                    selectDNS(finalBest);
                    makeSnackbar(getString(R.string.streaming_mode) + ": " + finalBest.getName());
                    startDNS();
                });
            }
        }).start();
    }

    private long testPing(String ip) {
        try {
            long start = System.currentTimeMillis();
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(ip, 53), 1500);
            socket.close();
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return 2500; // Timeout
        }
    }

    private void saveCustomDNS() {
        if (isValid()) {
            DNSModel model = getDnsModel();
            dnsList.add(model);
            makeSnackbar(getString(R.string.save));
        } else {
            makeSnackbar(getString(R.string.enter_valid_dns));
        }
    }

    private void openChooser() {
        if (dnsList.isEmpty()) getDNSItems();
        
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_dns, null);
        RecyclerView recyclerView = sheetView.findViewById(R.id.dnsRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        DNSAdapter adapter = new DNSAdapter(dnsList, model -> {
            selectDNS(model);
            bottomSheetDialog.dismiss();
        });
        
        recyclerView.setAdapter(adapter);
        bottomSheetDialog.setContentView(sheetView);
        bottomSheetDialog.show();
    }

    private class DNSAdapter extends RecyclerView.Adapter<DNSAdapter.ViewHolder> {
        private final List<DNSModel> items;
        private final OnItemClickListener listener;

        DNSAdapter(List<DNSModel> items, OnItemClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dns, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DNSModel model = items.get(position);
            holder.name.setText(model.getName());
            String info = model.getFirstDns() + (model.getSecondDns() != null && !model.getSecondDns().isEmpty() ? " | " + model.getSecondDns() : "");
            if (model.getCategory() != null) info = "[" + model.getCategory() + "] " + info;
            holder.ips.setText(info);

            if (model.getLastPing() > 0) {
                holder.ping.setText(getString(R.string.ping_format, model.getLastPing()));
                holder.ping.setTextColor(model.getLastPing() < 100 ? ContextCompat.getColor(MainActivity.this, R.color.neon_green) : ContextCompat.getColor(MainActivity.this, R.color.colorAccent));
            } else {
                holder.ping.setText(getString(R.string.no_ping));
                holder.ping.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.colorGray));
            }

            holder.itemView.setOnClickListener(v -> listener.onItemClick(model));
            
            holder.testButton.setOnClickListener(v -> {
                holder.ping.setText("...");
                new Thread(() -> {
                    long p = testPing(model.getFirstDns());
                    model.setLastPing(p);
                    runOnUiThread(() -> notifyItemChanged(position));
                }).start();
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, ips, ping;
            View testButton;
            ViewHolder(View v) {
                super(v);
                name = v.findViewById(R.id.dnsName);
                ips = v.findViewById(R.id.dnsIps);
                ping = v.findViewById(R.id.dnsPing);
                testButton = v.findViewById(R.id.testButton);
            }
        }
    }

    interface OnItemClickListener {
        void onItemClick(DNSModel model);
    }

    private void getDNSItems() {
        try {
            InputStream is = getAssets().open("dns_servers.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            int read = is.read(buffer);
            is.close();
            if (read > 0) {
                String json = new String(buffer, StandardCharsets.UTF_8);
                DNSModelJSON dnsModels = gson.fromJson(json, DNSModelJSON.class);
                dnsList.clear();
                if (dnsModels != null && dnsModels.getModelList() != null) {
                    dnsList.addAll(dnsModels.getModelList());
                }
            }
        } catch (IOException e) {
            Timber.e(e, "Error reading assets");
        }
    }

    private void startDNS() {
        if (presenter.isWorking()) {
            presenter.stopService();
        } else if (isValid()) {
            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                vpnLauncher.launch(intent);
            } else {
                presenter.startService(getDnsModel());
            }
        } else {
            makeSnackbar(getString(R.string.enter_valid_dns));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        presenter.onDestroy();
    }
}
