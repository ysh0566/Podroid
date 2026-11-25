#!/usr/bin/env python3
"""Integration assertions inside the disposable privileged Docker container."""
import json, pathlib, subprocess, time, os, signal, socket
lab=pathlib.Path('/lab')
binary=next(lab.glob('sing-box-*-linux-arm64-musl/sing-box'))
source='/src/build-rootfs/files/usr/local/share/podroid-host-services/global-config'

# Fixture HTTP servers bind on background threads; wait for actual listeners
# rather than racing their startup under a concurrent rootfs/kernel build.
for endpoint in [('198.18.0.1',18080),('2001:db8::1',18080),('169.254.77.1',18888),('127.0.0.1',31080)]:
    deadline=time.monotonic()+15
    while True:
        try:
            with socket.create_connection(endpoint,timeout=.2):break
        except OSError:
            if time.monotonic()>deadline:raise
            time.sleep(.1)

def command(args, expected=0, timeout=15):
    r=subprocess.run(args,capture_output=True,text=True,timeout=timeout)
    if expected is not None and r.returncode!=expected:
        raise AssertionError(f'{args}: {r.returncode}\n{r.stdout}\n{r.stderr}')
    return r

def ns(name,args,expected=0):return command(['ip','netns','exec',name]+args,expected)

def tcp(name,expected=True,ipv6=False):
    url='http://[2001:db8::1]:18080' if ipv6 else 'http://198.18.0.1:18080'
    r=ns(name,['curl','-g','--noproxy','*','-sS','--max-time','3','-o','/dev/null','-w','%{http_code}',url],None)
    assert (r.returncode==0 and r.stdout=='200')==expected,(name,url,r.stdout,r.stderr)

def udp(name,expected=True,address='198.18.0.1'):
    script=f"import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(2);s.sendto(b'udp-e2e',({address!r},18081));assert s.recv(100)==b'udp-e2e'"
    r=ns(name,['python3','-c',script],None)
    assert (r.returncode==0)==expected,(name,r.stderr)

def generate(protocol):
    script="const c=require(process.argv[1]);const x=c.config({protocol:process.argv[2],bypassLan:false},'169.254.77.1','eth0');x.experimental.cache_file.path='/lab/domain-cache.db';console.log(JSON.stringify(x))"
    (lab/'tun.json').write_text(command(['node','-e',script,source,protocol]).stdout)

script="const c=require(process.argv[1]);console.log(c.guard({bypassLan:false},'169.254.77.1',['169.254.77.2','fd78::2'],'eth0'))"
(lab/'guard.nft').write_text(command(['node','-e',script,source]).stdout)
ns('guest',['nft','-f','/lab/guard.nft'])
cached_answers=[]
for protocol in ['http','socks5']:
    generate(protocol)
    command([str(binary),'check','-c','/lab/tun.json'])
    with (lab/(protocol+'.log')).open('w') as log:
        proxy=subprocess.Popen(['ip','netns','exec','guest',str(binary),'run','-c','/lab/tun.json'],stdout=log,stderr=log)
        try:
            for _ in range(50):
                if proxy.poll() is not None:raise AssertionError((lab/(protocol+'.log')).read_text())
                if ns('guest',['ip','link','show','podroid-tun'],None).returncode==0:break
                time.sleep(.1)
            time.sleep(.3)
            if not cached_answers:
                cached_answers=[ns('guest',['dig','@172.29.255.2','proxy-fixture.test',t,'+short']).stdout.strip() for t in ['A','AAAA']]
            # Reuse old DNS answers after the HTTP process has exited. The
            # SOCKS process must restore both mappings from its persistent cache.
            for namespace in ['guest','workload']:
                tcp(namespace);tcp(namespace,ipv6=True)
                for address in cached_answers:
                    address='['+address+']' if ':' in address else address
                    r=ns(namespace,['curl','--noproxy','*','--resolve','proxy-fixture.test:18080:'+address,
                        '-fsS','--max-time','5','-o','/dev/null','-w','%{http_code}','http://proxy-fixture.test:18080'])
                    assert r.stdout=='200',r.stdout
                for dns in ['172.29.255.2','169.254.77.1']:
                    for query_type in ['A','SOA']:
                        # A exercises the domain mapping; SOA still crosses DoH.
                        answer=ns(namespace,['dig','@'+dns,'example.com',query_type,'+short','+time=4','+tries=1']).stdout
                        assert answer.strip() and 'timed out' not in answer,answer
                udp(namespace,expected=protocol=='socks5')
                udp(namespace,expected=protocol=='socks5',address=cached_answers[0])
            print(protocol+': transparent TCP/IPv6/DNS/container and UDP policy PASS',flush=True)
            if protocol=='socks5':
                os.kill(int((lab/'relay.pid').read_text()),signal.SIGTERM)
                time.sleep(.3)
                tcp('guest',expected=False);udp('workload',expected=False)
                # The destination remains alive: failure is relay loss, not the fixture disappearing.
                r=command(['curl','-sS','-o','/dev/null','-w','%{http_code}','http://198.18.0.1:18080'])
                assert r.stdout=='200'
                print('upstream/relay loss: fail-closed PASS',flush=True)
        finally:
            proxy.terminate()
            try:proxy.wait(timeout=5)
            except subprocess.TimeoutExpired:proxy.kill();proxy.wait()
    for namespace in ['guest','workload']:
        tcp(namespace,expected=False);tcp(namespace,expected=False,ipv6=True)
    assert ns('guest',['nft','list','table','inet','podroid_global_guard']).returncode==0
    print('proxy stopped: guard persists, direct TCP blocked PASS',flush=True)
print('ALL GLOBAL PROXY INTEGRATION CHECKS PASSED',flush=True)
