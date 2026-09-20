package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Loopback
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sits between tun2socks and several parallel OpenFlux transport processes, each connected
 * to its own document (see [ParallelTransportGroup]). Every new SOCKS5 CONNECT from tun2socks
 * is handed to one backend - round-robin over whichever are currently ready - and if that
 * backend fails to dial, the same connection is retried on the next one before giving up.
 *
 * This does NOT split a single connection's bytes across documents - that would need protocol
 * support inside the transport binary itself, which is closed-source here. It only spreads
 * *independent* connections across documents, so one document's channel reconnecting no
 * longer stalls traffic that happens to be assigned to another.
 *
 * Loopback-only, same as [SplitDomainSocksProxy] - internal wiring, never LAN-facing.
 */
class ParallelSocksProxy(
    @Volatile private var backends: List<NativeProcessSupervisor>,
    // Kept short deliberately: a live incident traced "everything times out even
    // though the pool has a healthy backend" to this timeout being too generous.
    // With N backends, a single dial can cost up to N * connectTimeoutMs before
    // dialAnyBackend() gives up entirely - at the old 10s default, two backends
    // meant up to 20s for one CONNECT, well past Android's own ~12s DNS
    // resolution timeout (confirmed via NetdEventListenerService logs showing
    // 12000-20000ms TIMEOUTs). A healthy backend answers a local SOCKS5 CONNECT
    // in well under a second even through the transport's WS relay, so a stuck
    // one is worth abandoning quickly rather than treating it like normal
    // network latency.
    private val connectTimeoutMs: Int = 4_000,
) {
    companion object {
        private const val TAG = "ParallelSocksProxy"
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
    }

    private var server: java.net.ServerSocket? = null
    private var pool: ExecutorService? = null
    private val nextIndex = AtomicInteger(0)

    val port: Int get() = server?.localPort ?: -1

    fun start(): ParallelSocksProxy {
        val s = java.net.ServerSocket(0, 50, Loopback.IPV4)
        server = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, TAG).apply { isDaemon = true } }
        pool = p
        p.execute { acceptLoop(s, p) }
        Logx.i(TAG, "started on 127.0.0.1:${s.localPort} -> ${backends.size} parallel documents")
        return this
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    /** Swaps the backend list live - existing in-flight connections are unaffected, only future dials use the new set. */
    fun updateBackends(newBackends: List<NativeProcessSupervisor>) {
        backends = newBackends
    }

    private fun acceptLoop(server: java.net.ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) Logx.w(TAG, "accept failed: ${e.message}")
                return
            }
            pool.execute { runCatching { serve(client) }.onFailure { runCatching { client.close() } } }
        }
    }

    private fun serve(client: Socket) {
        client.use {
            client.soTimeout = connectTimeoutMs
            val cin = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            // Method negotiation: accept no-auth only (tun2socks is a local, trusted client).
            val ver = cin.read()
            if (ver != 0x05) return
            val nMethods = cin.read()
            if (nMethods <= 0) return
            val methods = ByteArray(nMethods)
            cin.readFully(methods)
            cout.write(byteArrayOf(0x05, 0x00))
            cout.flush()

            val target = readConnectRequest(cin) ?: run {
                writeReply(cout, success = false)
                return
            }

            val upstream = dialAnyBackend(target) ?: run {
                writeReply(cout, success = false)
                return
            }

            upstream.use {
                writeReply(cout, success = true)
                client.soTimeout = 0
                upstream.soTimeout = 0
                val pool = this.pool ?: return
                val toUpstream = pool.submit { pump(client.getInputStream(), upstream.getOutputStream()) }
                pump(upstream.getInputStream(), cout)
                runCatching { toUpstream.get() }
            }
        }
    }

    /** Round-robins over currently-ready backends, trying each at most once for this connection. */
    private fun dialAnyBackend(target: InetSocketAddress): Socket? {
        val ready = backends.filter { it.isReady }
        if (ready.isEmpty()) {
            Logx.w(TAG, "no ready backend for $target")
            return null
        }
        val start = nextIndex.getAndIncrement()
        for (offset in ready.indices) {
            val backend = ready[(start + offset) % ready.size]
            val socket = runCatching { Socks5.connect(backend.socksPort, target, connectTimeoutMs) }
                .onFailure { Logx.w(TAG, "backend on ${backend.socksPort} failed for $target: ${it.message}") }
                .getOrNull()
            if (socket != null) return socket
        }
        return null
    }

    /** Reads a SOCKS5 CONNECT request (VER CMD RSV ATYP DST.ADDR DST.PORT); returns the resolved target or null on anything but CONNECT/supported ATYP. */
    private fun readConnectRequest(cin: DataInputStream): InetSocketAddress? {
        val ver = cin.read()
        val cmd = cin.read()
        cin.read() // RSV
        val atyp = cin.read()
        if (ver != 0x05 || cmd != 0x01) return null

        val address: InetAddress = when (atyp) {
            ATYP_IPV4 -> {
                val b = ByteArray(4); cin.readFully(b); InetAddress.getByAddress(b)
            }
            ATYP_IPV6 -> {
                val b = ByteArray(16); cin.readFully(b); InetAddress.getByAddress(b)
            }
            ATYP_DOMAIN -> {
                val len = cin.read()
                if (len <= 0) return null
                val b = ByteArray(len); cin.readFully(b)
                InetAddress.getByName(String(b, Charsets.US_ASCII))
            }
            else -> return null
        }
        val port = cin.readUnsignedShort()
        return InetSocketAddress(address, port)
    }

    private fun writeReply(out: OutputStream, success: Boolean) {
        val reply = byteArrayOf(
            0x05, if (success) 0x00 else 0x01, 0x00, 0x01,
            0, 0, 0, 0, // bind addr 0.0.0.0 - unused by tun2socks
            0, 0, // bind port 0
        )
        runCatching { out.write(reply); out.flush() }
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16384)
        try {
            var n: Int
            while (from.read(buf).also { n = it } != -1) {
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { to.close() }
        }
    }
}
