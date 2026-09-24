package io.github.p1neapplexpress.openflux.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent

    /** The OpenFlux process died or never came up; the VPN has been stopped. */
    data class NativeProcessExited(val message: String) : AppEvent

    /** Per-second upload/download throughput while the tunnel is running, in bytes/sec. */
    data class SpeedUpdate(val rxBytesPerSec: Long, val txBytesPerSec: Long) : AppEvent

    /** Round-trip time through the tunnel to a public host, or null if the probe failed. */
    data class PingUpdate(val rttMs: Long?) : AppEvent
}
