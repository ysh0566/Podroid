package com.excp.podroid.hostservices

import java.io.File

const val AUTO_RELAY_CONNECTIONS = 0
val RELAY_CONNECTION_OPTIONS = listOf(128, 256, 512, 1024, AUTO_RELAY_CONNECTIONS)

fun savedRelayConnectionLimit(value: Int?): Int =
    value?.takeIf { it in RELAY_CONNECTION_OPTIONS } ?: TcpRelay.CONNECTION_LIMIT

internal data class RelayResources(
    val maxHeap: Long,
    val usedHeap: Long,
    val descriptorLimit: Long?,
    val openDescriptors: Int?,
)

internal fun relayConnectionLimit(requested: Int, resources: RelayResources, udp: Boolean = false): Int {
    require(requested in RELAY_CONNECTION_OPTIONS)
    val ceiling = if (requested == AUTO_RELAY_CONNECTIONS) 1024 else requested
    val maxHeap = resources.maxHeap.coerceAtLeast(0)
    val headroom = maxHeap - resources.usedHeap.coerceIn(0, maxHeap)
    // Budget twice the 32 KiB payload buffers for Java bookkeeping. Leave at least
    // 7/8 of the heap ceiling and 3/4 of current headroom to the rest of the app.
    // A UDP slot owns a guest TCP socket, upstream control and UDP socket,
    // plus two full datagram buffers. Budget the worst case for shared admission.
    val slotBytes = if (udp) 256L * 1024 else 4L * TcpRelay.BUFFER_BYTES
    val memorySlots = minOf(maxHeap / 8, headroom / 4) / slotBytes
    val descriptorSlots = if (resources.descriptorLimit != null && resources.openDescriptors != null) {
        val softLimit = resources.descriptorLimit.coerceAtLeast(0)
        // Two sockets per tunnel; reserve descriptors for VM/display/audio and
        // eight more for the selector, listener and a concurrent health probe.
        val reserve = maxOf(256L, softLimit / 4)
        (softLimit - resources.openDescriptors.coerceAtLeast(0) - reserve - 8)
            .coerceAtLeast(0) / (if (udp) 3 else 2)
    } else {
        // Procfs can be restricted. Do not assume a large capacity without evidence.
        128L
    }
    return minOf(ceiling.toLong(), memorySlots, descriptorSlots).toInt()
}

internal fun parseDescriptorLimit(limits: String): Long? {
    val value = Regex("(?m)^Max open files\\s+(\\S+)").find(limits)?.groupValues?.get(1)
    return if (value == "unlimited") Long.MAX_VALUE else value?.toLongOrNull()?.takeIf { it >= 0 }
}

internal fun readRelayResources(): RelayResources {
    val runtime = Runtime.getRuntime()
    val limit = try {
        parseDescriptorLimit(File("/proc/self/limits").readText())
    } catch (_: Exception) { null }
    val open = try { File("/proc/self/fd").list()?.size } catch (_: Exception) { null }
    return RelayResources(runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory(), limit, open)
}
