package io.github.p1neapplexpress.openflux.service

import android.annotation.SuppressLint
import android.content.Intent
import android.os.IBinder
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx

@SuppressLint("VpnServicePolicy")
class SocksVpnService : android.net.VpnService() {

    companion object {
        private const val TAG = "SocksVpnService"
    }

    private lateinit var vpn: VpnServiceController
    private lateinit var group: ParallelTransportGroup
    private lateinit var tun2socks: Tun2SocksLauncher
    private lateinit var notifications: VpnNotificationManager
    private val hotspot = HotspotProxyBridge()
    private var poolPoller: DocumentPoolPoller? = null

    private var lastIntent: Intent? = null

    private val binder = object : IUnifiedService.Stub() {
        override fun isVpnRunning(): Boolean = vpn.isRunning.get()
        override fun stopVpn() = stopEverything()
        override fun isFServiceRunning(): Boolean = group.isReady
        override fun nativeError(): String? = group.error
        override fun stopOpenFluxNative() {
            poolPoller?.stop()
            poolPoller = null
            group.stop()
        }

        override fun startOpenFluxNative(
            transport: String?,
            args: Array<String>,
            encryptionKey: String?,
            extraDocumentUrls: Array<String>,
            poolUrl: String?,
            sessionCookie: String?,
        ) {
            transport ?: return
            if (poolUrl.isNullOrBlank()) {
                group.start(args.toList(), encryptionKey, extraDocumentUrls.toList(), sessionCookie)
                return
            }
            val poller = DocumentPoolPoller(poolUrl) { docs -> group.reconcilePool(docs) }
            poolPoller = poller
            group.startPool(args.toList(), encryptionKey, emptyList(), sessionCookie)
            poller.start()
        }

        override fun startTun2Socks() {
            synchronized(this@SocksVpnService) {
                val fd = vpn.fd
                if (fd <= 0) {
                    Logx.e(TAG, "no tun fd; aborting tun2socks start")
                    return
                }
                val i = lastIntent ?: run {
                    Logx.e(TAG, "no lastIntent; aborting tun2socks start")
                    return
                }

                val ok = tun2socks.start(
                    fd = fd,
                    socksPort = group.socksPort,
                    username = i.getStringExtra(Constants.INTENT_USERNAME),
                    password = i.getStringExtra(Constants.INTENT_PASSWORD),
                    ipv6 = i.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false),
                    udpgw = i.getStringExtra(Constants.INTENT_UDP_GW),
                    mtu = i.getIntExtra(Constants.INTENT_MTU, AppSettings.DEFAULT_MTU),
                )

                if (ok) {
                    vpn.isRunning.set(true)
                    notifications.startSpeedUpdates(group.socksPort)
                    val settings = AppSettings(this@SocksVpnService)
                    if (settings.shareLanProxy) {
                        hotspot.start(
                            lanPort = settings.lanProxyPort,
                            targetSocksPort = group.socksPort,
                            authEnabled = settings.socks5AuthEnabled,
                            username = settings.socks5CustomUser,
                            password = settings.socks5CustomPass,
                        )
                    }
                    EventBus.dispatch(AppEvent.LogMessage("[I] tun2socks running"))
                    Logx.i(TAG, "tun2socks running")
                } else {
                    Logx.e(TAG, "tun2socks failed")
                    EventBus.dispatch(AppEvent.LogMessage("[E] tun2socks failed"))
                    stopEverything()
                }
            }
        }

        override fun getFd(): Int = vpn.fd
    }

    override fun onCreate() {
        super.onCreate()
        NativeBridge.ensureLoaded(applicationContext)
        vpn = VpnServiceController(this)
        group = ParallelTransportGroup(applicationContext) { message ->
            // Without any transport backend left, the VPN would silently blackhole all traffic.
            stopEverything()
            EventBus.dispatch(AppEvent.NativeProcessExited(message))
        }
        tun2socks = Tun2SocksLauncher(applicationContext, protect = { socket -> protect(socket) })
        notifications = VpnNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_STICKY
        lastIntent = intent
        notifications.startForeground()

        if (vpn.isConfigured()) {
            Logx.d(TAG, "VPN already configured, ignoring")
            return START_STICKY
        }

        vpn.configure(intent)
        EventBus.dispatch(AppEvent.LogMessage("[S] VPN configured"))
        Logx.i(TAG, "VPN configured")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onRevoke() {
        Logx.w(TAG, "onRevoke")
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        Logx.i(TAG, "stopEverything")
        notifications.stopSpeedUpdates()
        runCatching { poolPoller?.stop() }
        poolPoller = null
        runCatching { hotspot.stop() }
        runCatching { tun2socks.stop() }
        runCatching { group.stop() }
        runCatching { vpn.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
