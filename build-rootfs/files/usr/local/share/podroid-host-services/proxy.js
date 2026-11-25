'use strict';
const fs = require('fs');
const net = require('net');
const {execFileSync} = require('child_process');

function proxySettings(cmdline, gateway) {
    const token = cmdline.trim().split(/\s+/).find(x => x.startsWith('podroid.proxy_port='));
    const text = token?.split('=')[1] || '0';
    if (!/^\d+$/.test(text)) throw new Error('Invalid relay port');
    const port = Number(text);
    if (port === 0) return null;
    if (port > 65535 || !net.isIPv4(gateway)) throw new Error('Invalid relay endpoint');
    const bypass = cmdline.trim().split(/\s+/).find(x => x.startsWith('podroid.no_proxy='))?.slice('podroid.no_proxy='.length) || '';
    // This value enters shell exports: reject metacharacters even if a custom
    // kernel command line bypasses Android's configuration validation.
    if (bypass.length > 512 || !/^[A-Za-z0-9.*:/,\-]*$/.test(bypass)) throw new Error('Invalid proxy exclusions');
    const noProxy = [...new Set(['localhost', '127.0.0.1', '::1', gateway,
        ...(bypass === '-' ? [] : bypass.split(',').filter(Boolean))])].join(',');
    const proxy = `http://${gateway}:${port}`;
    return {httpProxy: proxy, httpsProxy: proxy, noProxy};
}

function atomicJson(path, data) {
    const temp = path + '.podroid-new';
    fs.writeFileSync(temp, JSON.stringify(data, null, 2) + '\n', {mode: 0o600});
    fs.renameSync(temp, path);
}

function configure(root, proxy) {
    const stateDir = root + '/var/lib/podroid';
    const runDir = root + '/run/podroid-host-services';
    const dockerPath = root + '/root/.docker/config.json';
    const statePath = stateDir + '/managed-proxy.json';
    const read = path => fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, 'utf8')) : {};
    fs.mkdirSync(runDir, {recursive: true});
    // The profile and Docker service always source this generated file. Empty
    // content disables our environment without erasing the user's own proxy.
    fs.writeFileSync(runDir + '/proxy.env', '');
    if (!proxy && !fs.existsSync(statePath)) return;
    const config = read(dockerPath);
    const state = read(statePath);
    if (proxy) {
        fs.mkdirSync(stateDir, {recursive: true, mode: 0o700});
        fs.mkdirSync(root + '/root/.docker', {recursive: true, mode: 0o700});
        if (!fs.existsSync(statePath)) state.original = config.proxies?.default ?? null;
        state.managed = proxy;
        atomicJson(statePath, state);
        config.proxies = {...config.proxies, default: proxy};
        atomicJson(dockerPath, config);
        fs.writeFileSync(runDir + '/proxy.env',
            `export http_proxy='${proxy.httpProxy}' https_proxy='${proxy.httpsProxy}' HTTP_PROXY='${proxy.httpProxy}' HTTPS_PROXY='${proxy.httpsProxy}'\n` +
            `export no_proxy='${proxy.noProxy}' NO_PROXY='${proxy.noProxy}'\n`);
        const conf = root + '/etc/conf.d/docker';
        const line = '[ ! -r /run/podroid-host-services/proxy.env ] || . /run/podroid-host-services/proxy.env';
        const before = fs.existsSync(conf) ? fs.readFileSync(conf, 'utf8') : '';
        if (!before.split('\n').includes(line)) fs.appendFileSync(conf, `\n# Podroid integrated relay, generated per boot.\n${line}\n`);
    } else {
        // Do not undo a proxy the user deliberately changed while the VM ran.
        if (JSON.stringify(config.proxies?.default) === JSON.stringify(state.managed)) {
            if (state.original === null) delete config.proxies.default;
            else config.proxies.default = state.original;
            atomicJson(dockerPath, config);
        }
        fs.unlinkSync(statePath);
    }
}

if (require.main === module) {
    const cmdline = fs.readFileSync('/proc/cmdline', 'utf8');
    const route = execFileSync('/sbin/ip', ['-4', 'route', 'show', 'default'], {encoding:'utf8'});
    configure('', proxySettings(cmdline, route.match(/^default via (\S+)/m)?.[1]));
}
module.exports = {proxySettings, configure};
