package io.github.p1neapplexpress.openflux.service

import android.content.Context
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.DomainRulesPreferences
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.Loopback
import io.github.p1neapplexpress.openflux.util.ProcessRunner
import java.io.File
import java.net.Socket

/** [protect] should delegate to the owning VpnService's protect(Socket) - required for SplitDomainSocksProxy's bypass path to actually leave the tunnel. */
class Tun2SocksLauncher(
    private val context: Context,
    private val protect: (Socket) -> Boolean = { false },
) {

    companion object {
        private const val TAG = "Tun2SocksLauncher"
        private const val SEND_FD_ATTEMPTS = 10
        private const val SEND_FD_BASE_DELAY_MS = 500L

        private const val NETIF_IPADDR = "26.26.26.2"
        private const val NETIF_NETMASK = "255.255.255.0"
        private const val NETIF_IP6ADDR = "fdfe:dcba:9876::2"
        private const val LOG_LEVEL = "3"

        // VPN interface address (VpnServiceController). tun2socks re-injects DNS
        // queries towards it, so pdnsd must listen there rather than on loopback.
        private const val DNS_GW_IP = "26.26.26.1"
    }

    private var dnsRelay: DnsTcpRelay? = null
    private var domainProxy: SplitDomainSocksProxy? = null

    fun start(
        fd: Int,
        socksPort: Int,
        username: String?,
        password: String?,
        ipv6: Boolean,
        udpgw: String?,
        mtu: Int = AppSettings.DEFAULT_MTU,
    ): Boolean {
        if (fd <= 0) {
            Logx.e(TAG, "invalid tun fd: $fd")
            return false
        }

        val tunMtu = mtu.coerceIn(AppSettings.MIN_MTU, AppSettings.MAX_MTU)
        val settings = AppSettings(context)
        DnsResolutionCache.reset()

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val pdnsdBin = "$nativeDir/libpdnsd.so"
        val tun2socksBin = "$nativeDir/libtun2socks.so"

        val sockPath = File(context.applicationInfo.dataDir, "sock_path").apply {
            if (!exists()) createNewFile()
            setWritable(true, false)
            setReadable(true, false)
        }

        val upstreams = DnsTcpRelay.upstreamsFor(settings.primaryDns, settings.secondaryDns, settings.dotEnabled)
        val relay = DnsTcpRelay(socksPort, upstreams, useTls = settings.dotEnabled).start()
        dnsRelay = relay
        val dnsPort = Loopback.freeTcpPort()

        makePdnsdConf(listenPort = dnsPort, upstreamPort = relay.port)
        Logx.i(TAG, "starting pdnsd (DNS via tunnel${if (settings.dotEnabled) ", DoT" else ""})")
        ProcessRunner.execFireAndForget(
            command = listOf(pdnsdBin, "-c", "${context.filesDir}/pdnsd.conf"),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(500L)

        val domainPrefs = DomainRulesPreferences(context)
        val tun2socksTargetPort = if (domainPrefs.isEnabled && domainPrefs.domains.isNotEmpty() && username.isNullOrEmpty()) {
            val proxy = SplitDomainSocksProxy(
                nativeSocksPort = socksPort,
                ruleEngine = DomainRuleEngine(domainPrefs),
                protect = protect,
            ).start()
            domainProxy = proxy
            Logx.i(TAG, "domain routing active via 127.0.0.1:${proxy.port} -> 127.0.0.1:$socksPort")
            proxy.port
        } else {
            if (domainPrefs.isEnabled && domainPrefs.domains.isNotEmpty()) {
                Logx.w(TAG, "domain routing skipped: transport requires SOCKS5 auth, which SplitDomainSocksProxy doesn't support yet")
            }
            socksPort
        }

        Logx.i(TAG, "starting tun2socks (mtu=$tunMtu)")
        ProcessRunner.execFireAndForget(
            command = buildCommand(tun2socksBin, fd, tun2socksTargetPort, dnsPort, username, password, ipv6, udpgw, sockPath, tunMtu),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(500L)

        for (attempt in 1..SEND_FD_ATTEMPTS) {
            val r = NativeBridge.sendfd(fd, sockPath.absolutePath)
            if (r == 0) {
                Logx.i(TAG, "sendfd ok on attempt $attempt")
                return true
            }
            Logx.w(TAG, "sendfd attempt $attempt failed (ret=$r)")
            try {
                Thread.sleep(SEND_FD_BASE_DELAY_MS * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        Logx.e(TAG, "sendfd failed after $SEND_FD_ATTEMPTS attempts")
        return false
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        ProcessRunner.killPidFile("${context.filesDir}/tun2socks.pid")
        ProcessRunner.killPidFile("${context.filesDir}/pdnsd.pid")
        dnsRelay?.close()
        dnsRelay = null
        domainProxy?.stop()
        domainProxy = null
        DnsResolutionCache.reset()
        runCatching { File(context.applicationInfo.dataDir, "sock_path").delete() }
    }

    private fun buildCommand(
        bin: String,
        fd: Int,
        socksPort: Int,
        dnsPort: Int,
        user: String?,
        passwd: String?,
        ipv6: Boolean,
        udpgw: String?,
        sockPath: File,
        mtu: Int,
    ): List<String> = buildList {
        add(bin)
        add("--netif-ipaddr"); add(NETIF_IPADDR)
        add("--netif-netmask"); add(NETIF_NETMASK)
        add("--socks-server-addr"); add("127.0.0.1:$socksPort")
        add("--tunfd"); add(fd.toString())
        add("--tunmtu"); add(mtu.toString())
        add("--loglevel"); add(LOG_LEVEL)
        add("--pid"); add("${context.filesDir}/tun2socks.pid")
        add("--sock"); add(sockPath.absolutePath)
        if (!user.isNullOrEmpty()) {
            add("--username"); add(user)
            add("--password"); add(passwd ?: "")
        }
        if (ipv6) { add("--netif-ip6addr"); add(NETIF_IP6ADDR) }
        add("--dnsgw"); add("$DNS_GW_IP:$dnsPort")
        udpgw?.let { add("--udpgw-remote-server-addr"); add(it) }
    }

    private fun makePdnsdConf(listenPort: Int, upstreamPort: Int) {
        val conf = context.getString(io.github.p1neapplexpress.openflux.R.string.pdnsd_conf)
            .replace("{DIR}", context.filesDir.toString())
            .replace("{LISTEN_IP}", DNS_GW_IP)
            .replace("{LISTEN_PORT}", listenPort.toString())
            .replace("{UPSTREAM_PORT}", upstreamPort.toString())

        val f = File(context.filesDir, "pdnsd.conf")
        f.writeText(conf)

        val cache = File(context.filesDir, "pdnsd.cache")
        if (!cache.exists()) cache.createNewFile()
    }
}
