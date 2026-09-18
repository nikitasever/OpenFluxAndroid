package io.github.p1neapplexpress.openflux.data

import kotlinx.serialization.Serializable

@Serializable
data class Tunnel(
    val id: Long,
    val name: String,
    val transportType: String,
    val transportConnPayload: List<String>,
    /** Shared secret for OpenFlux's AES-256-GCM transport encryption; null means unencrypted. */
    val encryptionKey: String? = null,
    /**
     * Extra document URLs to run alongside the main one in [transportConnPayload], each as its
     * own independent transport process (see ParallelTransportGroup). Empty by default, so old
     * stored/shared tunnels decode unchanged.
     */
    val extraDocumentUrls: List<String> = emptyList(),
    /**
     * A pool.json URL (published on Yandex Disk by openflux-pool/pool_manager.py) that replaces
     * [extraDocumentUrls] with a server-managed, auto-rotating document list: the app polls it
     * periodically and swaps documents in/out via ParallelTransportGroup.reconcilePool as the
     * VPS-side pool manager retires unhealthy ones and provisions replacements. Null/blank means
     * this tunnel doesn't use pool mode.
     */
    val poolUrl: String? = null,
)
