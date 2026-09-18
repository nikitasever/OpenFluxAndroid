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
)
