#!/usr/bin/env python3
# Isolated test-only SOCKS/CONNECT proxy and echo endpoints, never exposed on host ports.
import socket,threading,select,struct,http.server

def exact(c,n):
 b=b''
 while len(b)<n:
  x=c.recv(n-len(b))
  if not x:raise EOFError()
  b+=x
 return b

def addr(c,t):
 if t==1:h=socket.inet_ntop(socket.AF_INET,exact(c,4))
 elif t==4:h=socket.inet_ntop(socket.AF_INET6,exact(c,16))
 elif t==3:h=exact(c,exact(c,1)[0]).decode()
 else:raise ValueError()
 return h,struct.unpack('!H',exact(c,2))[0]
def pump(a,b):
 sockets=[a,b]
 while sockets:
  for s in select.select(sockets,[],[],10)[0]:
   data=s.recv(65536);other=b if s is a else a
   if data:other.sendall(data)
   else:sockets.remove(s);other.shutdown(socket.SHUT_WR)
def udp(c):
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as relay:
  relay.bind(('127.0.0.1',0));c.sendall(bytes([5,0,0,1,127,0,0,1])+struct.pack('!H',relay.getsockname()[1]))
  while True:
   ready=select.select([relay,c],[],[],5)[0]
   if c in ready and not c.recv(1):return
   if relay not in ready:continue
   packet,peer=relay.recvfrom(65535)
   if packet[:3]!=bytes([0,0,0]):continue
   if packet[3]==1:
    host=socket.inet_ntop(socket.AF_INET,packet[4:8]);offset=8
   elif packet[3]==3:
    length=packet[4];host=packet[5:5+length].decode();offset=5+length
   else:continue
   port=struct.unpack('!H',packet[offset:offset+2])[0]
   if host=='proxy-fixture.test':host='198.18.0.1'
   with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as out:
    out.settimeout(3);out.sendto(packet[offset+2:],(host,port));response,target=out.recvfrom(65535)
    header=bytes([0,0,0,1])+socket.inet_aton(target[0])+struct.pack('!H',target[1])
    relay.sendto(header+response,peer)
def serve(c):
 try:
  with c:
   first=exact(c,1)
   if first==b'\x05':
    exact(c,exact(c,1)[0]);c.sendall(b'\x05\x00');header=exact(c,4);host,port=addr(c,header[3])
    if header[1]==3:return udp(c)
    if header[1]!=1:return
    if host=='proxy-fixture.test':host='198.18.0.1'
    up=socket.create_connection((host,port),5);c.sendall(bytes([5,0,0,1,0,0,0,0,0,0]))
   else:
    line=first
    while not line.endswith(b'\r\n\r\n'):line+=exact(c,1)
    dest=line.split()[1].decode();host,port=dest.rsplit(':',1)
    if host=='proxy-fixture.test':host='198.18.0.1'
    up=socket.create_connection((host.strip('[]'),int(port)),5);c.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
   with up:pump(c,up)
 except Exception as e:print(type(e).__name__,flush=True)
def udp_echo():
 s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('198.18.0.1',18081))
 while True:
  data,peer=s.recvfrom(65535);s.sendto(data,peer)
class HTTP6(http.server.ThreadingHTTPServer):
 address_family=socket.AF_INET6
threading.Thread(target=lambda:HTTP6(('2001:db8::1',18080),http.server.SimpleHTTPRequestHandler).serve_forever(),daemon=True).start()
threading.Thread(target=udp_echo,daemon=True).start()
threading.Thread(target=lambda:http.server.ThreadingHTTPServer(('198.18.0.1',18080),http.server.SimpleHTTPRequestHandler).serve_forever(),daemon=True).start()
s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('127.0.0.1',31080));s.listen()
print('fixture ready',flush=True)
while True:
 c,_=s.accept();threading.Thread(target=serve,args=(c,),daemon=True).start()
