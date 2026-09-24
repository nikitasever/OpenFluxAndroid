package io.github.p1neapplexpress.openflux.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Routes
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class VpnServiceController(private val service: VpnService) {

    companion object {
        private const val TAG = "VpnServiceController"
        private const val MTU = AppSettings.DEFAULT_MTU
        private const val VPN_IPV4_ADDR = "26.26.26.1"
        private const val VPN_IPV4_PREFIX = 24
        private const val VPN_IPV6_ADDR = "fdfe:dcba:9876::1"
        private const val VPN_IPV6_PREFIX = 126
        private const val PRIMARY_DNS = "1.1.1.1"
        private const val SECONDARY_DNS = "8.8.8.8"
    }

    @Volatile private var iface: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    val isRunning = AtomicBoolean(false)

    val fd: Int get() = iface?.fd ?: -1

    fun isConfigured(): Boolean = iface != null

    fun configure(intent: Intent) {
        if (iface != null) {
            Logx.w(TAG, "configure() called twice; ignoring")
            return
        }

        val name = intent.getStringExtra(Constants.INTENT_NAME) ?: "OpenFlux"
        val route = intent.getStringExtra(Constants.INTENT_ROUTE)
        val perApp = intent.getBooleanExtra(Constants.INTENT_PER_APP, false)
        val appBypass = intent.getBooleanExtra(Constants.INTENT_APP_BYPASS, false)
        val appList = intent.getStringArrayExtra(Constants.INTENT_APP_LIST) ?: emptyArray()
        val ipv6 = intent.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false)
        val dns = intent.getStringExtra(Constants.INTENT_DNS)?.ifBlank { null } ?: PRIMARY_DNS
        val secDns = intent.getStringExtra(Constants.INTENT_SECONDARY_DNS)?.ifBlank { null } ?: SECONDARY_DNS
        val mtu = intent.getIntExtra(Constants.INTENT_MTU, MTU).coerceIn(AppSettings.MIN_MTU, AppSettings.MAX_MTU)
        val bypassLan = intent.getBooleanExtra(Constants.INTENT_BYPASS_LAN, true)
        val killSwitch = intent.getBooleanExtra(Constants.INTENT_KILL_SWITCH, false)
        val ipType = intent.getIntExtra(Constants.INTENT_IP_TYPE, AppSettings.IP_TYPE_AUTO)

        val builder = service.Builder()
            .setMtu(mtu)
            .setSession(name)
            .addDnsServer(dns)
            .addDnsServer(secDns)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(killSwitch)
        }

        when (ipType) {
            AppSettings.IP_TYPE_IPV4 -> {
                builder.addAddress(VPN_IPV4_ADDR, VPN_IPV4_PREFIX)
                Routes.addRoutes(service, builder, route, bypassLan)
            }
            AppSettings.IP_TYPE_IPV6 -> {
                // Local IPv4 is still required for the tun2socks/pdnsd DNS gateway,
                // but external traffic is routed over IPv6 only.
                builder.addAddress(VPN_IPV4_ADDR, VPN_IPV4_PREFIX)
                builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                    .addRoute("::", 0)
            }
            else -> { // Auto / dual-stack
                builder.addAddress(VPN_IPV4_ADDR, VPN_IPV4_PREFIX)
                Routes.addRoutes(service, builder, route, bypassLan)
                if (ipv6) {
                    builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                        .addRoute("::", 0)
                }
            }
        }

        listOf(dns, secDns).forEach { server ->
            runCatching {
                val addr = InetAddress.getByName(server)
                val prefix = if (addr is Inet6Address) 128 else 32
                builder.addRoute(addr, prefix)
            }
        }

        val effectivePerApp = perApp && appList.isNotEmpty()
        if (effectivePerApp && !appBypass) {
            configureAppRouting(builder, bypass = false, appList)
        } else {
            runCatching { builder.addDisallowedApplication(service.packageName) }
                .onFailure { Logx.w(TAG, "disallow self failed: ${it.message}") }
            if (effectivePerApp && appBypass) {
                configureAppRouting(builder, bypass = true, appList)
            }
        }

        iface = builder.establish()
        trackUnderlyingNetwork()
        if (iface == null) {
            Logx.e(TAG, "Failed to establish VPN interface")
            EventBus.dispatch(AppEvent.LogMessage("[E] VPN establish failed"))
        } else {
            Logx.d(TAG, "VPN interface established fd=${iface?.fd}")
        }
    }

    /**
     * Tells the system which real network carries this VPN, and keeps that current as the
     * phone moves between Wi-Fi and mobile data.
     *
     * Without it the transport process - which is excluded from the VPN via
     * addDisallowedApplication so it can reach Yandex at all - loses DNS the moment the tunnel
     * starts carrying traffic: every lookup comes back "no such host". The first connect
     * succeeds because it happens before routing takes hold, so the tunnel works for about
     * half a minute and then can never reconnect, which looks exactly like a dead transport.
     */
    private fun trackUnderlyingNetwork() {
        val cm = service.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { service.setUnderlyingNetworks(cm.activeNetwork?.let { arrayOf(it) }) }
            .onFailure { Logx.w(TAG, "setUnderlyingNetworks failed: ${it.message}") }

        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { service.setUnderlyingNetworks(arrayOf(network)) }
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Logx.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}") }
    }

    private fun configureAppRouting(
        builder: VpnService.Builder,
        bypass: Boolean,
        apps: Array<String>,
    ) {
        val self = service.packageName
        for (raw in apps) {
            val pkg = raw.trim()
            if (pkg.isEmpty() || pkg == self) continue
            runCatching {
                if (bypass) builder.addDisallowedApplication(pkg)
                else builder.addAllowedApplication(pkg)
            }.onFailure {
                if (it !is PackageManager.NameNotFoundException) {
                    Logx.w(TAG, "app routing failed for $pkg: ${it.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        networkCallback?.let { cb ->
            runCatching { service.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            networkCallback = null
        }
        iface?.let {
            runCatching { it.close() }
            iface = null
            Logx.d(TAG, "VPN interface closed")
        }
    }
}
