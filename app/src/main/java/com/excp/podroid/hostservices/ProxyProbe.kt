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
        connection = URL("https://example.com/").openConnection(Proxy(Proxy.Type.HTTP, destination)) as HttpsURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = false
        // Keep normal TLS/hostname verification; a TCP accept alone proves no Internet access.
        if (connection.responseCode in 200..399) ProxyProbeResult.SUCCESS else ProxyProbeResult.HTTPS_FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (tcpConnected) ProxyProbeResult.HTTPS_FAILED else ProxyProbeResult.UNREACHABLE
    } finally { connection?.disconnect() }
}
