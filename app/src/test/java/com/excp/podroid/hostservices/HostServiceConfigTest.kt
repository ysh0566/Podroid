package com.excp.podroid.hostservices

import org.junit.Assert.*
import org.junit.Test

class HostServiceConfigTest {
    @Test fun `reject malformed proxy endpoints and relay loops`() {
        assertTrue(RelayConfig().isValid())
        assertTrue(RelayConfig(host = "proxy.example", port = 8080).isValid())
        listOf("", "http://proxy", "user:password@proxy", "a b", "a\nb", "0.0.0.0;").forEach {
            assertFalse(RelayConfig(host = it).isValid())
        }
        assertFalse(RelayConfig(port = 0).isValid())
        assertFalse(RelayConfig(port = 65536).isValid())
        assertFalse(RelayConfig(port = RELAY_PORT).isValid())
        assertFalse(RelayConfig(host = "localhost", port = RELAY_PORT).isValid())
        assertFalse(RelayConfig(host = "10.0.2.2", port = MICROPHONE_PORT).isValid())
    }

    @Test fun `microphone credentials rotate and cannot leak through launch diagnostics`() {
        val session = MicrophoneSession()
        val first = session.prepare()
        val second = session.prepare()
        assertNotEquals(first, second)
        assertTrue(second.matches(Regex("[0-9a-f]{64}")))
        val command = hostServiceCmdline("quiet podroid.mic_token=old", true, second)
        assertFalse(command.contains("=old"))
        assertTrue(command.contains("podroid.mic_token=$second"))
        assertEquals("[quiet podroid.mic_token=<redacted>, next]",
            redactHostServiceSecrets("[quiet podroid.mic_token=$second, next]"))
        assertEquals("Kernel command line: podroid.mic_token=<redacted> quiet\nReady!",
            redactHostServiceSecrets("Kernel command line: podroid.mic_token=$second quiet\nReady!"))
        assertFalse(hostServiceCmdline("", false, "invalid").contains("mic_token="))
        session.clear()
        assertNull(session.token)
    }

    @Test fun `disabled relay removes stale boot flags without losing user arguments`() {
        assertEquals("quiet foo=bar podroid.proxy_port=0 podroid.mic_port=$MICROPHONE_PORT",
            hostServiceCmdline("quiet podroid.proxy_port=1 foo=bar podroid.mic_port=2", false))
        assertTrue(hostServiceCmdline("", true).contains("podroid.proxy_port=$RELAY_PORT"))
    }

    @Test fun `software backends only bind loopback`() {
        for (backend in listOf("qemu", "uml")) {
            assertEquals("127.0.0.1", guestNetwork(backend)!!.address.hostAddress)
            assertEquals(32, guestNetwork(backend)!!.prefix)
        }
        assertNull(guestNetwork("unsupported"))
    }

    @Test fun `proxy exclusions are normalized and cannot inject boot or shell arguments`() {
        val config = RelayConfig(noProxy = " localhost, .example.com,10.0.0.0/8,localhost ")
        assertTrue(config.isValid())
        assertEquals("localhost,.example.com,10.0.0.0/8", config.normalizedNoProxy())
        for (invalid in listOf("a b", "a'", "\$(id)", "a;env", "x".repeat(513))) {
            assertFalse(RelayConfig(noProxy = invalid).isValid())
        }
        val command = hostServiceCmdline("quiet podroid.no_proxy=old", true, noProxy = config.noProxy)
        assertTrue(command.contains("podroid.no_proxy=localhost,.example.com,10.0.0.0/8"))
        assertFalse(command.contains("=old"))
        assertFalse(hostServiceCmdline("podroid.no_proxy=old", false).contains("no_proxy="))
    }
    @Test fun `global proxy flags and transport are validated independently of app proxy`() {
        assertFalse(RelayConfig(enabled = true, protocol = ProxyProtocol.SOCKS5).isValid())
        assertTrue(RelayConfig(enabled = true, global = true, protocol = ProxyProtocol.SOCKS5).isValid())
        assertFalse(RelayConfig(port = UDP_RELAY_PORT).isValid())
        val flags = hostServiceCmdline("podroid.proxy_mode=old podroid.proxy_type=bad podroid.proxy_lan=1", true,
            global = true, protocol = ProxyProtocol.SOCKS5)
        assertTrue(flags.contains("podroid.proxy_mode=global"))
        assertTrue(flags.contains("podroid.proxy_type=socks5"))
        assertTrue(flags.contains("podroid.proxy_lan=0"))
        assertFalse(flags.contains("=old"))
        assertFalse(hostServiceCmdline(flags, false).contains("proxy_mode="))
    }
}
