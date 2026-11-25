package com.excp.podroid.hostservices

import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class RelayPhase { OFF, WAITING_NETWORK, WAITING_PROXY, READY, FAILED }
data class RelayState(
    val phase: RelayPhase = RelayPhase.OFF,
    val connections: Int = 0,
    val bytes: Long = 0,
    val limit: Int = 0,
    val rejected: Long = 0,
)

/** Owned by PodroidService; never starts another foreground service or WakeLock. */
@Singleton
class NetworkRelay @Inject constructor() {
    private val lock = Any()
    private var generation = 0L
    private var relay: TcpRelay? = null
    private var udpRelay: UdpRelay? = null
    private val mutableState = MutableStateFlow(RelayState())
    val state = mutableState.asStateFlow()

    fun stop() = synchronized(lock) {
        generation++
        relay?.close()
        udpRelay?.close()
        udpRelay = null
        relay = null
        mutableState.value = RelayState()
    }

    suspend fun run(config: RelayConfig, backend: String) = withContext(Dispatchers.IO) {
        stop()
        if (!config.enabled) return@withContext
        val token = synchronized(lock) { generation }
        var binding: GuestNetwork? = null
        var destination: InetSocketAddress? = null
        var retiredBytes = 0L
        var retiredRejected = 0L
        var effectiveLimit = 0
        // Called under lock. Counters span listener rebinds within this VM session.
        fun retire() {
            relay?.let { it.close(); retiredBytes += it.bytes(); retiredRejected += it.rejectedConnections() }
            udpRelay?.let { it.close(); retiredBytes += it.bytes(); retiredRejected += it.rejectedConnections() }
            udpRelay = null
            relay = null
            binding = null
            destination = null
            effectiveLimit = 0
        }
        fun publish(phase: RelayPhase) {
            mutableState.value = RelayState(phase, (relay?.connections() ?: 0) + (udpRelay?.connections() ?: 0),
                retiredBytes + (relay?.bytes() ?: 0) + (udpRelay?.bytes() ?: 0), effectiveLimit,
                retiredRejected + (relay?.rejectedConnections() ?: 0) + (udpRelay?.rejectedConnections() ?: 0))
        }
        try {
            while (currentCoroutineContext().isActive) {
                var phase = RelayPhase.FAILED
                try {
                    require(config.isValid())
                    phase = RelayPhase.WAITING_NETWORK
                    val network = guestNetwork(backend)
                    if (network == null) synchronized(lock) {
                        if (token != generation) return@withContext
                        retire()
                        publish(phase)
                    } else {
                        phase = RelayPhase.WAITING_PROXY
                        val upstream = InetSocketAddress(config.host, config.port)
                        check(!upstream.isUnresolved)
                        synchronized(lock) {
                            if (token != generation) return@withContext
                            if (binding != network || destination != upstream || relay == null || relay!!.failure().isNotEmpty() || udpRelay?.failure()?.isNotEmpty() == true) {
                                retire()
                                phase = RelayPhase.FAILED
                                // Sample after retiring the old sockets. Keep this cap stable until rebind.
                                val udp = config.global && config.protocol == ProxyProtocol.SOCKS5
                                effectiveLimit = relayConnectionLimit(config.connectionLimit, readRelayResources(), udp)
                                check(effectiveLimit > 0) { "No relay resource budget" }
                                val budget = RelayBudget(effectiveLimit)
                                try {
                                    relay = TcpRelay(network.address, network.prefix, RELAY_PORT, upstream, budget)
                                    if (udp) udpRelay = UdpRelay(network.address, network.prefix, UDP_RELAY_PORT, upstream, budget)
                                } catch (e: Exception) {
                                    retire()
                                    throw e
                                }
                                binding = network
                                destination = upstream
                            }
                        }
                        // A failed health probe must not kill healthy established tunnels.
                        phase = RelayPhase.WAITING_PROXY
                        Socket().use { it.connect(upstream, 1500) }
                        synchronized(lock) {
                            if (token != generation) return@withContext
                            publish(RelayPhase.READY)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (token != generation) return@withContext
                        if (phase == RelayPhase.FAILED) retire()
                        publish(phase)
                    }
                }
                delay(3000)
            }
        } finally {
            // A cancelled, slow connect must not tear down a newer VM session.
            synchronized(lock) {
                if (token == generation) {
                    retire()
                    mutableState.value = RelayState()
                }
            }
        }
    }
}
