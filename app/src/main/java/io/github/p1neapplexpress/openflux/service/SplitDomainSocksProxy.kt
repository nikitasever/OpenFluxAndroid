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

/**
 * Sits between tun2socks and the native transport's SOCKS5 port, only when
 * domain rules are active. tun2socks connects here (instead of directly to
 * the transport) issuing an ordinary SOCKS5 CONNECT with a resolved IP; for
 * each connection this looks the IP up in [DnsResolutionCache] via
 * [DomainRuleEngine] and either:
 *  - BYPASS: dials out directly on a VpnService.protect()'d socket, so the
 *    connection never re-enters the TUN interface;
 *  - TUNNEL: forwards it as a SOCKS5 client to the native transport's port,
 *    same as tun2socks would have done on its own.
 *
 * Loopback-only; this is wiring internal to the app, never exposed to the
 * LAN (that is HotspotProxyBridge's job, a separate, intentionally
 * LAN-facing listener).
 */
class SplitDomainSocksProxy(
    private val nativeSocksPort: Int,
    private val ruleEngine: DomainRuleEngine,
    private val protect: (Socket) -> Boolean,
    private val connectTimeoutMs: Int = 10_000,
) {
    companion object {
        private const val TAG = "SplitDomainSocksProxy"
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
    }

    private var server: java.net.ServerSocket? = null
    private var pool: ExecutorService? = null

    val port: Int get() = server?.localPort ?: -1

    fun start(): SplitDomainSocksProxy {
        val s = java.net.ServerSocket(0, 50, Loopback.IPV4)
        server = s
        val p = Executors.newCachedThreadPool { r -> Thread(r, TAG).apply { isDaemon = true } }
        pool = p
        p.execute { acceptLoop(s, p) }
        Logx.i(TAG, "started on 127.0.0.1:${s.localPort} -> native SOCKS5 127.0.0.1:$nativeSocksPort")
        return this
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
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

            val decision = ruleEngine.decide(target.address.hostAddress ?: "")
            Logx.i(TAG, "$decision -> $target (domains=${DnsResolutionCache.domainsFor(target.address.hostAddress ?: "")})")
            val upstream = runCatching {
                when (decision) {
                    RouteDecision.BYPASS -> dialDirect(target)
                    RouteDecision.TUNNEL -> Socks5.connect(nativeSocksPort, target, connectTimeoutMs)
                }
            }.getOrElse {
                Logx.w(TAG, "$decision dial to $target failed: ${it.message}")
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

    private fun dialDirect(target: InetSocketAddress): Socket {
        val socket = Socket()
        if (!protect(socket)) throw IOException("VpnService.protect() failed for $target")
        socket.connect(target, connectTimeoutMs)
        return socket
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
