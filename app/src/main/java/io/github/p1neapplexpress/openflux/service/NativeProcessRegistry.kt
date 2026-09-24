package io.github.p1neapplexpress.openflux.service

import java.util.concurrent.ConcurrentHashMap

/**
 * PIDs of transport processes this app session started deliberately and still considers
 * live. [StaleProcesses] must never kill these - only genuine leftovers from before this
 * session (e.g. after the app process itself was killed without a clean [NativeProcessSupervisor]
 * shutdown). Needed because [ParallelTransportGroup] legitimately runs more than one instance
 * of the same transport binary at once, which [StaleProcesses.kill]'s original single-instance
 * assumption would otherwise treat as leftovers of each other.
 */
object NativeProcessRegistry {
    private val activePids = ConcurrentHashMap.newKeySet<Long>()

    fun register(pid: Long) {
        activePids.add(pid)
    }

    fun unregister(pid: Long) {
        activePids.remove(pid)
    }

    fun isTracked(pid: Long): Boolean = activePids.contains(pid)
}
