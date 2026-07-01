package com.hololo.app.dnschanger.dnschanger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.hololo.app.dnschanger.DNSChangerApp
import com.hololo.app.dnschanger.R
import com.hololo.app.dnschanger.about.AboutActivity
import com.hololo.app.dnschanger.model.DNSModel
import com.hololo.app.dnschanger.model.DnsServerRepository
import com.hololo.app.dnschanger.model.DnsType
import com.hololo.app.dnschanger.ui.screens.DnsPickerContent
import com.hololo.app.dnschanger.ui.screens.DnsPickerItem
import com.hololo.app.dnschanger.ui.screens.MainScreen
import com.hololo.app.dnschanger.ui.screens.MainUiState
import com.hololo.app.dnschanger.ui.theme.DnsChangerTheme
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
    private val pingExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            title = { Text("DNS Changer") },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
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
                                label = { Text("Home") },
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                label = { Text("Logs") },
                                selected = selectedTab == 1,
                                onClick = {
                                    selectedTab = 1
                                    startActivity(Intent(this@MainActivity, LogActivity::class.java))
                                }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, null) },
                                label = { Text("Settings") },
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

    @Composable
    private fun DrawerContent(
        currentServerName: String,
        isDarkTheme: Boolean,
        onToggleTheme: () -> Unit,
        onNavigate: (DrawerItem) -> Unit,
    ) {
        ModalDrawerSheet {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "DNS Changer",
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = currentServerName,
                modifier = Modifier.padding(horizontal = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))

            val selectedItem = remember { mutableStateOf(DrawerItem.HOME) }
            val items = listOf(
                DrawerItem.HOME,
                DrawerItem.LOGS,
                DrawerItem.SETTINGS,
                DrawerItem.APPS,
                DrawerItem.ABOUT,
            )
            items.forEach { item ->
                NavigationDrawerItem(
                    icon = { Icon(item.icon, null) },
                    label = { Text(item.label) },
                    selected = selectedItem.value == item,
                    onClick = {
                        selectedItem.value = item
                        onNavigate(item)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    )
                )
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))

            // Dark mode toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleTheme() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (isDarkTheme) "Dark Mode" else "Light Mode",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    private enum class DrawerItem(
        val label: String,
        val icon: ImageVector,
    ) {
        HOME("Home", Icons.Default.Home),
        LOGS("DNS Logs", Icons.AutoMirrored.Filled.List),
        SETTINGS("Settings", Icons.Default.Settings),
        APPS("App Filter", Icons.Default.PhoneAndroid),
        ABOUT("About", Icons.AutoMirrored.Filled.HelpCenter),
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    private fun getServiceStatus() {
        if (presenter.isWorking) {
            serviceStarted()
            presenter.getServiceInfo()
        } else {
            serviceStopped()
        }
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
        pingExecutor.schedule({
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
        eventsDisposable = presenter.events.subscribe { o ->
            if (o is StatsUpdateEvent) {
                state = state.copy(
                    totalQueries = o.total,
                    blockedQueries = o.blocked,
                    blockPercent = if (o.total > 0) (o.blocked * 100.0 / o.total).toFloat() else 0f,
                )
            }
        }
    }

    private fun startDNS() {
        if (presenter.isWorking) {
            presenter.stopService()
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnLauncher.launch(intent)
            } else {
                presenter.startService(currentModel)
            }
        }
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DnsPickerDialog(
        dnsList: List<DNSModel>,
        onItemClick: (DNSModel) -> Unit,
        onTestClick: (DNSModel, (Long) -> Unit) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            val items = dnsList.map { DnsPickerItem(it, it.lastPing) }
            DnsPickerContent(
                items = items,
                onItemClick = onItemClick,
                onTestClick = onTestClick,
            )
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
        pingExecutor.shutdownNow()
        presenter.onDestroy()
        super.onDestroy()
    }
}
