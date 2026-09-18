package io.github.p1neapplexpress.openflux.data

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `openflux://import?t=<config>` - the same Tunnel JSON the QR code carries,
 * just base64url-wrapped so it survives being pasted as a link in chat apps
 * (Telegram, etc.) instead of needing a camera. Round-trips with the QR flow:
 * same [Tunnel] shape, same tolerant [Json] config.
 */
object TunnelLink {
    private const val SCHEME = "openflux"
    private const val HOST = "import"
    private const val PARAM = "t"

    private val json = Json { ignoreUnknownKeys = true }
    private val base64Flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(tunnel: Tunnel): String {
        val payload = json.encodeToString(tunnel)
        val token = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), base64Flags)
        return "$SCHEME://$HOST?$PARAM=$token"
    }

    /** Returns null for anything that isn't one of our own, well-formed import links. */
    fun decode(uri: Uri?): Tunnel? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val token = uri.getQueryParameter(PARAM) ?: return null
        return runCatching {
            val bytes = Base64.decode(token, base64Flags)
            json.decodeFromString<Tunnel>(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }
}
