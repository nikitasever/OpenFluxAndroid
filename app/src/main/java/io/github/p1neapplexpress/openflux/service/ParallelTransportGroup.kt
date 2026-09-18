package io.github.p1neapplexpress.openflux.service

import android.content.Context
import io.github.p1neapplexpress.openflux.data.TunnelPayload
import io.github.p1neapplexpress.openflux.util.Logx
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs one [NativeProcessSupervisor] per document. With a single document (the common case,
 * no [start] `extraDocumentUrls`) this is exactly equivalent to running one directly - same
 * port, no extra hop, no new failure surface. With more than one, it also starts a
 * [ParallelSocksProxy] so tun2socks sees a single SOCKS5 port that load-balances new
 * connections across whichever documents are currently up.
 *
 * A single backend dying is normal wear for this kind of transport - that's the whole reason
 * to run more than one - and does not tear down the tunnel; only losing every backend does,
 * via [onUnexpectedExit], same contract [NativeProcessSupervisor] has on its own.
 */
class ParallelTransportGroup(
    private val context: Context,
    private val onUnexpectedExit: (String) -> Unit,
) {
    companion object {
        private const val TAG = "ParallelTransportGroup"
    }

    private var supervisors: List<NativeProcessSupervisor> = emptyList()
    private var proxy: ParallelSocksProxy? = null
    private val deadCount = AtomicInteger(0)

    /** SOCKS5 port tun2socks should target - the sole backend's port, or the aggregator's. */
    @Volatile
    var socksPort: Int = 0
        private set

    @Volatile
    var error: String? = null
        private set

    val isReady: Boolean get() = supervisors.any { it.isReady }

    fun start(payload: List<String>, encryptionKey: String?, extraDocumentUrls: List<String>) {
        deadCount.set(0)
        error = null

        val payloads = listOf(payload) + extraDocumentUrls.map { TunnelPayload.withUrl(payload, it) }
        val group = payloads.map { p ->
            NativeProcessSupervisor(context, ::onBackendExit).also { it.start(p, encryptionKey) }
        }
        supervisors = group

        socksPort = if (group.size == 1) {
            group[0].socksPort
        } else {
            Logx.i(TAG, "running ${group.size} documents in parallel")
            ParallelSocksProxy(group).start().also { proxy = it }.port
        }
    }

    private fun onBackendExit(message: String) {
        Logx.w(TAG, "backend exited: $message")
        // Only tear down the tunnel once every backend is gone - losing one of several is
        // exactly the instability this feature exists to absorb, not a fatal error.
        if (deadCount.incrementAndGet() >= supervisors.size) {
            error = message
            onUnexpectedExit(message)
        }
    }

    fun stop() {
        proxy?.stop()
        proxy = null
        supervisors.forEach { it.stop() }
        supervisors = emptyList()
    }
}
