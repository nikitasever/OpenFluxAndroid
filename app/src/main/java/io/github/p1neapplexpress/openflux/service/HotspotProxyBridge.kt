package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local TCP bridge for LAN/hotspot proxy sharing. Listens on 0.0.0.0:[lanPort],
 * optionally requires RFC 1929 SOCKS5 username/password auth, and forwards
 * traffic to the app's own local SOCKS5 proxy on 127.0.0.1:[targetSocksPort]
 * (OpenFlux allocates that port dynamically per-run via NativeProcessSupervisor,
 * so the target must be passed in fresh on each start rather than assumed fixed).
 */
class HotspotProxyBridge {

    companion object {
        private const val TAG = "HotspotProxyBridge"
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    val running: Boolean get() = isRunning.get()

    fun start(
        lanPort: Int,
        targetSocksPort: Int,
        authEnabled: Boolean = false,
        username: String = "",
        password: String = "",
    ) {
        if (isRunning.getAndSet(true)) {
            stop()
            isRunning.set(true)
        }

        try {
            val sSocket = ServerSocket(lanPort, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket = sSocket
            val pool = Executors.newCachedThreadPool { r ->
                Thread(r, "HotspotBridgeWorker").apply { isDaemon = true }
            }
            executor = pool

            Logx.i(TAG, "hotspot bridge started on 0.0.0.0:$lanPort -> 127.0.0.1:$targetSocksPort (auth=$authEnabled)")

            pool.execute {
                while (isRunning.get() && !sSocket.isClosed) {
                    try {
                        val client = sSocket.accept()
                        pool.execute { handleClient(client, targetSocksPort, authEnabled, username, password) }
                    } catch (e: Exception) {
                        if (!sSocket.isClosed) Logx.w(TAG, "accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Logx.e(TAG, "failed to start hotspot bridge on port $lanPort", e)
            isRunning.set(false)
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Logx.i(TAG, "stopping hotspot bridge")
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    private fun handleClient(
        clientSocket: Socket,
        targetSocksPort: Int,
        authEnabled: Boolean,
        expectedUser: String,
        expectedPass: String,
    ) {
        try {
            clientSocket.soTimeout = 15000
            val cin = clientSocket.getInputStream()
            val cout = clientSocket.getOutputStream()

            val ver = cin.read()
            if (ver != 0x05) { clientSocket.close(); return }
            val nMethods = cin.read()
            if (nMethods <= 0) { clientSocket.close(); return }
            val methods = ByteArray(nMethods)
            cin.readFully(methods)

            val useAuth = authEnabled && expectedUser.isNotEmpty() && expectedPass.isNotEmpty()
            if (useAuth) {
                if (!methods.contains(0x02.toByte())) {
                    cout.write(byteArrayOf(0x05, 0xFF.toByte()))
                    cout.flush()
                    clientSocket.close()
                    return
                }
                cout.write(byteArrayOf(0x05, 0x02))
                cout.flush()

                val authVer = cin.read()
                if (authVer != 0x01) { clientSocket.close(); return }
                val uLen = cin.read()
                if (uLen <= 0) { clientSocket.close(); return }
                val uBytes = ByteArray(uLen)
                cin.readFully(uBytes)
                val user = String(uBytes, Charsets.UTF_8)

                val pLen = cin.read()
                if (pLen < 0) { clientSocket.close(); return }
                val pBytes = ByteArray(pLen)
                cin.readFully(pBytes)
                val pass = String(pBytes, Charsets.UTF_8)

                if (user != expectedUser || pass != expectedPass) {
                    Logx.w(TAG, "authentication failed from ${clientSocket.inetAddress.hostAddress}")
                    cout.write(byteArrayOf(0x01, 0x01))
                    cout.flush()
                    clientSocket.close()
                    return
                }
                cout.write(byteArrayOf(0x01, 0x00))
                cout.flush()
            } else {
                cout.write(byteArrayOf(0x05, 0x00))
                cout.flush()
            }

            val targetSocket = Socket(InetAddress.getByName("127.0.0.1"), targetSocksPort)
            targetSocket.soTimeout = 0
            clientSocket.soTimeout = 0

            val tin = targetSocket.getInputStream()
            val tout = targetSocket.getOutputStream()

            tout.write(byteArrayOf(0x05, 0x01, 0x00))
            tout.flush()
            val tVer = tin.read()
            val tMethod = tin.read()
            if (tVer != 0x05 || tMethod != 0x00) {
                targetSocket.close()
                clientSocket.close()
                return
            }

            val t1 = Thread({ pipe(cin, tout) }, "HotspotBridge-ClientToTarget")
            val t2 = Thread({ pipe(tin, cout) }, "HotspotBridge-TargetToClient")
            t1.isDaemon = true
            t2.isDaemon = true
            t1.start()
            t2.start()
        } catch (_: Exception) {
            runCatching { clientSocket.close() }
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16384)
        try {
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun InputStream.readFully(b: ByteArray) {
        var offset = 0
        while (offset < b.size) {
            val count = read(b, offset, b.size - offset)
            if (count < 0) throw EOFException("unexpected EOF")
            offset += count
        }
    }
}
