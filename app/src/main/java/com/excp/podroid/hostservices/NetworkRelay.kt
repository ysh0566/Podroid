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
    val limit: Int = TcpRelay.CONNECTION_LIMIT,
    val rejected: Long = 0,
)

/** Owned by PodroidService; never starts another foreground service or WakeLock. */
@Singleton
class NetworkRelay @Inject constructor() {
    private val lock = Any()
    private var generation = 0L
    private var relay: TcpRelay? = null
    private val mutableState = MutableStateFlow(RelayState())
    val state = mutableState.asStateFlow()

    fun stop() = synchronized(lock) {
        generation++
        relay?.close()
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
        // Called under lock. Counters span listener rebinds within this VM session.
        fun retire() {
            relay?.let { it.close(); retiredBytes += it.bytes(); retiredRejected += it.rejectedConnections() }
            relay = null
            binding = null
            destination = null
        }
        fun publish(phase: RelayPhase) {
            mutableState.value = RelayState(phase, relay?.connections() ?: 0,
                retiredBytes + (relay?.bytes() ?: 0), TcpRelay.CONNECTION_LIMIT,
                retiredRejected + (relay?.rejectedConnections() ?: 0))
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
                            if (binding != network || destination != upstream || relay == null || relay!!.failure().isNotEmpty()) {
                                retire()
                                phase = RelayPhase.FAILED
                                relay = TcpRelay(network.address, network.prefix, RELAY_PORT, upstream, TcpRelay.CONNECTION_LIMIT)
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
