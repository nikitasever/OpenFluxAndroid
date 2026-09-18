package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-app split-tunnel selection. VpnServiceController already reads
 * INTENT_PER_APP/APP_BYPASS/APP_LIST (from the original OpenFlux PR #5) and
 * routes traffic per-app via VpnService.Builder.addAllowedApplication /
 * addDisallowedApplication - this is just the storage + UI to drive it,
 * wired into VPNConfig by VpnIntentFactory.
 */
class SplitTunnelPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "openflux_split_tunnel"
        private const val KEY_ENABLED = "split_tunnel_enabled"
        private const val KEY_MODE = "split_tunnel_mode"
        private const val KEY_BYPASS_APPS = "bypass_apps"
        private const val KEY_PROXY_APPS = "proxy_apps"
        private const val KEY_HIDE_SYSTEM_APPS = "hide_system_apps"
        private const val KEY_INITIALIZED = "presets_initialized"

        const val MODE_BYPASS = "bypass"
        const val MODE_PROXY = "proxy"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** MODE_BYPASS: selected apps skip the tunnel, everything else goes through it.
     *  MODE_PROXY: only selected apps go through the tunnel, everything else is direct. */
    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_BYPASS) ?: MODE_BYPASS
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    var hideSystemApps: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SYSTEM_APPS, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_SYSTEM_APPS, value).apply()

    var bypassApps: Set<String>
        get() = prefs.getStringSet(KEY_BYPASS_APPS, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_BYPASS_APPS, value).apply()

    var proxyApps: Set<String>
        get() = prefs.getStringSet(KEY_PROXY_APPS, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PROXY_APPS, value).apply()

    fun isInitialized(): Boolean = prefs.getBoolean(KEY_INITIALIZED, false)

    fun initializeDefaults(installedPackages: Set<String>) {
        val defaultBypass = RussianAppsPreset.PACKAGE_NAMES.filter { it in installedPackages }.toSet()
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putStringSet(KEY_BYPASS_APPS, defaultBypass)
            .apply()
    }

    fun resetToDefaults(installedPackages: Set<String>) {
        val defaultBypass = RussianAppsPreset.PACKAGE_NAMES.filter { it in installedPackages }.toSet()
        bypassApps = defaultBypass
    }
}
