'use strict';
const net = require('net');
const fs = require('fs');
const { spawn, execFileSync } = require('child_process');
const clients = new Map();
const cmdline = fs.readFileSync('/proc/cmdline', 'utf8');
const micPort = Number(cmdline.match(/(?:^|\s)podroid\.mic_port=(\d+)(?:\s|$)/)?.[1] || 0);
const micToken = cmdline.match(/(?:^|\s)podroid\.mic_token=([0-9a-f]{64})(?:\s|$)/)?.[1];
const fifo = '/run/podroid-pulse/microphone.pcm';
let microphone, writer, drain, inode, stopping = false;
function gateway() {
    return execFileSync('/sbin/ip', ['-4', 'route', 'show', 'default'], {encoding: 'utf8'}).trim().split(/\s+/)[2];
}
const server = net.createServer(socket => {
    let host;
    try { host = gateway(); } catch { socket.destroy(); return; }
    if (!['127.0.0.1', host].includes(socket.remoteAddress) || clients.size >= 4) {
        socket.destroy(); return;
    }
    // pacat and Node handle partial TCP writes without the simple-protocol frame-drop assertion.
    const capture = spawn('/usr/bin/pacat', ['--record', '--raw', '--device=podroid_sink.monitor',
        '--rate=44100', '--channels=2', '--format=s16le', '--latency-msec=50'], {
        env: {...process.env, PULSE_SERVER: 'unix:/run/podroid-pulse/native'}, stdio: ['ignore', 'pipe', 'ignore']
    });
    clients.set(socket, capture);
    socket.setNoDelay(true);
    capture.stdout.pipe(socket);
    capture.on('error', () => socket.destroy());
    capture.on('exit', () => socket.destroy());
    socket.on('error', () => socket.destroy());
    socket.on('close', () => { capture.kill(); clients.delete(socket); });
});
server.on('error', error => { console.error('Playback listener:', error.code); shutdown(1); });
server.listen(4713, '0.0.0.0');
function disconnectMic() {
    microphone?.destroy(); writer?.destroy(); microphone = writer = undefined;
}
const retry = setInterval(() => {
    if (stopping || !micToken || micPort < 1 || micPort > 65535) return;
    try {
        const current = fs.statSync(fifo).ino;
        // Pulse recreates its FIFO after a crash; never keep writing into the unlinked old pipe.
        if (current !== inode) { disconnectMic(); inode = current; }
        // Keep the pipe source drained while apps are idle; their first capture must receive live audio.
        if (!drain) {
            const child = spawn('/usr/bin/pacat', ['--record', '--raw', '--device=podroid_mic',
                '--rate=48000', '--channels=1', '--format=s16le', '--latency-msec=20'], {
                env: {...process.env, PULSE_SERVER: 'unix:/run/podroid-pulse/native'}, stdio: 'ignore'
            });
            drain = child;
            child.on('exit', () => { if (drain === child) drain = undefined; });
            child.on('error', () => { if (drain === child) drain = undefined; });
        }
        if (microphone) return;
        const socket = net.connect({host: gateway(), port: micPort});
        microphone = socket;
        socket.setTimeout(5000, () => socket.destroy());
        socket.on('connect', () => {
            if (socket !== microphone) return;
            socket.setTimeout(0);
            socket.write(micToken + '\n');
            writer = fs.createWriteStream(fifo);
            writer.on('error', () => socket.destroy());
            socket.pipe(writer);
        });
        socket.on('error', () => {});
        socket.on('close', () => { if (socket === microphone) disconnectMic(); });
    } catch { disconnectMic(); }
}, 2000);
function shutdown(code) {
    if (stopping) return;
    stopping = true;
    clearInterval(retry); disconnectMic(); server.close();
    drain?.kill();
    for (const [socket, child] of clients) { socket.destroy(); child.kill(); }
    process.exitCode = code;
}
process.on('SIGTERM', () => shutdown(0));
process.on('SIGINT', () => shutdown(0));
