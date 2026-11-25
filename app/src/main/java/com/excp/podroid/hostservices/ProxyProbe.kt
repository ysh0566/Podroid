package com.excp.podroid.hostservices

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ProxyProbeResult { IDLE, TESTING, UNREACHABLE, HTTPS_FAILED, SUCCESS }

/** Tests the Android-side upstream explicitly; it does not claim VM connectivity. */
suspend fun probeProxy(config: RelayConfig): ProxyProbeResult = withContext(Dispatchers.IO) {
    if (!config.isValid()) return@withContext ProxyProbeResult.UNREACHABLE
    var tcpConnected = false
    var connection: HttpsURLConnection? = null
    try {
        val destination = InetSocketAddress(config.host, config.port)
        Socket().use { it.connect(destination, 2000) }
        tcpConnected = true
        connection = URL("https://example.com/").openConnection(Proxy(if (config.protocol == ProxyProtocol.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP, destination)) as HttpsURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        // Some upstream filters reject HEAD while the same HTTPS URL allows GET.
        // Inspect headers only and close below; no response body is retained.
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        // A verified TLS session plus an HTTP response proves connectivity even
        // when the destination's WAF rejects the request (for example HTTP 403).
        // CONNECT rejection and certificate/hostname failures still throw below.
        val status = connection.responseCode
        android.util.Log.i("PodroidProxyProbe", "HTTPS probe returned HTTP $status")
        if (status in 100..599) ProxyProbeResult.SUCCESS else ProxyProbeResult.HTTPS_FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (error: Exception) {
        // Keep connection-test failures actionable in exported diagnostics.
        android.util.Log.w("PodroidProxyProbe", "HTTPS probe failed", error)
        if (tcpConnected) ProxyProbeResult.HTTPS_FAILED else ProxyProbeResult.UNREACHABLE
    } finally { connection?.disconnect() }
}
