package com.excp.podroid.hostservices

import org.junit.Assert.*
import org.junit.Test

class RelayConnectionLimitTest {
    private val mib = 1024L * 1024
    private val roomy = RelayResources(512 * mib, 64 * mib, 32768, 100)

    @Test fun `saved choices round trip and missing or invalid values use 512`() {
        assertEquals(512, savedRelayConnectionLimit(null))
        for (choice in RELAY_CONNECTION_OPTIONS) {
            assertEquals(choice, savedRelayConnectionLimit(choice))
            assertTrue(RelayConfig(connectionLimit = choice).isValid())
        }
        for (invalid in listOf(-1, 1, 129, 2048, Int.MAX_VALUE)) {
            assertEquals(512, savedRelayConnectionLimit(invalid))
            assertFalse(RelayConfig(connectionLimit = invalid).isValid())
        }
    }

    @Test fun `fixed choices cap capacity and auto stops at 1024`() {
        for (choice in listOf(128, 256, 512, 1024)) {
            assertEquals(choice, relayConnectionLimit(choice, roomy))
        }
        assertEquals(1024, relayConnectionLimit(AUTO_RELAY_CONNECTIONS, roomy))
    }

    @Test fun `heap ceiling and current headroom independently constrain capacity`() {
        assertEquals(512, relayConnectionLimit(0, roomy.copy(maxHeap = 256 * mib)))
        assertEquals(112, relayConnectionLimit(0, roomy.copy(maxHeap = 128 * mib, usedHeap = 100 * mib)))
        assertEquals(112, relayConnectionLimit(512, roomy.copy(maxHeap = 128 * mib, usedHeap = 100 * mib)))
        assertEquals(0, relayConnectionLimit(0, roomy.copy(usedHeap = roomy.maxHeap)))
        assertEquals(0, relayConnectionLimit(512, roomy.copy(usedHeap = Long.MAX_VALUE)))
    }

    @Test fun `descriptor budget reserves capacity for the rest of the app`() {
        assertEquals(330, relayConnectionLimit(0, roomy.copy(descriptorLimit = 1024)))
        assertEquals(330, relayConnectionLimit(512, roomy.copy(descriptorLimit = 1024)))
        assertEquals(714, relayConnectionLimit(0, roomy.copy(descriptorLimit = 2048)))
        assertEquals(0, relayConnectionLimit(512, roomy.copy(descriptorLimit = 256)))
        assertEquals(0, relayConnectionLimit(0, roomy.copy(openDescriptors = 32768)))
        assertEquals(1024, relayConnectionLimit(0, roomy.copy(descriptorLimit = Long.MAX_VALUE)))
    }

    @Test fun `missing procfs information uses a conservative cap without ignoring memory`() {
        assertEquals(128, relayConnectionLimit(0, roomy.copy(descriptorLimit = null)))
        assertEquals(128, relayConnectionLimit(512, roomy.copy(openDescriptors = null)))
        assertEquals(64, relayConnectionLimit(0, roomy.copy(maxHeap = 32 * mib, usedHeap = 0, openDescriptors = null)))
    }

    @Test fun `descriptor parser reads the soft limit not the hard limit`() {
        assertEquals(1024L, parseDescriptorLimit("Max open files            1024                 65536                files\n"))
        assertEquals(Long.MAX_VALUE, parseDescriptorLimit("Max open files            unlimited            unlimited            files\n"))
        assertNull(parseDescriptorLimit("Max locked memory         65536                65536                bytes\n"))
        assertNull(parseDescriptorLimit("Max open files            -1                   65536                files\n"))
        assertNull(parseDescriptorLimit("Max open files            invalid"))
    }
    @Test fun `UDP admission budgets full datagrams and three descriptors`() {
        val resources = RelayResources(256L * 1024 * 1024, 0, 4096L, 50)
        assertEquals(128, relayConnectionLimit(1024, resources, udp = true))
        assertEquals(512, relayConnectionLimit(1024, resources))
    }
}
