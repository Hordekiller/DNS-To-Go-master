package com.hololo.app.dnschanger.dnschanger

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.hololo.app.dnschanger.DNSChangerApp
import com.hololo.app.dnschanger.R
import com.hololo.app.dnschanger.about.AboutActivity
import com.hololo.app.dnschanger.model.DNSModel
import com.hololo.app.dnschanger.model.DnsServerRepository
import com.hololo.app.dnschanger.model.DnsType
import com.hololo.app.dnschanger.ui.screens.DnsPickerDialog
import com.hololo.app.dnschanger.ui.screens.DrawerContent
import com.hololo.app.dnschanger.ui.screens.DrawerItem
import com.hololo.app.dnschanger.ui.screens.MainScreen
import com.hololo.app.dnschanger.ui.screens.MainUiState
import com.hololo.app.dnschanger.ui.theme.DnsChangerTheme
import com.hololo.app.dnschanger.utils.RateManager
import com.hololo.app.dnschanger.utils.event.StatsUpdateEvent
import com.hololo.app.dnschanger.utils.locale.LocaleHelper
import com.hololo.app.dnschanger.settings.SettingsActivity
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : AppCompatActivity(), IDNSView {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    @Inject lateinit var presenter: DNSPresenter
    @Inject lateinit var gson: Gson

    private val dnsList = mutableListOf<DNSModel>()
    private var pendingVpnModel: DNSModel? = null
    private var selectedModel: DNSModel? = null

    private var state by mutableStateOf(MainUiState())
    private var showDnsPicker by mutableStateOf(false)
    private var darkTheme by mutableStateOf(true)
    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingExecutor: java.util.concurrent.ScheduledExecutorService? =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var pingActive = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val model = pendingVpnModel ?: currentModel
            pendingVpnModel = null
            presenter.startService(model)
        } else {
            pendingVpnModel = null
            showToast(getString(R.string.enter_valid_dns))
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }

        if (allGranted) {
            Timber.d("All permissions granted ✓")
            showToast("Permissions granted - You can now start VPN")
            pendingVpnModel?.let { model ->
                pendingVpnModel = null
                startDNS(model)
            }
        } else {
            Timber.w("Some permissions denied")

            val deniedPermissions = permissions.filter { !it.value }.keys
            Timber.w("Denied permissions: $deniedPermissions")

            showPermissionRationaleDialog(deniedPermissions.toList())
        }
    }

    private fun startDNS(model: DNSModel) {
        if (presenter.isWorking) {
            presenter.stopService()
            return
        }

        val intent = VpnService.prepare(this)
        if (intent != null) {
            Timber.d("VPN not yet authorized — Requesting user consent")
            pendingVpnModel = model
            vpnLauncher.launch(intent)
        } else {
            Timber.d("VPN already authorized — Starting service directly")
            presenter.startService(model)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingVpnModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            savedInstanceState?.getParcelable("pendingVpnModel", DNSModel::class.java)
        } else {
            @Suppress("DEPRECATION")
            savedInstanceState?.getParcelable("pendingVpnModel")
        }
        DaggerDNSComponent.builder()
            .applicationComponent(DNSChangerApp.getApplicationComponent())
            .dNSModule(DNSModule(this))
            .build().inject(this)
        checkPermissions()
        loadDnsItems()
        subscribeToEvents()
        enableEdgeToEdge()
        darkTheme = preferences?.getBoolean("dark_theme", true) ?: true
        setContent { AppContent() }
        getServiceStatus()
        showMyketRating()
    }

    override fun onResume() {
        super.onResume()
        getServiceStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingVpnModel?.let { model ->
            outState.putParcelable("pendingVpnModel", model)
        }
    }

    private fun showMyketRating() {
        if (!RateManager.shouldShow(this)) return
        AlertDialog.Builder(this)
            .setTitle("DNS To Go")
            .setMessage("اگر از برنامه راضی هستید لطفا به ما در مایکت امتیاز دهید")
            .setPositiveButton("امتیاز می‌دهم") { _, _ ->
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("myket://comment?id=com.hololo.app.dnschanger")
                )
                startActivity(intent)
                RateManager.markShown(this)
            }
            .setNegativeButton("بعدا") { _, _ ->
                RateManager.markShown(this)
            }
            .show()
    }

    private val preferences by lazy {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppContent() {
        DnsChangerTheme(darkTheme = darkTheme) {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var selectedTab by remember { mutableStateOf(0) }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    DrawerContent(
                        currentServerName = state.dnsName.ifEmpty { "Custom DNS" },
                        isDarkTheme = darkTheme,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            preferences.edit().putBoolean("dark_theme", darkTheme).apply()
                        },
                        onNavigate = { item ->
                            scope.launch { drawerState.close() }
                            handleDrawerNavigation(item)
                        }
                    )
                }
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.dns_changer_title)) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Home, null) },
                                label = { Text(stringResource(R.string.home)) },
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                label = { Text(stringResource(R.string.tab_logs)) },
                                selected = selectedTab == 1,
                                onClick = {
                                    selectedTab = 1
                                    startActivity(Intent(this@MainActivity, LogActivity::class.java))
                                }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text(stringResource(R.string.settings)) },
                                selected = selectedTab == 2,
                                onClick = {
                                    selectedTab = 2
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    MainScreen(
                        state = state,
                        onStartStopClick = { startDNS() },
                        onSelectServerClick = { showDnsPicker = true },
                        onPrimaryDnsChange = { state = state.copy(primaryDns = it) },
                        onSecondaryDnsChange = { state = state.copy(secondaryDns = it) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }

                if (showDnsPicker) {
                    DnsPickerDialog(
                        dnsList = dnsList,
                        onItemClick = { model ->
                            selectDnsModel(model)
                            showDnsPicker = false
                        },
                        onTestClick = { model, callback ->
                            Thread {
                                val p = testPing(model.firstDns)
                                model.lastPing = p
                                runOnUiThread { callback(p) }
                            }.start()
                        },
                        onDismiss = { showDnsPicker = false },
                    )
                }
            }
        }
    }

    private fun handleDrawerNavigation(item: DrawerItem) {
        when (item) {
            DrawerItem.HOME -> {}
            DrawerItem.LOGS -> startActivity(Intent(this, LogActivity::class.java))
            DrawerItem.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            DrawerItem.APPS -> startActivity(Intent(this, AppFilterActivity::class.java))
            DrawerItem.ABOUT -> startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private val currentModel: DNSModel
        get() = DNSModel().apply {
            name = getString(R.string.custom_dns)
            firstDns = state.primaryDns
            secondDns = state.secondaryDns
        }

    private fun checkPermissions() {
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
            }
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun getServiceStatus() {
        if (presenter.isWorking && isOurServiceRunning()) {
            serviceStarted()
            presenter.getServiceInfo()
        } else {
            if (presenter.isWorking) {
                preferences?.edit()?.putBoolean("isStarted", false)?.apply()
            }
            serviceStopped()
        }
    }

    private fun isOurServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DNSService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun changeStatus(serviceStatus: Int) {
        runOnUiThread {
            if (serviceStatus == DNSPresenter.SERVICE_OPEN) serviceStarted()
            else serviceStopped()
        }
    }

    override fun setServiceInfo(model: DNSModel) {
        selectedModel = model
        state = state.copy(
            dnsName = model.name,
            primaryDns = model.firstDns,
            secondaryDns = model.secondDns,
        )
    }

    private fun serviceStopped() {
        Timber.i("VPN Service Stopped")
        pingActive = false
        state = state.copy(isRunning = false, pingMs = -1, latencyHistory = emptyList())
    }

    private fun serviceStarted() {
        Timber.i("VPN Service Started")
        updatePing()
        state = state.copy(isRunning = true)
    }

    private fun updatePing() {
        if (!presenter.isWorking || pingActive) return
        pingActive = true
        performPing()
    }

    private fun performPing() {
        if (!presenter.isWorking) {
            pingActive = false
            return
        }
        var executor = pingExecutor
        if (executor == null || executor.isShutdown) {
            executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            pingExecutor = executor
        }
        try {
            executor.schedule({
                try {
                    val p = testPing("8.8.8.8")
                    runOnUiThread {
                        val hist = state.latencyHistory.toMutableList()
                        if (p < 2000) {
                            hist.add(p.toFloat())
                            if (hist.size > 20) hist.removeAt(0)
                            state = state.copy(pingMs = p, latencyHistory = hist)
                        } else {
                            state = state.copy(pingMs = -1)
                        }
                    }
                    runOnUiThread { performPing() }
                } catch (e: Exception) {
                    Timber.e(e, "Ping error")
                    runOnUiThread { performPing() }
                }
            }, 3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Timber.w(e, "Ping task rejected, reinitializing executor")
            pingExecutor = null
            pingActive = false
        }
    }

    private fun testPing(ip: String): Long {
        try {
            val start = System.currentTimeMillis()
            if (java.net.InetAddress.getByName(ip).isReachable(2000))
                return System.currentTimeMillis() - start
        } catch (_: Exception) {}
        return 2000
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private var eventsDisposable: io.reactivex.disposables.Disposable? = null

    private fun subscribeToEvents() {
        eventsDisposable = presenter.events.subscribe(
            { o ->
                if (o is StatsUpdateEvent) {
                    state = state.copy(
                        totalQueries = o.total,
                        blockedQueries = o.blocked,
                        blockPercent = if (o.total > 0) (o.blocked * 100.0 / o.total).toFloat() else 0f,
                    )
                }
            },
            { throwable ->
                Timber.e(throwable, "Error in MainActivity event subscription")
            }
        )
    }

    private fun startDNS() {
        if (presenter.isWorking) {
            presenter.stopService()
            return
        }

        val model = currentModel
        pendingVpnModel = model

        // Validate Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w("Cannot start VPN - POST_NOTIFICATIONS permission not granted")
                showToast("Notification permission required to run VPN service")
                checkPermissions()
                return
            }
        }

        // Validate Android 14+ foreground service permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w("Cannot start VPN - FOREGROUND_SERVICE_SPECIAL_USE permission not granted")
                showToast("Foreground service permission required for Android 14+")
                checkPermissions()
                return
            }
        }

        // All permissions validated — proceed with VPN setup
        Timber.d("All permissions validated ✓ — Starting VPN preparation")

        val intent = VpnService.prepare(this)
        if (intent != null) {
            Timber.d("VPN not yet authorized — Requesting user consent")
            vpnLauncher.launch(intent)
        } else {
            Timber.d("VPN already authorized — Starting service directly")
            pendingVpnModel = null
            presenter.startService(model)
        }
    }

    private fun showPermissionRationaleDialog(deniedPermissions: List<String>) {
        val message = buildString {
            append("این برنامه برای کارکرد صحیح به مجوزهای زیر نیاز دارد:\n\n")

            if (Manifest.permission.POST_NOTIFICATIONS in deniedPermissions) {
                append("📢 نمایش اعلان‌ها: برای نمایش وضعیت فیلترشکن\n\n")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in deniedPermissions) {
                    append("🔒 سرویس پیش‌زمینه: برای فعال نگه داشتن فیلترشکن در پس‌زمینه\n\n")
                }
            }

            append("لطفاً مجوزها را در تنظیمات برنامه فعال کنید.")
        }

        AlertDialog.Builder(this)
            .setTitle("نیاز به مجوزهای ضروری")
            .setMessage(message)
            .setPositiveButton("تنظیمات") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun loadDnsItems() {
        dnsList.clear()
        val grouped = LinkedHashMap<String, MutableList<com.hololo.app.dnschanger.model.DnsServer>>()
        for (server in DnsServerRepository.getAllServers()) {
            val gid = DnsServerRepository.groupIdFromServerId(server.id)
            grouped.getOrPut(gid) { mutableListOf() }.add(server)
        }
        for ((groupId, servers) in grouped) {
            if (servers.isEmpty()) continue
            val first = servers.first()
            DNSModel().apply {
                val fullName = first.name
                val si = fullName.lastIndexOf(' ')
                name = if (si > 0) fullName.substring(0, si) else fullName
                category = first.category
                serverGroupId = groupId
                val protocols = mutableListOf<String>()
                var primaryIp: String? = null
                var secondaryIp: String? = null
                for (s in servers) {
                    when (s.type) {
                        DnsType.DOH -> protocols.add("DoH")
                        DnsType.DOT -> protocols.add("DoT")
                        DnsType.PLAIN_UDP -> {
                            protocols.add("UDP")
                            if (primaryIp == null) primaryIp = s.primaryIp
                            else if (secondaryIp == null && s.primaryIp != primaryIp)
                                secondaryIp = s.primaryIp
                        }
                        else -> {}
                    }
                }
                firstDns = primaryIp ?: ""
                secondDns = secondaryIp ?: ""
                features = protocols
            }.let { dnsList.add(it) }
        }
    }

    private fun selectDnsModel(model: DNSModel) {
        selectedModel = model
        state = state.copy(
            dnsName = model.name,
            primaryDns = model.firstDns,
            secondaryDns = model.secondDns,
        )
    }

    override fun onDestroy() {
        eventsDisposable?.dispose()
        pingActive = false
        pingExecutor?.shutdownNow()
        pingExecutor = null
        presenter.onDestroy()
        super.onDestroy()
    }
}
