package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.SystemClock
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

        // Restarting is a heavy, externally-visible action: the new process joins the shared
        // document as a fresh participant, which evicts the exit node's own session, which
        // reconnects and evicts ours - a mutual-eviction loop that keeps a working document
        // permanently broken. Observed live at a 60s cooldown. The transport now recovers on
        // its own (it has read/write deadlines on its WebSocket), so this exists only for a
        // process genuinely wedged beyond that, and must stay rare enough not to churn the
        // document.
        private const val RESTART_COOLDOWN_MS = 300_000L
    }

    private var supervisors: List<NativeProcessSupervisor> = emptyList()
    private var proxy: ParallelSocksProxy? = null
    private val deadCount = AtomicInteger(0)

    // Pool mode (server-managed document list, see DocumentPoolPoller) is mutually exclusive
    // with the static extraDocumentUrls mode above and kept fully separate: it always runs
    // behind a ParallelSocksProxy - even for a single document - so socksPort never has to
    // change as documents get swapped in/out later by reconcilePool().
    private val poolSupervisors = java.util.concurrent.ConcurrentHashMap<String, NativeProcessSupervisor>()
    private val poolRestartAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var poolProxy: ParallelSocksProxy? = null
    private var poolBasePayload: List<String>? = null
    private var poolEncryptionKey: String? = null
    private var poolSessionCookie: String? = null

    /** SOCKS5 port tun2socks should target - the sole backend's port, or the aggregator's. */
    @Volatile
    var socksPort: Int = 0
        private set

    @Volatile
    var error: String? = null
        private set

    val isReady: Boolean get() = supervisors.any { it.isReady } || poolSupervisors.values.any { it.isReady }

    fun start(
        payload: List<String>,
        encryptionKey: String?,
        extraDocumentUrls: List<String>,
        sessionCookie: String? = null,
    ) {
        deadCount.set(0)
        error = null

        val payloads = listOf(payload) + extraDocumentUrls.map { TunnelPayload.withUrl(payload, it) }
        val group = payloads.map { p ->
            NativeProcessSupervisor(context, ::onBackendExit).also { it.start(p, encryptionKey, sessionCookie) }
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

    /** Starts pool mode with an initial document set from [DocumentPoolPoller]. */
    fun startPool(
        basePayload: List<String>,
        encryptionKey: String?,
        initialUrls: List<String>,
        sessionCookie: String? = null,
    ) {
        error = null
        poolBasePayload = basePayload
        poolEncryptionKey = encryptionKey
        poolSessionCookie = sessionCookie
        initialUrls.forEach(::addPoolDocument)
        socksPort = ParallelSocksProxy(
            poolSupervisors.values.toList(),
            onBackendUnhealthy = ::onPoolBackendUnhealthy,
        ).start().also { poolProxy = it }.port
        Logx.i(TAG, "pool mode: running ${poolSupervisors.size} document(s)")
    }

    /** Adds/removes documents to match [urls] exactly; documents that stay are left untouched. */
    fun reconcilePool(urls: List<String>) {
        val wanted = urls.toSet()
        val added = wanted - poolSupervisors.keys
        val removed = poolSupervisors.keys - wanted
        if (added.isEmpty() && removed.isEmpty()) return

        added.forEach(::addPoolDocument)
        removed.forEach(::removePoolDocument)
        poolProxy?.updateBackends(poolSupervisors.values.toList())
        Logx.i(TAG, "pool reconciled: +${added.size} -${removed.size}, now ${poolSupervisors.size} document(s)")
    }

    private fun addPoolDocument(url: String) {
        val basePayload = poolBasePayload ?: return
        val supervisor = NativeProcessSupervisor(context, ::onPoolBackendExit)
        poolSupervisors[url] = supervisor
        supervisor.start(TunnelPayload.withUrl(basePayload, url), poolEncryptionKey, poolSessionCookie)
    }

    private fun removePoolDocument(url: String) {
        poolSupervisors.remove(url)?.stop()
        poolRestartAt.remove(url)
    }

    /**
     * Restarts the one document whose dials keep failing, reported by [ParallelSocksProxy].
     *
     * Nothing else in the group can notice this state: the process is still alive - only its
     * collaborative-editing session died - so [onPoolBackendExit] never fires, the backend stays
     * [NativeProcessSupervisor.isReady] forever, and it keeps being handed connections it can
     * never complete. Restarting rejoins the same document; if the document itself is gone the
     * new process fails to start and takes the normal exit path instead, and the server-side
     * pool manager swaps the URL out from under us soon after.
     */
    private fun onPoolBackendUnhealthy(supervisor: NativeProcessSupervisor) {
        val basePayload = poolBasePayload ?: return
        val url = poolSupervisors.entries.firstOrNull { it.value === supervisor }?.key ?: return

        val now = SystemClock.elapsedRealtime()
        if (poolRestartAt[url]?.let { now - it < RESTART_COOLDOWN_MS } == true) return

        val replacement = NativeProcessSupervisor(context, ::onPoolBackendExit)
        // Compare-and-set: a concurrent reconcilePool() may have just dropped this document,
        // and resurrecting it here would put back a URL the server no longer publishes.
        if (!poolSupervisors.replace(url, supervisor, replacement)) return
        poolRestartAt[url] = now

        Logx.w(TAG, "restarting unhealthy pool backend for $url")
        supervisor.stop()
        replacement.start(TunnelPayload.withUrl(basePayload, url), poolEncryptionKey, poolSessionCookie)
        poolProxy?.updateBackends(poolSupervisors.values.toList())
    }

    private fun onPoolBackendExit(message: String) {
        Logx.w(TAG, "pool backend exited: $message")
        // Membership changes over time (documents get swapped by the pool poller), so - unlike
        // the static path's deadCount - liveness is checked directly against who's left, rather
        // than counted against a fixed original size.
        if (poolSupervisors.values.none { it.isReady }) {
            error = message
            onUnexpectedExit(message)
        }
    }

    fun stop() {
        proxy?.stop()
        proxy = null
        supervisors.forEach { it.stop() }
        supervisors = emptyList()

        poolProxy?.stop()
        poolProxy = null
        poolSupervisors.values.forEach { it.stop() }
        poolRestartAt.clear()
        poolSupervisors.clear()
    }
}
