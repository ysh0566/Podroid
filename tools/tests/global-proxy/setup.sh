set -eu
ip netns add guest
ip link add host0 type veth peer name eth0 netns guest
ip addr add 169.254.77.1/24 dev host0
ip link set host0 up
ip -6 addr add fd78::1/64 dev host0 nodad
ip netns exec guest ip link set lo up
ip netns exec guest ip addr add 169.254.77.2/24 dev eth0
ip netns exec guest ip link set eth0 up
ip netns exec guest ip -6 addr add fd78::2/64 dev eth0 nodad
ip netns exec guest ip -6 route add default via fd78::1
ip netns exec guest ip route add default via 169.254.77.1
ip addr add 198.18.0.1/32 dev lo
ip -6 addr add 2001:db8::1/128 dev lo
sysctl -w net.ipv4.ip_forward=1
iptables -t nat -A POSTROUTING -s 169.254.77.0/24 -o eth0 -j MASQUERADE
python3 /src/tools/tests/global-proxy/fixture.py > /lab/fixture.log 2>&1 &
java -cp /lab/classes com.excp.podroid.hostservices.RelayHarness 169.254.77.1 > /lab/relay.log 2>&1 &
echo $! > /lab/relay.pid
ip netns exec guest node -e 'require("/src/build-rootfs/files/usr/local/share/podroid-host-services/socks-bridge").createBridge({gateway:"169.254.77.1"}).server.listen(18891,"127.0.0.1")' > /lab/bridge.log 2>&1 &
set -eu
ip netns add workload
ip netns exec guest ip link add docker0 type bridge
ip netns exec guest ip addr add 10.77.0.1/24 dev docker0
ip netns exec guest ip link set docker0 up
ip netns exec guest ip -6 addr add fd77::1/64 dev docker0
ip netns exec guest ip link add vethwork type veth peer name eth0 netns workload
ip netns exec guest ip link set vethwork master docker0
ip netns exec guest ip link set vethwork up
ip netns exec guest sysctl -w net.ipv4.ip_forward=1
ip netns exec guest sysctl -w net.ipv6.conf.all.forwarding=1
ip netns exec workload ip link set lo up
ip netns exec workload ip addr add 10.77.0.2/24 dev eth0
ip netns exec workload ip link set eth0 up
ip netns exec workload ip route add default via 10.77.0.1
ip netns exec workload ip -6 addr add fd77::2/64 dev eth0 nodad
ip netns exec workload ip -6 route add default via fd77::1
ip netns exec guest iptables -t nat -A POSTROUTING -s 10.77.0.0/24 ! -o docker0 -j MASQUERADE

# Match Docker's bridge forwarding policy instead of relying on ACCEPT defaults.
ip netns exec guest iptables -P FORWARD DROP
ip netns exec guest iptables -A FORWARD -i docker0 ! -o docker0 -j ACCEPT
ip netns exec guest iptables -A FORWARD -o docker0 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
