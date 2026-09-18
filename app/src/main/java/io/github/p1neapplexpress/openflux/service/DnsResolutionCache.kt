package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps resolved IPs back to the domain name(s) that produced them, built by
 * parsing the DNS responses DnsTcpRelay already carries through the tunnel.
 * This is the only place a domain name is ever visible in this pipeline:
 * tun2socks issues SOCKS5 CONNECT with a raw destination IP (ATYP=1), never
 * a hostname, because the OS/pdnsd resolution already happened by then.
 *
 * IP reuse across domains (shared CDN/load-balancer IPs) is expected and
 * handled by keeping a *set* of domains per IP rather than overwriting;
 * SplitDomainSocksProxy/DomainRuleEngine decide what to do when that set
 * has conflicting rule matches.
 */
object DnsResolutionCache {

    private const val TAG = "DnsResolutionCache"
    private const val MIN_TTL_SECONDS = 30L
    private const val MAX_TTL_SECONDS = 3600L

    private data class Entry(val domain: String, val expiresAtMs: Long)

    // One IP can legitimately serve several domains (shared hosting/CDNs).
    private val ipToEntries = ConcurrentHashMap<String, MutableSet<Entry>>()

    /** Clears all learned mappings; call when a VPN session starts/stops so stale IP/domain pairs from a previous tunnel don't leak into a new one. */
    fun reset() {
        ipToEntries.clear()
    }

    /** Returns the domain(s) currently believed to resolve to [ip], expired entries excluded. */
    fun domainsFor(ip: String): Set<String> {
        val entries = ipToEntries[ip] ?: return emptySet()
        val now = System.currentTimeMillis()
        val live = entries.filter { it.expiresAtMs > now }
        if (live.size != entries.size) {
            // Opportunistic cleanup; safe under concurrent access since we
            // only ever replace with a subset, never touch other IPs.
            if (live.isEmpty()) ipToEntries.remove(ip, entries) else ipToEntries[ip] = live.toMutableSet()
        }
        return live.map { it.domain }.toSet()
    }

    /**
     * Parses a raw DNS message (the payload after the 2-byte TCP length
     * prefix DnsTcpRelay/pdnsd already use) and records any A/AAAA answers.
     * Never throws: malformed input is logged and ignored, since this path
     * sits on the hot data path for every DNS response through the tunnel.
     */
    fun recordFromDnsMessage(message: ByteArray) {
        runCatching { parseAndRecord(message) }
            .onFailure { Logx.w(TAG, "failed to parse DNS response: ${it.message}") }
    }

    private fun parseAndRecord(msg: ByteArray) {
        if (msg.size < 12) return
        val qdCount = u16(msg, 4)
        val anCount = u16(msg, 6)
        if (anCount == 0) return

        var offset = 12
        // Question section: read the QNAME of the first question (all
        // questions in a DNS response share the same base owner name for
        // our purposes) and skip QTYPE/QCLASS for each entry present.
        var qname: String? = null
        for (i in 0 until qdCount) {
            val (name, next) = readName(msg, offset)
            if (i == 0) qname = name
            offset = next + 4 // QTYPE(2) + QCLASS(2)
        }
        if (qname.isNullOrBlank()) return
        val domain = qname.trimEnd('.').lowercase()
        if (domain.isEmpty()) return

        repeat(anCount) {
            if (offset >= msg.size) return
            val (_, afterName) = readName(msg, offset)
            offset = afterName
            if (offset + 10 > msg.size) return
            val type = u16(msg, offset)
            val ttlSeconds = u32(msg, offset + 4).coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
            val rdLength = u16(msg, offset + 8)
            val rdataStart = offset + 10
            if (rdataStart + rdLength > msg.size) return

            if (type == TYPE_A && rdLength == 4) {
                val ip = InetAddress.getByAddress(msg.copyOfRange(rdataStart, rdataStart + 4)).hostAddress
                remember(ip, domain, ttlSeconds)
            } else if (type == TYPE_AAAA && rdLength == 16) {
                val ip = InetAddress.getByAddress(msg.copyOfRange(rdataStart, rdataStart + 16)).hostAddress
                remember(ip, domain, ttlSeconds)
            }
            offset = rdataStart + rdLength
        }
    }

    private fun remember(ip: String?, domain: String, ttlSeconds: Long) {
        if (ip.isNullOrEmpty()) return
        val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L
        ipToEntries.compute(ip) { _, existing ->
            val set = existing ?: mutableSetOf()
            set.removeAll { it.domain == domain }
            set.add(Entry(domain, expiresAt))
            set
        }
    }

    /** Reads a (possibly compressed) DNS name starting at [offset]; returns the name and the offset just past it in the original message (not following a compression pointer). */
    private fun readName(msg: ByteArray, offset: Int): Pair<String, Int> {
        val labels = StringBuilder()
        var pos = offset
        var endOfName = -1
        var jumps = 0
        while (pos < msg.size) {
            val len = msg[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                if (endOfName == -1) endOfName = pos
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= msg.size) break
                if (endOfName == -1) endOfName = pos + 2
                val pointer = ((len and 0x3F) shl 8) or (msg[pos + 1].toInt() and 0xFF)
                jumps++
                if (jumps > 20 || pointer >= msg.size) break
                pos = pointer
                continue
            }
            pos += 1
            if (pos + len > msg.size) break
            if (labels.isNotEmpty()) labels.append('.')
            labels.append(String(msg, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return labels.toString() to (if (endOfName == -1) pos else endOfName)
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
}
