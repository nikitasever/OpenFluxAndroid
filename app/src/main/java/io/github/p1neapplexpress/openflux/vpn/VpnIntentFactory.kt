package io.github.p1neapplexpress.openflux.vpn

import android.content.Context
import android.content.Intent
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences

object VpnIntentFactory {
    /**
     * Per-tunnel remote config comes from [cfg]; device-wide network behavior
     * (MTU, DNS mode, kill switch, LAN bypass, IP stack) comes from [AppSettings],
     * and per-app routing comes from [SplitTunnelPreferences] - both apply
     * uniformly regardless of which tunnel is started or how [cfg] was built.
     */
    fun build(context: Context, cfg: VPNConfig): Intent {
        val settings = AppSettings(context)
        val split = SplitTunnelPreferences(context)
        val perApp = split.isEnabled
        val appBypass = split.mode == SplitTunnelPreferences.MODE_BYPASS
        val appList = if (perApp) {
            (if (appBypass) split.bypassApps else split.proxyApps).toTypedArray()
        } else {
            emptyArray()
        }
        return Intent(context, SocksVpnService::class.java).apply {
            putExtra(Constants.INTENT_NAME, cfg.name)
            putExtra(Constants.INTENT_SERVER, cfg.server)
            putExtra(Constants.INTENT_PORT, cfg.port)
            putExtra(Constants.INTENT_ROUTE, cfg.route)
            putExtra(Constants.INTENT_DNS, settings.primaryDns)
            putExtra(Constants.INTENT_SECONDARY_DNS, settings.secondaryDns)
            putExtra(Constants.INTENT_DNS_PORT, cfg.dnsPort)
            putExtra(Constants.INTENT_PER_APP, perApp && appList.isNotEmpty())
            putExtra(Constants.INTENT_APP_BYPASS, appBypass)
            putExtra(Constants.INTENT_APP_LIST, appList)
            putExtra(Constants.INTENT_IPV6_PROXY, cfg.ipv6Proxy)
            putExtra(Constants.INTENT_MTU, settings.mtu)
            putExtra(Constants.INTENT_BYPASS_LAN, settings.bypassLan)
            putExtra(Constants.INTENT_KILL_SWITCH, settings.killSwitch)
            putExtra(Constants.INTENT_IP_TYPE, settings.ipType)
            cfg.udpGw?.let { putExtra(Constants.INTENT_UDP_GW, it) }
            cfg.username?.let { putExtra(Constants.INTENT_USERNAME, it) }
            cfg.password?.let { putExtra(Constants.INTENT_PASSWORD, it) }
        }
    }
}
