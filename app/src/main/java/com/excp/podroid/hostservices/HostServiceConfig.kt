package com.excp.podroid.hostservices

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

// Separate from the standalone helper so an existing installation keeps working.
const val RELAY_PORT = 18888
const val MICROPHONE_PORT = 18889
const val DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1"

data class RelayConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8686,
    val noProxy: String = DEFAULT_NO_PROXY,
    val connectionLimit: Int = TcpRelay.CONNECTION_LIMIT,
) {
    fun normalizedNoProxy(): String = noProxy.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
    fun isValid(): Boolean = connectionLimit in RELAY_CONNECTION_OPTIONS && port in 1..65535 && host.length in 1..253 &&
        host.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")) &&
        port !in listOf(RELAY_PORT, MICROPHONE_PORT) && noProxy.length <= 512 &&
        normalizedNoProxy().matches(Regex("[A-Za-z0-9.*:/,\\-]*"))
}

data class GuestNetwork(val address: InetAddress, val prefix: Int)

/** Diagnostics must not export the per-boot microphone credential. */
fun redactHostServiceSecrets(text: String): String =
    text.replace(Regex("podroid\\.mic_token=[^\\s,\\]]+"), "podroid.mic_token=<redacted>")

fun guestNetwork(backend: String): GuestNetwork? = when (backend) {
    "avf" -> NetworkInterface.getByName("avf_tap_fixed")?.takeIf { it.isUp }
        ?.interfaceAddresses?.firstOrNull { it.address is Inet4Address }
        ?.let { GuestNetwork(it.address, it.networkPrefixLength.toInt()) }
    // SLIRP exposes Android loopback to the guest through 10.0.2.2.
    "qemu", "uml" -> GuestNetwork(InetAddress.getByName("127.0.0.1"), 32)
    else -> null
}

fun hostServiceCmdline(extra: String, relayEnabled: Boolean, microphoneToken: String = "", noProxy: String = DEFAULT_NO_PROXY): String {
    // These flags belong to the app. Remove stale user-supplied duplicates so
    // disabling the switch cannot leave a guest pointed at an absent relay.
    val cleaned = extra.split(Regex("\\s+")).filter {
        it.isNotEmpty() && !it.startsWith("podroid.proxy_port=") && !it.startsWith("podroid.no_proxy=") &&
            !it.startsWith("podroid.mic_port=") && !it.startsWith("podroid.mic_token=")
    }
    val proxyConfig = RelayConfig(noProxy = noProxy)
    require(proxyConfig.isValid())
    return (cleaned + "podroid.proxy_port=${if (relayEnabled) RELAY_PORT else 0}" +
        "podroid.mic_port=$MICROPHONE_PORT" +
        (if (relayEnabled) listOf("podroid.no_proxy=${proxyConfig.normalizedNoProxy().ifEmpty { "-" }}") else emptyList()) +
        listOfNotNull(microphoneToken.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { "podroid.mic_token=$it" })).joinToString(" ")
}
