'use strict';
const net = require('net');
const dgram = require('dgram');
const {once} = require('events');
const MAX_PACKET = 65507;

// Read only the handshake bytes. Keeping the socket in paused/readable mode
// preserves pipelined application data for the later stream pipe.
async function read(socket, size) {
    for (;;) {
        const value = socket.read(size);
        if (value) return value;
        if (socket.destroyed || socket.readableEnded) throw new Error('Connection closed');
        await new Promise((resolve, reject) => {
            const cleanup = () => {
                socket.off('readable', ready); socket.off('error', fail); socket.off('close', closed); socket.off('end', closed);
            };
            const ready = () => { cleanup(); resolve(); };
            const fail = e => { cleanup(); reject(e); };
            const closed = () => fail(new Error('Connection closed'));
            socket.once('readable', ready); socket.once('error', fail); socket.once('close', closed); socket.once('end', closed);
        });
    }
}
async function address(socket, header) {
    const type = header[3];
    if (type === 1 || type === 4) return Buffer.concat([header, await read(socket, type === 1 ? 6 : 18)]);
    if (type !== 3) throw new Error('Invalid address');
    const size = await read(socket, 1);
    if (!size[0]) throw new Error('Empty domain');
    return Buffer.concat([header, size, await read(socket, size[0] + 2)]);
}
function validPacket(data) {
    if (data.length < 7 || data.length > MAX_PACKET || data[0] || data[1] || data[2]) return false;
    const size = data[3] === 1 ? 10 : data[3] === 4 ? 22 : data[3] === 3 && data[4] ? 7 + data[4] : Infinity;
    return data.length >= size;
}
function createBridge({gateway, tcpPort = 18888, udpPort = 18890, limit = 1024}) {
    const sessions = new Set();
    const server = net.createServer({allowHalfOpen:true}, client => {
        if (sessions.size >= limit) { client.destroy(); return; }
        let upstream, udp, peer, pending = 0, ended = false;
        const close = () => {
            if (ended) return;
            ended = true; clearTimeout(timer); client.destroy(); upstream?.destroy();
            if (udp) { try { udp.close(); } catch (_) {} }
            sessions.delete(close);
        };
        const timer = setTimeout(close, 7000);
        sessions.add(close); client.on('error', close); client.on('close', close);
        const connect = async port => {
            upstream = net.createConnection({host:gateway, port, allowHalfOpen:true});
            upstream.on('error', close); upstream.on('close', close);
            await once(upstream, 'connect');
            return upstream;
        };
        (async () => {
            const greeting = await read(client, 2);
            if (greeting[0] !== 5 || greeting[1] === 0) throw new Error('Invalid greeting');
            if (!(await read(client, greeting[1])).includes(0)) throw new Error('Authentication unsupported');
            client.write(Buffer.from([5,0]));
            const header = await read(client, 4);
            if (header[0] !== 5 || header[2] !== 0) throw new Error('Invalid request');
            const request = await address(client, header);
            if (header[1] === 1) {
                await connect(tcpPort);
                upstream.write(Buffer.from([5,1,0]));
                if (!(await read(upstream, 2)).equals(Buffer.from([5,0]))) throw new Error('Upstream authentication');
                upstream.write(request);
                const reply = await address(upstream, await read(upstream, 4));
                if (reply[0] !== 5 || reply[1] !== 0) throw new Error('Upstream rejected connection');
                client.write(reply); clearTimeout(timer);
                // Node pipes bound queued data and preserve TCP half-close semantics.
                client.pipe(upstream); upstream.pipe(client);
            } else if (header[1] === 3) {
                await connect(udpPort);
                if ((await read(upstream, 1))[0] !== 1) throw new Error('UDP unavailable');
                udp = dgram.createSocket('udp4'); udp.on('error', close);
                udp.bind(0, '127.0.0.1'); await once(udp, 'listening');
                const reply = Buffer.from([5,0,0,1,127,0,0,1,0,0]); reply.writeUInt16BE(udp.address().port, 8);
                client.write(reply); clearTimeout(timer);
                // A SOCKS UDP control connection carries no application bytes.
                client.on('data', close); client.on('end', close); client.resume();
                udp.on('message', (data, remote) => {
                    if (remote.address !== '127.0.0.1' || !validPacket(data)) return;
                    if (!peer) peer = remote;
                    if (remote.port !== peer.port || upstream.writableLength > MAX_PACKET) return;
                    const frame = Buffer.allocUnsafe(data.length + 2); frame.writeUInt16BE(data.length); data.copy(frame, 2);
                    upstream.write(frame);
                });
                while (!ended) {
                    const size = (await read(upstream, 2)).readUInt16BE();
                    if (size < 7 || size > MAX_PACKET) throw new Error('Invalid frame');
                    const data = await read(upstream, size);
                    if (peer && validPacket(data) && pending < 16) {
                        pending++;
                        udp.send(data, peer.port, peer.address, () => { pending--; });
                    }
                }
            } else throw new Error('Unsupported SOCKS command');
        })().catch(close);
    });
    server.on('close', () => { for (const close of sessions) close(); });
    return {server, close() { for (const close of sessions) close(); server.close(); }};
}
module.exports = {createBridge, read, validPacket};

if (require.main === module) {
    const fs = require('fs');
    const {execFileSync} = require('child_process');
    const opts = require('./global-config').options(fs.readFileSync('/proc/cmdline','utf8'));
    if (!opts.global || opts.protocol !== 'socks5') process.exit(0);
    const route = execFileSync('ip',['-4','route','show','default'],{encoding:'utf8'});
    const gateway = route.match(/^default via (\S+)/m)?.[1];
    if (!net.isIPv4(gateway)) throw new Error('No VM gateway');
    const bridge = createBridge({gateway});
    bridge.server.on('error',e => { console.error(e.message); process.exit(1); });
    bridge.server.listen(18891,'127.0.0.1');
    process.on('SIGTERM',()=>bridge.close());
    process.on('SIGINT',()=>bridge.close());
}
