package com.excp.podroid.hostservices;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class UdpRelayTest {
    private static final InetAddress LOOP;
    static { try { LOOP = InetAddress.getByName("127.0.0.1"); } catch (Exception e) { throw new RuntimeException(e); } }
    private static byte[] packet(int size) {
        byte[] b = new byte[size]; Arrays.fill(b, (byte) 42);
        System.arraycopy(new byte[]{0,0,0,1,8,8,8,8,0,53},0,b,0,10);return b;
    }
    private static Socket client(UdpRelay relay) throws Exception {
        Socket c = new Socket(LOOP, relay.port()); c.setSoTimeout(3000); return c;
    }
    private static void until(Callable<Boolean> done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!done.call() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(done.call());
    }
    @Test public void fragmentedHandshakeAndDatagramsPreservePayloadAndCloseAssociation() throws Exception {
        try (FakeSocks socks = new FakeSocks(false); UdpRelay relay = socks.relay(new RelayBudget(8)); Socket c = client(relay)) {
            assertEquals(1, c.getInputStream().read());
            for (int size : new int[]{10, 1600, 64000}) {
                byte[] message = packet(size);
                DataOutputStream out = new DataOutputStream(c.getOutputStream());
                out.writeByte(size >> 8); out.flush(); out.writeByte(size & 255);
                out.write(message,0,5);out.flush();out.write(message,5,size-5);out.flush();
                DataInputStream in = new DataInputStream(c.getInputStream());
                assertEquals(size,in.readUnsignedShort());byte[] echo=new byte[size];in.readFully(echo);assertArrayEquals(message,echo);
            }
            c.close(); until(() -> relay.connections() == 0);
            assertTrue(relay.bytes() > 100000);
            assertEquals("",relay.failure());
        }
    }
    @Test public void capacityIsSharedWithTcpAndRecoveredOnClose() throws Exception {
        RelayBudget budget = new RelayBudget(1);
        assertTrue(budget.acquire());
        try (FakeSocks socks = new FakeSocks(false); UdpRelay relay = socks.relay(budget)) {
            try(Socket denied=client(relay)) {assertEquals(-1,denied.getInputStream().read());}
            assertEquals(1,relay.rejectedConnections());budget.release();
            try(Socket allowed=client(relay)) {assertEquals(1,allowed.getInputStream().read());}
            until(() -> relay.connections()==0);assertTrue(budget.acquire());budget.release();
        }
    }
    @Test public void upstreamWithoutUdpDoesNotFallBackToDirect() throws Exception {
        try(FakeSocks socks=new FakeSocks(true);UdpRelay relay=socks.relay(new RelayBudget(2));Socket c=client(relay)) {
            assertEquals(-1,c.getInputStream().read());until(()->relay.connections()==0);
        }
    }
    @Test public void malformedFramesRevokeAssociation() throws Exception {
        try(FakeSocks socks=new FakeSocks(false);UdpRelay relay=socks.relay(new RelayBudget(2))) {
            for (byte[] malformed : new byte[][]{{0,1,0},{0,10,0,0,1,1,8,8,8,8,0,53}}) {
                try(Socket c=client(relay)) {assertEquals(1,c.getInputStream().read());c.getOutputStream().write(malformed);assertEquals(-1,c.getInputStream().read());}
            }
            until(()->relay.connections()==0);
        }
    }
    @Test public void socksPacketValidationRejectsFragmentationAndTruncation() {
        assertTrue(UdpRelay.validPacket(ByteBuffer.wrap(packet(10))));
        byte[] fragmented=packet(10);fragmented[2]=1;
        assertFalse(UdpRelay.validPacket(ByteBuffer.wrap(fragmented)));
        assertFalse(UdpRelay.validPacket(ByteBuffer.wrap(new byte[]{0,0,0,3,10,1,2})));
        assertFalse(UdpRelay.validPacket(ByteBuffer.wrap(new byte[]{0,0,0,4,0,0,0})));
    }
    @Test public void concurrentAssociationsKeepDatagramsIsolated() throws Exception {
        ExecutorService clients=Executors.newFixedThreadPool(16);
        try(FakeSocks socks=new FakeSocks(false);UdpRelay relay=socks.relay(new RelayBudget(16))) {
            java.util.List<Future<?>> results=new java.util.ArrayList<>();
            for(int client=0;client<16;client++) {
                final int id=client;
                results.add(clients.submit(()->{
                    try(Socket c=client(relay)) {
                        assertEquals(1,c.getInputStream().read());
                        DataInputStream in=new DataInputStream(c.getInputStream());
                        DataOutputStream out=new DataOutputStream(c.getOutputStream());
                        for(int round=0;round<64;round++) {
                            byte[] p=packet(256);p[10]=(byte)id;p[11]=(byte)round;
                            out.writeShort(p.length);out.write(p);out.flush();
                            assertEquals(p.length,in.readUnsignedShort());byte[] echo=new byte[p.length];in.readFully(echo);assertArrayEquals(p,echo);
                        }
                    } catch(Exception e) {throw new RuntimeException(e);}
                }));
            }
            for(Future<?> result:results)result.get(15,TimeUnit.SECONDS);
            until(()->relay.connections()==0);assertEquals(0,relay.rejectedConnections());
        } finally {clients.shutdownNow();}
    }
    @Test public void guestDisconnectDuringUpstreamHandshakeReleasesBudget() throws Exception {
        RelayBudget budget=new RelayBudget(1);
        try(ServerSocket blackhole=new ServerSocket(0,8,LOOP);
            UdpRelay relay=new UdpRelay(LOOP,32,0,new InetSocketAddress(LOOP,blackhole.getLocalPort()),budget)) {
            Socket guest=client(relay);until(()->relay.connections()==1);guest.close();
            until(()->relay.connections()==0);assertTrue(budget.acquire());budget.release();
        }
    }
    @Test public void restartRebindsTheSamePortAfterClosingAnActiveAssociation() throws Exception {
        try (FakeSocks socks = new FakeSocks(false)) {
            int port;
            try (UdpRelay relay = socks.relay(new RelayBudget(2)); Socket guest = client(relay)) {
                port = relay.port();
                assertEquals(1, guest.getInputStream().read());
                relay.close();
                assertEquals(-1, guest.getInputStream().read());
            }
            try (UdpRelay restarted = new UdpRelay(LOOP, 32, port,
                    new InetSocketAddress(LOOP, socks.server.getLocalPort()), new RelayBudget(2));
                 Socket guest = client(restarted)) {
                assertEquals(1, guest.getInputStream().read());
            }
        }
    }
    private static class FakeSocks implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,16,LOOP);
        final ExecutorService jobs=Executors.newCachedThreadPool();
        final ConcurrentLinkedQueue<Closeable> open=new ConcurrentLinkedQueue<>();
        FakeSocks(boolean reject) throws Exception {
            jobs.submit(()->{try {while(!server.isClosed()) {Socket c=server.accept();open.add(c);jobs.submit(()->serve(c,reject));}}catch(IOException ignored){}});
        }
        UdpRelay relay(RelayBudget budget) throws Exception {return new UdpRelay(LOOP,32,0,new InetSocketAddress(LOOP,server.getLocalPort()),budget);}
        void serve(Socket c,boolean reject) {
            try(c;DatagramSocket udp=new DatagramSocket(0,LOOP)) {
                open.add(udp::close);DataInputStream in=new DataInputStream(c.getInputStream());
                byte[] greeting=new byte[3];in.readFully(greeting);
                c.getOutputStream().write(5);c.getOutputStream().flush();Thread.sleep(5);c.getOutputStream().write(0);
                byte[] request=new byte[10];in.readFully(request);
                byte[] reply={5,(byte)(reject?7:0),0,1,127,0,0,1,(byte)(udp.getLocalPort()>>8),(byte)udp.getLocalPort()};
                for(byte b:reply) {c.getOutputStream().write(b);c.getOutputStream().flush();}
                if(reject)return;
                jobs.submit(()->{byte[] data=new byte[65535];try {while(!udp.isClosed()){DatagramPacket p=new DatagramPacket(data,data.length);udp.receive(p);udp.send(p);}}catch(IOException ignored){}});
                while(in.read()!=-1) {}
            }catch(Exception ignored){}
        }
        public void close() throws Exception {server.close();for(Closeable c:open)try{c.close();}catch(IOException ignored){}jobs.shutdownNow();jobs.awaitTermination(3,TimeUnit.SECONDS);}
    }
}
