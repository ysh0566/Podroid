#!/bin/sh
set -eu

# Run in the disposable guest, not on Android or a production host. --check
# asks the running kernel to validate an atomic transaction without applying it.
# CONFIG_NFT_CT alone does not enable ct mark: NF_CONNTRACK_MARK is also needed.
nft --check --file - <<EOF
table inet podroid_kernel_probe_$$ {
    chain route_probe {
        type route hook output priority -151; policy accept;
        ct mark set 0x1234
        meta mark set ct mark
        udp dport 9 queue num 0 bypass
        tcp dport 9 reject with icmpx type admin-prohibited
    }
    chain nat_probe {
        type nat hook prerouting priority -100; policy accept;
        tcp dport 9 redirect to :12345
    }
}
EOF
echo 'PASS: running kernel supports global proxy nftables expressions'
