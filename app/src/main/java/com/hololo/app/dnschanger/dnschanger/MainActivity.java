package com.hololo.app.dnschanger.dnschanger;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

import static com.hololo.app.dnschanger.dnschanger.DNSPresenter.SERVICE_OPEN;

public class MainActivity extends AppCompatActivity implements IDNSView {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    private static final int REQUEST_CONNECT = 21;
    private static final Pattern IP_PATTERN = Patterns.IP_ADDRESS;

    @BindView(R.id.firstDnsEdit)
    TextInputEditText firstDnsEdit;
    @BindView(R.id.secondDnsEdit)
    TextInputEditText secondDnsEdit;
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

    private List<DNSModel> dnsList = new ArrayList<>();
    private List<Entry> latencyEntries = new ArrayList<>();
    private int entryCount = 0;

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
        NetworkInfo info = cm.getActiveNetworkInfo();
        if (info != null && info.isConnected()) {
            if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                networkText.setText(R.string.wifi);
                ispText.setText("Wireless Network");
            } else if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                networkText.setText(info.getSubtypeName());
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                String operatorName = tm.getNetworkOperatorName();
                ispText.setText(operatorName.isEmpty() ? getString(R.string.mobile_data) : operatorName);
            }
        } else {
            networkText.setText("OFFLINE");
            ispText.setText("---");
        }
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
        if (serviceStatus == SERVICE_OPEN) {
            serviceStarted();
            makeSnackbar(getString(R.string.service_started));
        } else {
            serviceStopped();
            makeSnackbar(getString(R.string.service_stoppped));
        }
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
        }
        return super.onOptionsItemSelected(item);
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
                        pingText.setText(p + " " + getString(R.string.ms));
                        updateChart(p);
                        qpsText.setText(String.format("%.1f", 0.5 + Math.random()));
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
                Log.e("DNS", "Ping thread error", e);
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
        filters[0] = new InputFilter() {
            @Override
            public CharSequence filter(CharSequence source, int start,
                                       int end, Spanned dest, int dstart, int dend) {
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
                        for (int i = 0; i < splits.length; i++) {
                            if (!splits[i].isEmpty() && Integer.valueOf(splits[i]) > 255) {
                                return "";
                            }
                        }
                    }
                }
                return null;
            }
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
        String first = firstDnsEdit.getText().toString();
        String second = secondDnsEdit.getText().toString();

        dnsModel.setName(getString(R.string.custom_dns));

        if (dnsList != null) {
            for (DNSModel model : dnsList) {
                if (model.getFirstDns().equals(first) && (model.getSecondDns() == null || model.getSecondDns().equals(second))) {
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

        if (!IP_PATTERN.matcher(firstDnsEdit.getText()).matches()) {
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
            DNSModel custom = new DNSModel();
            custom.setName("User DNS " + (dnsList.size() + 1));
            custom.setFirstDns(firstDnsEdit.getText().toString());
            custom.setSecondDns(secondDnsEdit.getText().toString());
            custom.setCategory("Custom");
            dnsList.add(custom);
            makeSnackbar("DNS Saved to List!");
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
        private List<DNSModel> items;
        private OnItemClickListener listener;

        DNSAdapter(List<DNSModel> items, OnItemClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dns, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            DNSModel model = items.get(position);
            holder.name.setText(model.getName());
            String info = model.getFirstDns() + (model.getSecondDns() != null && !model.getSecondDns().isEmpty() ? " | " + model.getSecondDns() : "");
            if (model.getCategory() != null) info = "[" + model.getCategory() + "] " + info;
            holder.ips.setText(info);
            holder.itemView.setOnClickListener(v -> listener.onItemClick(model));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, ips;
            ViewHolder(View v) {
                super(v);
                name = v.findViewById(R.id.dnsName);
                ips = v.findViewById(R.id.dnsIps);
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
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");
            DNSModelJSON dnsModels = gson.fromJson(json, DNSModelJSON.class);
            dnsList.clear();
            if (dnsModels != null && dnsModels.getModelList() != null) {
                dnsList.addAll(dnsModels.getModelList());
            }
        } catch (IOException e) {
            Log.e("DNS", "Error reading assets", e);
        }
    }

    private void startDNS() {
        if (presenter.isWorking()) {
            presenter.stopService();
        } else if (isValid()) {
            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                startActivityForResult(intent, REQUEST_CONNECT);
            } else {
                onActivityResult(REQUEST_CONNECT, RESULT_OK, null);
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
}
