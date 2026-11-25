package com.excp.podroid.hostservices;

import java.net.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;

/** Socket contract tests run on the JVM with assertions enabled by Gradle. */
public class TcpRelayTest {
    @org.junit.Test(timeout = 20000) public void relayContract() throws Exception { main(new String[0]); }
    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assert TcpRelay.sameSubnet(InetAddress.getByName("172.20.108.121"), InetAddress.getByName("172.20.108.19"), 24);
        assert !TcpRelay.sameSubnet(InetAddress.getByName("172.20.108.121"), InetAddress.getByName("172.20.109.19"), 24);
        assert !TcpRelay.sameSubnet(loopback, InetAddress.getByName("::1"), 24);
        assert !TcpRelay.sameSubnet(loopback, InetAddress.getByName("127.0.0.2"), 32);
        byte[] payload = new byte[200_000];
        new Random(17).nextBytes(payload);
        ExecutorService server = Executors.newSingleThreadExecutor();
        try (ServerSocket upstream = new ServerSocket(0, 4, loopback)) {
            upstream.setSoTimeout(3000);
            Future<?> echo = server.submit(() -> {
                try (Socket peer = upstream.accept()) {
                    peer.setSoTimeout(3000);
                    byte[] request = peer.getInputStream().readAllBytes();
                    if (!Arrays.equals(payload, request)) throw new AssertionError("Request was corrupted");
                    // Respond only after EOF to catch relays that close both directions too early.
                    peer.getOutputStream().write(request);
                    peer.shutdownOutput();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            InetSocketAddress destination = new InetSocketAddress(loopback, upstream.getLocalPort());
            int reusablePort;
            try (TcpRelay relay = new TcpRelay(loopback, 32, 0, destination, 1);
                 Socket client = new Socket(loopback, relay.port())) {
                reusablePort = relay.port();
                client.setSoTimeout(3000);
                client.getOutputStream().write(payload);
                client.shutdownOutput();
                assert Arrays.equals(payload, client.getInputStream().readAllBytes()) : "Half-close truncated response";
                echo.get(4, TimeUnit.SECONDS);
                assert relay.bytes() == payload.length * 2L;
            }
            // Rebinding must work, and excess sessions must not create unbounded worker threads.
            TcpRelay relay = new TcpRelay(loopback, 32, reusablePort, destination, 1);
            try (Socket first = new Socket(loopback, relay.port()); Socket accepted = upstream.accept()) {
                try (Socket excess = new Socket(loopback, relay.port())) {
                    excess.setSoTimeout(3000);
                    assert excess.getInputStream().read() == -1 : "Connection limit was ignored";
                }
                assert relay.connections() == 1;
                relay.close();
                first.setSoTimeout(3000);
                assert first.getInputStream().read() == -1 : "Stop did not close active connection";
            } finally { relay.close(); }
        } finally { server.shutdownNow(); }
        browserConnections(loopback);
        System.out.println("PASS: byte integrity, half-close, subnet boundary, connection limit, shutdown, rebind, browser concurrency");
    }

    private static void browserConnections(InetAddress loopback) throws Exception {
        ArrayList<Socket> peers = new ArrayList<>();
        try (ServerSocket upstream = new ServerSocket(0, 64, loopback);
             TcpRelay relay = new TcpRelay(loopback, 32, 0,
                 new InetSocketAddress(loopback, upstream.getLocalPort()), TcpRelay.CONNECTION_LIMIT)) {
            upstream.setSoTimeout(3000);
            // Browser keep-alives must leave room for a new OAuth request through the same relay.
            for (int i = 0; i < 41; i++) {
                Socket client = new Socket(loopback, relay.port());
                peers.add(client);
                Socket server = upstream.accept();
                peers.add(server);
                client.setSoTimeout(3000);
                server.getOutputStream().write(42);
                assert client.getInputStream().read() == 42 : "Browser load blocked a new request";
            }
        } finally {
            for (Socket peer : peers) peer.close();
        }
    }
}
