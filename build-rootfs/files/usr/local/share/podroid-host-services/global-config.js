'use strict';
const net = require('net');
const LAN4 = ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16','169.254.0.0/16'];
const LAN6 = ['fc00::/7','fe80::/10'];
function options(cmdline) {
    const flags = Object.fromEntries(cmdline.trim().split(/\s+/).map(x => x.split('=')));
    const mode = flags['podroid.proxy_mode'];
    if (mode && mode !== 'global') throw new Error('Invalid proxy mode');
    const global = mode === 'global';
    const protocol = flags['podroid.proxy_type'] || 'http';
    if (!['http','socks5'].includes(protocol)) throw new Error('Invalid proxy type');
    if (global && flags['podroid.proxy_port'] !== '18888') throw new Error('Invalid global relay port');
    return {global, protocol, bypassLan:flags['podroid.proxy_lan'] === '1'};
}
function config(opts, gateway, iface) {
    if (!net.isIPv4(gateway) || !/^[a-zA-Z0-9_.-]{1,15}$/.test(iface)) throw new Error('Invalid network');
    const socks = opts.protocol === 'socks5';
    const upstream = socks ? {type:'socks',version:'5',server:'127.0.0.1',server_port:18891} :
        {type:'http',server:gateway,server_port:18888};
    return {
        log:{level:'warn',timestamp:true},
        // Preserve domain names for HTTP CONNECT / SOCKS5 rather than forcing
        // an IPv6 literal on upstreams whose domain dialer selects usable IPv4.
        dns:{servers:[{type:'https',tag:'secure-dns',server:'1.1.1.1',detour:'proxy',
            tls:{enabled:true,server_name:'cloudflare-dns.com'}},
            {type:'fakeip',tag:'domain-map',inet4_range:'198.19.0.0/16',inet6_range:'2001:2::/48'}],
            rules:[{query_type:['A','AAAA'],server:'domain-map'}],final:'secure-dns'},
        // Applications can cache synthetic DNS answers across proxy restarts.
        // Persist their mapping so those addresses never resolve to another name.
        experimental:{cache_file:{enabled:true,path:'/var/lib/podroid-proxy/cache.db',store_fakeip:true}},
        inbounds:[{type:'tun',tag:'tun',interface_name:'podroid-tun',
            address:['172.29.255.1/30','fdfe:706f:6472::1/126'],mtu:1500,
            auto_route:true,auto_redirect:true,strict_route:true,
            route_exclude_address:[gateway+'/32'],stack:'mixed'}],
        outbounds:[{...upstream,tag:'proxy',bind_interface: socks ? 'lo' : iface},
            {type:'direct',tag:'lan',bind_interface:iface}],
        route:{auto_detect_interface:false,default_interface:iface,final:'proxy',
            default_domain_resolver:'secure-dns',rules:[
                {port:53,action:'hijack-dns'},
                ...(opts.bypassLan ? [{ip_cidr:[...LAN4,...LAN6],action:'route',outbound:'lan'}] : []),
                // HTTP CONNECT has no generic UDP support. Explicit rejection lets
                // QUIC-capable clients fall back to TCP without leaking UDP directly.
                ...(!socks ? [{network:'udp',action:'reject'}] : []),
                {network:['tcp','udp'],action:'route',outbound:'proxy'},
                {action:'reject'},
            ]},
    };
}
function guard(opts, gateway, localAddresses = [], iface) {
    if (iface && !/^[a-zA-Z0-9_.-]{1,15}$/.test(iface)) throw new Error("Invalid interface");
    if (localAddresses.some(x => !net.isIP(x))) throw new Error("Invalid local address");
    const local4 = localAddresses.filter(net.isIPv4), local6 = localAddresses.filter(net.isIPv6);
    if (gateway && !net.isIPv4(gateway)) throw new Error('Invalid gateway');
    // A separate table survives a sing-box crash or route cleanup. Install it
    // before bringing up the VM NIC, and keep it until the next boot/mode change.
    // auto_redirect rewrites TCP to a listener on a guest-local address;
    // its output packet can retain the original oif until routing is repeated.
    const common = `${iface ? `oifname != "${iface}" accept` : ''}
        ${local4.length ? `ip daddr { ${local4.join(', ')} } accept` : ''}
        ${local6.length ? `ip6 daddr { ${local6.join(', ')} } accept` : ''}
        ip daddr 127.0.0.0/8 accept
        ip6 daddr ::1 accept
        oifname "lo" accept
        oifname "podroid-tun" accept
        ct direction reply ct state established,related accept
        udp sport 68 udp dport 67 accept
        ip6 hoplimit 255 icmpv6 type { nd-neighbor-solicit, nd-neighbor-advert } accept
        ${gateway ? `ip daddr ${gateway} tcp dport { 18888, 18889, 18890 } accept` : ''}
        udp dport 53 drop
        tcp dport 53 drop
        ${opts.bypassLan && gateway ? `ip daddr { ${LAN4.join(', ')} } accept
        ip6 daddr { ${LAN6.join(', ')} } accept` : ''}
        counter drop`;
    // Postrouting observes the final interface after sing-box's route marks
    // and DNAT have been applied, for both local and container traffic.
    return `table inet podroid_global_guard {
    ${gateway ? `chain dns_output { type nat hook output priority -151; policy accept;
        ip daddr ${gateway} meta l4proto { tcp, udp } th dport 53 dnat ip to 172.29.255.2
    }
    chain dns_forward { type nat hook prerouting priority -151; policy accept;
        ip daddr ${gateway} meta l4proto { tcp, udp } th dport 53 dnat ip to 172.29.255.2
    }` : ''}
    chain egress { type filter hook postrouting priority -10; policy accept;
        ${common}
    }
}\n`;
}
module.exports = {options,config,guard};
