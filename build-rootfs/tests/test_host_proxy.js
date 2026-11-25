'use strict';
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const {proxySettings, configure} = require('../files/usr/local/share/podroid-host-services/proxy');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'podroid-proxy-test-'));
try {
    assert.equal(proxySettings('quiet podroid.proxy_port=0', undefined), null);
    assert.throws(() => proxySettings('podroid.proxy_port=99999', '10.0.2.2'));
    assert.throws(() => proxySettings('podroid.proxy_port=18888', "10.0.2.2'"));
    assert.equal(proxySettings('podroid.proxy_port=18888 podroid.no_proxy=.example.com,10.0.0.0/8,localhost', '10.0.2.2').noProxy,
        'localhost,127.0.0.1,::1,10.0.2.2,.example.com,10.0.0.0/8');
    assert.equal(proxySettings('podroid.proxy_port=18888 podroid.no_proxy=-', '10.0.2.2').noProxy,
        'localhost,127.0.0.1,::1,10.0.2.2');
    for (const value of ["a'", 'a;env', '$(id)', 'x'.repeat(513)]) {
        assert.throws(() => proxySettings(`podroid.proxy_port=18888 podroid.no_proxy=${value}`, '10.0.2.2'));
    }
    fs.mkdirSync(root + '/etc/conf.d', {recursive:true});
    fs.mkdirSync(root + '/root/.docker', {recursive:true});
    const p = root + '/root/.docker/config.json';
    const original = {auths: {registry: {auth: 'test-only'}}, proxies: {default: {httpProxy: 'http://existing:8080'}}};
    fs.writeFileSync(p, JSON.stringify(original));
    configure(root, null);
    assert.deepEqual(JSON.parse(fs.readFileSync(p)), original);
    configure(root, proxySettings('podroid.proxy_port=18888', '10.0.2.2'));
    configure(root, proxySettings('podroid.proxy_port=18888', '10.8.0.1'));
    assert.equal(JSON.parse(fs.readFileSync(p)).proxies.default.httpProxy, 'http://10.8.0.1:18888');
    assert.deepEqual(JSON.parse(fs.readFileSync(p)).auths, original.auths);
    assert.equal(fs.readFileSync(root + '/etc/conf.d/docker', 'utf8').match(/\|\| \./g).length, 1);
    configure(root, null);
    assert.deepEqual(JSON.parse(fs.readFileSync(p)), original);
    assert.equal(fs.readFileSync(root + '/run/podroid-host-services/proxy.env', 'utf8'), '');
    configure(root, proxySettings('podroid.proxy_port=18888', '10.0.2.2'));
    fs.writeFileSync(p, JSON.stringify(original));
    configure(root, null);
    assert.deepEqual(JSON.parse(fs.readFileSync(p)), original);
    console.log('PASS: proxy validation, gateway changes, opt-out, credentials and manual edits preserved');
} finally { fs.rmSync(root, {recursive:true, force:true}); }
