package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Polls a pool.json file the VPS-side pool manager publishes on Yandex Disk (see
 * openflux-pool/pool_manager.py) and reports the current document list on change.
 *
 * The request goes through Yandex's own public Disk API (a metadata lookup, then a
 * redirect-following GET of the actual file) rather than hitting the VPS directly - the same
 * "blend in with normal Yandex traffic" reasoning as the tunnel's own transport.
 */
class DocumentPoolPoller(
    private val poolUrl: String,
    private val onUpdate: (List<String>) -> Unit,
) {
    companion object {
        private const val TAG = "DocumentPoolPoller"
        private const val POLL_INTERVAL_MS = 90_000L
        private const val REQUEST_TIMEOUT_MS = 15_000
        private const val API = "https://cloud-api.yandex.net/v1/disk/public/resources/download"
    }

    private val running = AtomicBoolean(false)
    private var lastDocuments: List<String>? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread(name = TAG, isDaemon = true) { loop() }
    }

    fun stop() {
        running.set(false)
    }

    private fun loop() {
        while (running.get()) {
            runCatching { fetchPool() }
                .onSuccess { docs ->
                    if (docs != null && docs != lastDocuments) {
                        lastDocuments = docs
                        onUpdate(docs)
                    }
                }
                .onFailure { Logx.w(TAG, "poll failed: ${it.message}") }
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun fetchPool(): List<String>? {
        val encodedKey = URLEncoder.encode(poolUrl, "UTF-8")
        val metaJson = get("$API?public_key=$encodedKey") ?: return null
        val href = JSONObject(metaJson).optString("href").ifEmpty { return null }
        val poolJson = get(href) ?: return null
        val documents = JSONObject(poolJson).optJSONArray("documents") ?: return null
        return List(documents.length()) { documents.getString(it) }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = REQUEST_TIMEOUT_MS
        conn.readTimeout = REQUEST_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
