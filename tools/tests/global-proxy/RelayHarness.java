package com.excp.podroid.hostservices;
import java.net.*;
public class RelayHarness {
 public static void main(String[] args) throws Exception {
  InetAddress host=InetAddress.getByName(args[0]); InetSocketAddress up=new InetSocketAddress("127.0.0.1",31080);
  RelayBudget budget=new RelayBudget(128);
  try(TcpRelay tcp=new TcpRelay(host,24,18888,up,budget); UdpRelay udp=new UdpRelay(host,24,18890,up,budget)) {
   System.out.println("ready");while(true)Thread.sleep(1000);
  }
 }
}
