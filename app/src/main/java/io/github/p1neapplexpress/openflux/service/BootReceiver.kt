package io.github.p1neapplexpress.openflux.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Mirrors TunnelsViewModel.startTunnel's bind -> startOpenFluxNative -> startTun2Socks
 * sequence, but from a BroadcastReceiver (goAsync so the process survives long enough
 * to finish the async AIDL calls; a plain onReceive would be killed almost immediately).
 * VpnService.prepare() consent from a prior run is assumed still granted — Android does
 * not re-prompt for a package that already has VPN permission.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BIND_TIMEOUT_MS = 5_000L
        private const val TRANSPORT_TIMEOUT_MS = 50_000L
        private const val TUN2SOCKS_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 250L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appSettings = AppSettings(context)
        if (!appSettings.autoConnectOnBoot) return

        val repo = TunnelRepository(context)
        val tunnel = repo.getSelected() ?: run {
            Logx.w(TAG, "auto-connect on boot: no selected tunnel")
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()

        val service = AtomicReference<IUnifiedService?>(null)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service.set(IUnifiedService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service.set(null)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Logx.i(TAG, "auto-connecting '${tunnel.name}' on boot")
                val vpnIntent = VpnIntentFactory.build(appContext, VPNConfig(name = tunnel.name))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(vpnIntent)
                } else {
                    appContext.startService(vpnIntent)
                }
                appContext.bindService(
                    Intent(appContext, SocksVpnService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )

                var waited = 0L
                while (service.get() == null && waited < BIND_TIMEOUT_MS) {
                    delay(POLL_MS); waited += POLL_MS
                }
                val bound = service.get() ?: run { Logx.e(TAG, "auto-connect: service did not bind"); return@launch }

                bound.startOpenFluxNative(
                    tunnel.transportType,
                    tunnel.transportConnPayload.toTypedArray(),
                    tunnel.encryptionKey,
                )

                if (!awaitReady(TRANSPORT_TIMEOUT_MS) { bound.isFServiceRunning() }) {
                    Logx.e(TAG, "auto-connect: transport did not start")
                    return@launch
                }
                bound.startTun2Socks()
                if (!awaitReady(TUN2SOCKS_TIMEOUT_MS) { bound.isVpnRunning() }) {
                    Logx.e(TAG, "auto-connect: tun2socks did not start")
                    return@launch
                }
                Logx.i(TAG, "auto-connect: tunnel running")
            } catch (e: Exception) {
                Logx.e(TAG, "auto-connect failed", e)
            } finally {
                runCatching { appContext.unbindService(connection) }
                pending.finish()
            }
        }
    }

    private suspend fun awaitReady(timeoutMs: Long, ready: () -> Boolean): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (runCatching(ready).getOrDefault(false)) return true
            delay(POLL_MS); waited += POLL_MS
        }
        return false
    }
}
