package com.nextcraft.forge.network

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Plain-HTTP policy and localhost port-discovery utilities for NextCraft Forge.
 *
 * Self-hosted model servers (llama.cpp, LM Studio, Ollama, vLLM, LocalFormatGateway, etc.)
 * and local dev servers inside the Ubuntu chroot speak plain HTTP on loopback, LAN, or Tailscale.
 * Public internet endpoints must always use HTTPS.
 */
object LocalNetwork {
    private val localSuffixes = listOf(
        ".local",
        ".lan",
        ".home",
        ".home.arpa",
        ".internal",
        ".localdomain",
        ".ts.net",
    )

    val DEFAULT_PREVIEW_PORTS: List<Int> = listOf(
        3000,
        4173,
        4200,
        5000,
        5173,
        8000,
        8080,
        8081,
        8787,
        8888,
    )

    fun isPlainHttpAllowed(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.takeIf(String::isNotBlank) ?: return false
        return when (scheme) {
            "https" -> true
            "http" -> isLocalHost(host)
            else -> false
        }
    }

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost
            .trim()
            .trim('[', ']')
            .substringBefore('%') // Strip IPv6 scope IDs like %wlan0
            .lowercase()
            .trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "0.0.0.0" || localSuffixes.any(host::endsWith)) return true
        if (host.contains(':')) return isLocalIpv6(host)
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = octets.take(2).map(String::toInt)
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // Tailscale / carrier-grade NAT
        }
        return false
    }

    private fun isLocalIpv6(host: String): Boolean {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.startsWith("::ffff:")) {
            val mappedIpv4 = host.removePrefix("::ffff:")
            if (mappedIpv4.contains('.')) return isLocalHost(mappedIpv4)
        }
        val firstHextet = host.substringBefore(':')
        if (firstHextet.length == 4 && firstHextet.all { it in '0'..'9' || it in 'a'..'f' }) {
            val prefix2 = firstHextet.take(2)
            val prefix3 = firstHextet.take(3)
            if (prefix2 == "fc" || prefix2 == "fd") return true // Unique Local (fc00::/7)
            if (prefix3 == "fe8" || prefix3 == "fe9" || prefix3 == "fea" || prefix3 == "feb") return true // Link-Local (fe80::/10)
        }
        return false
    }

    /**
     * Probes whether a TCP server is listening on `127.0.0.1:[port]` (shared with the chroot via host netns).
     */
    fun isLocalPortOpen(port: Int, timeoutMs: Int = 150): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.coerceAtLeast(25))
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Scans candidate localhost ports and returns those currently accepting TCP connections.
     */
    fun scanOpenLocalPorts(
        ports: Iterable<Int> = DEFAULT_PREVIEW_PORTS,
        timeoutMs: Int = 120,
    ): List<Int> = ports.distinct().filter { port -> isLocalPortOpen(port, timeoutMs) }
}
