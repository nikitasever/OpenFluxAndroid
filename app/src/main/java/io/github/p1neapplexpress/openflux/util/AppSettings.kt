package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager

class AppSettings(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "openflux_settings"

        const val DNS_MODE_CLOUDFLARE = 0
        const val DNS_MODE_GOOGLE = 1
        const val DNS_MODE_ADGUARD = 2
        const val DNS_MODE_QUAD9 = 3
        const val DNS_MODE_YANDEX = 4
        const val DNS_MODE_CUSTOM = 5

        const val DOH_PROVIDER_CLOUDFLARE = 0
        const val DOH_PROVIDER_GOOGLE = 1
        const val DOH_PROVIDER_ADGUARD = 2
        const val DOH_PROVIDER_QUAD9 = 3
        const val DOH_PROVIDER_YANDEX = 4
        const val DOH_PROVIDER_CUSTOM = 5

        const val IP_TYPE_AUTO = 0
        const val IP_TYPE_IPV4 = 1
        const val IP_TYPE_IPV6 = 2

        const val DEFAULT_MTU = 1400
        const val MIN_MTU = 1280
        const val MAX_MTU = 1500

        private const val KEY_DNS_MODE = "dns_mode"
        private const val KEY_CUSTOM_DNS_PRIMARY = "custom_dns_primary"
        private const val KEY_CUSTOM_DNS_SECONDARY = "custom_dns_secondary"
        private const val KEY_USE_SYSTEM_DNS = "use_system_dns"
        private const val KEY_DOT_ENABLED = "dot_enabled"
        private const val KEY_DOH_ENABLED = "doh_enabled"
        private const val KEY_DOH_PROVIDER = "doh_provider"
        private const val KEY_DOH_CUSTOM_URL = "doh_custom_url"
        private const val KEY_MTU = "mtu"
        private const val KEY_IPV6_PROXY = "ipv6_proxy"
        private const val KEY_IP_TYPE = "ip_type"
        private const val KEY_BYPASS_LAN = "bypass_lan"
        private const val KEY_KILL_SWITCH = "kill_switch"
        private const val KEY_SHARE_LAN_PROXY = "share_lan_proxy"
        private const val KEY_LAN_PROXY_PORT = "lan_proxy_port"
        private const val KEY_NOTIFY_SPEED = "show_notify_speed"
        private const val KEY_SOCKS5_AUTH_ENABLED = "socks5_auth_enabled"
        private const val KEY_SOCKS5_USER = "socks5_custom_user"
        private const val KEY_SOCKS5_PASS = "socks5_custom_pass"
        private const val KEY_SHOW_MEMORY_USAGE = "show_memory_usage"
        private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
        private const val KEY_AUTO_FAILOVER = "auto_failover"
        private const val KEY_AUTO_BOOT = "auto_boot"
        private const val KEY_AUTO_CLEAR_LOGS = "auto_clear_logs"
        private const val KEY_VERBOSE_LOG = "verbose_log"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var dnsMode: Int
        get() = prefs.getInt(KEY_DNS_MODE, DNS_MODE_CLOUDFLARE)
        set(value) = prefs.edit().putInt(KEY_DNS_MODE, value).apply()

    var customDnsPrimary: String
        get() = prefs.getString(KEY_CUSTOM_DNS_PRIMARY, "1.1.1.1") ?: "1.1.1.1"
        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_PRIMARY, value.trim()).apply()

    var customDnsSecondary: String
        get() = prefs.getString(KEY_CUSTOM_DNS_SECONDARY, "8.8.8.8") ?: "8.8.8.8"
        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_SECONDARY, value.trim()).apply()

    var useSystemDns: Boolean
        get() = prefs.getBoolean(KEY_USE_SYSTEM_DNS, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_SYSTEM_DNS, value).apply()

    /**
     * DNS-over-TLS: encrypted resolution is done by [io.github.p1neapplexpress.openflux.service.DotResolver]
     * against [primaryDns]/[secondaryDns] on port 853, feeding results into pdnsd's cache
     * (see DnsTcpRelay). Falls back to the plaintext-over-tunnel relay if the TLS handshake fails.
     */
    var dotEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DOT_ENABLED, value).apply()

    var dohEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DOH_ENABLED, value).apply()

    var dohProvider: Int
        get() = prefs.getInt(KEY_DOH_PROVIDER, DOH_PROVIDER_CLOUDFLARE)
        set(value) = prefs.edit().putInt(KEY_DOH_PROVIDER, value).apply()

    var dohCustomUrl: String
        get() = prefs.getString(KEY_DOH_CUSTOM_URL, "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query"
        set(value) = prefs.edit().putString(KEY_DOH_CUSTOM_URL, value.trim()).apply()

    val dohUrl: String
        get() = when (dohProvider) {
            DOH_PROVIDER_CLOUDFLARE -> "https://cloudflare-dns.com/dns-query"
            DOH_PROVIDER_GOOGLE -> "https://dns.google/dns-query"
            DOH_PROVIDER_ADGUARD -> "https://dns.adguard-dns.com/dns-query"
            DOH_PROVIDER_QUAD9 -> "https://dns.quad9.net/dns-query"
            DOH_PROVIDER_YANDEX -> "https://common.dot.dns.yandex.net/dns-query"
            DOH_PROVIDER_CUSTOM -> dohCustomUrl.ifBlank { "https://cloudflare-dns.com/dns-query" }
            else -> "https://cloudflare-dns.com/dns-query"
        }

    val primaryDns: String
        get() {
            if (useSystemDns) {
                val sys = getSystemDnsServer(0)
                if (sys != null) return sys
            }
            return when (dnsMode) {
                DNS_MODE_CLOUDFLARE -> "1.1.1.1"
                DNS_MODE_GOOGLE -> "8.8.8.8"
                DNS_MODE_ADGUARD -> "94.140.14.14"
                DNS_MODE_QUAD9 -> "9.9.9.9"
                DNS_MODE_YANDEX -> "77.88.8.8"
                DNS_MODE_CUSTOM -> customDnsPrimary.ifBlank { "1.1.1.1" }
                else -> "1.1.1.1"
            }
        }

    val secondaryDns: String
        get() {
            if (useSystemDns) {
                val sys = getSystemDnsServer(1)
                if (sys != null) return sys
            }
            return when (dnsMode) {
                DNS_MODE_CLOUDFLARE -> "1.0.0.1"
                DNS_MODE_GOOGLE -> "8.8.4.4"
                DNS_MODE_ADGUARD -> "94.140.15.15"
                DNS_MODE_QUAD9 -> "149.112.112.112"
                DNS_MODE_YANDEX -> "77.88.8.1"
                DNS_MODE_CUSTOM -> customDnsSecondary.ifBlank { "8.8.8.8" }
                else -> "8.8.8.8"
            }
        }

    var mtu: Int
        get() = prefs.getInt(KEY_MTU, DEFAULT_MTU).coerceIn(MIN_MTU, MAX_MTU)
        set(value) = prefs.edit().putInt(KEY_MTU, value.coerceIn(MIN_MTU, MAX_MTU)).apply()

    var ipv6Proxy: Boolean
        get() = prefs.getBoolean(KEY_IPV6_PROXY, false)
        set(value) = prefs.edit().putBoolean(KEY_IPV6_PROXY, value).apply()

    var ipType: Int
        get() = prefs.getInt(KEY_IP_TYPE, IP_TYPE_AUTO)
        set(value) = prefs.edit().putInt(KEY_IP_TYPE, value).apply()

    var bypassLan: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_LAN, true)
        set(value) = prefs.edit().putBoolean(KEY_BYPASS_LAN, value).apply()

    var killSwitch: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, false)
        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()

    var shareLanProxy: Boolean
        get() = prefs.getBoolean(KEY_SHARE_LAN_PROXY, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARE_LAN_PROXY, value).apply()

    var lanProxyPort: Int
        get() = prefs.getInt(KEY_LAN_PROXY_PORT, 10808)
        set(value) = prefs.edit().putInt(KEY_LAN_PROXY_PORT, value).apply()

    var showNotificationSpeed: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_SPEED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_SPEED, value).apply()

    var socks5AuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOCKS5_AUTH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SOCKS5_AUTH_ENABLED, value).apply()

    var socks5CustomUser: String
        get() {
            var user = prefs.getString(KEY_SOCKS5_USER, null)
            if (user.isNullOrBlank()) {
                user = "openflux-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
                prefs.edit().putString(KEY_SOCKS5_USER, user).apply()
            }
            return user
        }
        set(value) = prefs.edit().putString(KEY_SOCKS5_USER, value.trim()).apply()

    var socks5CustomPass: String
        get() = prefs.getString(KEY_SOCKS5_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SOCKS5_PASS, value.trim()).apply()

    var showMemoryUsage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MEMORY_USAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MEMORY_USAGE, value).apply()

    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, value).apply()

    var autoFailover: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FAILOVER, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FAILOVER, value).apply()

    var autoConnectOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_BOOT, value).apply()

    var autoClearLogs: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_LOGS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_LOGS, value).apply()

    var verboseLog: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_VERBOSE_LOG, value).apply()

    private fun getSystemDnsServer(index: Int): String? {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val activeNetwork = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(activeNetwork) ?: return null
            val servers = lp.dnsServers
            if (servers.size > index) servers[index].hostAddress else null
        }.getOrNull()
    }
}
