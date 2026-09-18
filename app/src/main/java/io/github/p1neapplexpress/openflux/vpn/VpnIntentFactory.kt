package io.github.p1neapplexpress.openflux.vpn

import android.content.Context
import android.content.Intent
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Constants

object VpnIntentFactory {
    /**
     * Per-tunnel remote config comes from [cfg]; device-wide network behavior
     * (MTU, DNS mode, kill switch, LAN bypass, IP stack) comes from [AppSettings]
     * so it applies uniformly regardless of which tunnel is started.
     */
    fun build(context: Context, cfg: VPNConfig): Intent {
        val settings = AppSettings(context)
        return Intent(context, SocksVpnService::class.java).apply {
            putExtra(Constants.INTENT_NAME, cfg.name)
            putExtra(Constants.INTENT_SERVER, cfg.server)
            putExtra(Constants.INTENT_PORT, cfg.port)
            putExtra(Constants.INTENT_ROUTE, cfg.route)
            putExtra(Constants.INTENT_DNS, settings.primaryDns)
            putExtra(Constants.INTENT_SECONDARY_DNS, settings.secondaryDns)
            putExtra(Constants.INTENT_DNS_PORT, cfg.dnsPort)
            putExtra(Constants.INTENT_PER_APP, cfg.perApp)
            putExtra(Constants.INTENT_APP_BYPASS, cfg.appBypass)
            putExtra(Constants.INTENT_APP_LIST, cfg.appList)
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
