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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.hololo.app.dnschanger.DNSChangerApp
import com.hololo.app.dnschanger.R
import com.hololo.app.dnschanger.about.AboutActivity
import com.hololo.app.dnschanger.model.DNSModel
import com.hololo.app.dnschanger.model.DnsServer
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
import timber.log.Timber
import javax.inject.Inject

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
    private val pingHandler = Handler(Looper.getMainLooper())
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
        setContent { AppContent() }
        getServiceStatus()
    }

    @Composable
    private fun AppContent() {
        DnsChangerTheme {
            MainScreen(
                state = state,
                onStartStopClick = { startDNS() },
                onSelectServerClick = { openChooser() },
                onPrimaryDnsChange = { state = state.copy(primaryDns = it) },
                onSecondaryDnsChange = { state = state.copy(secondaryDns = it) },
            )
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
        pingHandler.removeCallbacksAndMessages(null)
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
        Thread {
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
                runOnUiThread { pingHandler.postDelayed(::performPing, 3000) }
            } catch (e: Exception) {
                Timber.e(e, "Ping error")
                runOnUiThread { pingHandler.postDelayed(::performPing, 3000) }
            }
        }.start()
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

    private fun subscribeToEvents() {
        presenter.events.subscribe { o ->
            if (o is StatsUpdateEvent) {
                runOnUiThread {
                    val pct = if (o.total > 0) (o.blocked * 100.0 / o.total).toFloat() else 0f
                    state = state.copy(totalQueries = o.total, blockedQueries = o.blocked, blockPercent = pct)
                }
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
        val grouped = LinkedHashMap<String, MutableList<DnsServer>>()
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

    private fun openChooser() {
        val dialog = BottomSheetDialog(this)
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setContent {
            DnsChangerTheme {
                val items = dnsList.map { DnsPickerItem(it, it.lastPing) }
                DnsPickerContent(
                    items = items,
                    onItemClick = { model ->
                        selectDnsModel(model)
                        dialog.dismiss()
                    },
                    onTestClick = { model, callback ->
                        Thread {
                            val p = testPing(model.firstDns)
                            model.lastPing = p
                            runOnUiThread { callback(p) }
                        }.start()
                    }
                )
            }
        }
        dialog.setContentView(composeView)
        dialog.show()
    }

    private fun selectDnsModel(model: DNSModel) {
        selectedModel = model
        state = state.copy(
            dnsName = model.name,
            primaryDns = model.firstDns,
            secondaryDns = model.secondDns,
        )
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.about -> { startActivity(Intent(this, AboutActivity::class.java)); true }
            R.id.settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            R.id.logs -> { startActivity(Intent(this, LogActivity::class.java)); true }
            R.id.apps -> { startActivity(Intent(this, AppFilterActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        pingHandler.removeCallbacksAndMessages(null)
        pingActive = false
        presenter.onDestroy()
        super.onDestroy()
    }
}
