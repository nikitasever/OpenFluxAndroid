package io.github.p1neapplexpress.openflux.event

import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a tunnel handed to us by an `openflux://import` deep link until whichever
 * fragment can act on it (TunnelsFragment) is actually observing.
 *
 * Deliberately not just another [AppEvent] on [EventBus]: that bus has replay=0,
 * so a fire-and-forget event dispatched at cold start - before the fragment's
 * collector is even running - would simply be lost. A conflated StateFlow keeps
 * the latest pending import around for whenever a collector shows up.
 */
object DeepLinkImport {
    private val _pending = MutableStateFlow<Tunnel?>(null)
    val pending: StateFlow<Tunnel?> = _pending.asStateFlow()

    fun offer(tunnel: Tunnel) {
        _pending.value = tunnel
    }

    /** Call once the pending tunnel has been handled, so it isn't re-imported later. */
    fun consume() {
        _pending.value = null
    }
}
