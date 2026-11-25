'use strict';
const fs = require('fs');
const {execFileSync} = require('child_process');
const {options,config,guard} = require('./global-config');
const opts = options(fs.readFileSync('/proc/cmdline','utf8'));
if (!opts.global) process.exit(0);
const run = '/run/podroid-host-services';
fs.mkdirSync(run,{recursive:true});
fs.mkdirSync('/var/lib/podroid-proxy',{recursive:true});
function installGuard(gateway, localAddresses, iface) {
    let exists = false;
    try { execFileSync('nft',['list','table','inet','podroid_global_guard'],{stdio:'ignore'}); exists = true; } catch (_) {}
    // Delete/recreate in ONE nft transaction, so changing the gateway never
    // creates a brief interval with no fail-closed rules.
    execFileSync('nft',['-f','-'],{input:(exists ? 'delete table inet podroid_global_guard\n' : '')+guard(opts,gateway,localAddresses,iface)});
}
if (process.argv.includes('--guard')) { installGuard(); process.exit(0); }
const route = execFileSync('ip',['-4','route','show','default'],{encoding:'utf8'});
const match = route.match(/^default via (\S+) dev (\S+)/m);
if (!match) throw new Error('No VM gateway');
const [,gateway,iface] = match;
const localAddresses = JSON.parse(execFileSync('ip',['-j','addr','show','dev',iface],{encoding:'utf8'}))
    .flatMap(x => x.addr_info.map(a => a.local));
installGuard(gateway,localAddresses,iface);
fs.writeFileSync(run+'/global.json',JSON.stringify(config(opts,gateway,iface)));
execFileSync('/usr/local/bin/sing-box',['check','-c',run+'/global.json'],{stdio:'inherit'});
// OpenRC supervises sing-box and the SOCKS shim separately. A killed Node
// configurator cannot leave an orphan TUN process behind on supervisor restart.
fs.writeFileSync('/etc/resolv.conf','nameserver 172.29.255.2\n');
