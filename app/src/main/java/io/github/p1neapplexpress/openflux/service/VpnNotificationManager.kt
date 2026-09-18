package io.github.p1neapplexpress.openflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.ui.MainActivity
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.toSpeedString
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class VpnNotificationManager(private val service: Service) {

    companion object {
        const val CHANNEL_ID = "io.github.p1neapplexpress.libp1npplydtransport.so.vpn"
        const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val PING_INTERVAL_MS = 5000L
        private const val PING_TARGET_IP = "1.1.1.1"
        private const val PING_TARGET_PORT = 443
        private const val PING_TIMEOUT_MS = 3000
    }

    private val handler = Handler(Looper.getMainLooper())
    private val uid = Process.myUid()
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSampleAt = 0L
    private var socksPort = -1
    private var lastPingMs: Long? = null
    private val pingExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PingProbe").apply { isDaemon = true } }

    // speedUpdater refreshes the pinned notification with the current
    // upload/download speed once a second while the tunnel is running.
    // TrafficStats is used instead of tapping the packet path directly:
    // the data plane here is a native tun2socks process, opaque to this
    // Kotlin code, but per-UID counters keep working regardless of which
    // process is actually moving bytes through the TUN interface.
    private val speedUpdater = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = (now - lastSampleAt).coerceAtLeast(1)
            val rxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            val txBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            val rxPerSec = (rxBytes - lastRxBytes) * 1000 / elapsedMs
            val txPerSec = (txBytes - lastTxBytes) * 1000 / elapsedMs
            lastRxBytes = rxBytes
            lastTxBytes = txBytes
            lastSampleAt = now
            EventBus.dispatch(AppEvent.SpeedUpdate(rxPerSec, txPerSec))
            if (AppSettings(service).showNotificationSpeed) {
                val ping = lastPingMs?.let { "  •  ${it}ms" } ?: ""
                updateContent("↑ ${txPerSec.toSpeedString()}   ↓ ${rxPerSec.toSpeedString()}$ping")
            }
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    private val pingProbe = object : Runnable {
        override fun run() {
            val port = socksPort
            if (port > 0) {
                pingExecutor.execute {
                    val rtt = measurePing(port)
                    lastPingMs = rtt
                    EventBus.dispatch(AppEvent.PingUpdate(rtt))
                }
            }
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    fun startForeground() {
        createChannel()
        service.startForeground(NOTIFICATION_ID, buildNotification(service.getString(R.string.notify_msg)))
    }

    // startSpeedUpdates begins the live upload/download indicator; call once
    // the tunnel is actually passing traffic (tun2socks reported running).
    fun startSpeedUpdates(socksPort: Int = -1) {
        this.socksPort = socksPort
        lastRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        lastTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        lastSampleAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(speedUpdater)
        handler.removeCallbacks(pingProbe)
        handler.post(speedUpdater)
        if (socksPort > 0) handler.post(pingProbe)
    }

    // stopSpeedUpdates cancels the periodic refresh; call when the tunnel
    // stops so a stale speed reading isn't left on screen.
    fun stopSpeedUpdates() {
        handler.removeCallbacks(speedUpdater)
        handler.removeCallbacks(pingProbe)
        lastPingMs = null
        socksPort = -1
    }

    private fun measurePing(proxyPort: Int): Long? = runCatching {
        val started = SystemClock.elapsedRealtime()
        val target = InetSocketAddress(InetAddress.getByName(PING_TARGET_IP), PING_TARGET_PORT)
        Socks5.connect(proxyPort, target, PING_TIMEOUT_MS).close()
        SystemClock.elapsedRealtime() - started
    }.getOrNull()

    private fun updateContent(text: String) {
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.notify_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(ch)
    }
}
