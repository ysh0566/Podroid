const net=require('net'),{spawn}=require('child_process'),assert=require('assert');
const children=[];let interval,playInterval,stream,playback,authenticated=false,micBytes=0,playBytes=0,playNonzero=0,nonzero=0;
const env={...process.env,PULSE_SERVER:'unix:/run/podroid-pulse/native'};
const child=(args)=>{const p=spawn(args[0],args.slice(1),{env});children.push(p);p.stderr.on('data',d=>process.stderr.write(d));return p;};
const server=net.createServer(s=>{stream=s;let auth=Buffer.alloc(0);s.on('error',()=>{});s.on('data',d=>{auth=Buffer.concat([auth,d]);if(auth.length>=65&&!authenticated){assert.equal(auth.toString(),'1'.padStart(64,'0')+'\n');authenticated=true;const pcm=Buffer.alloc(1920);for(let i=0;i<1920;i+=2)pcm.writeInt16LE(999,i);interval=setInterval(()=>s.write(pcm),20);}});});
function finish(code){clearInterval(interval);clearInterval(playInterval);stream?.destroy();playback?.destroy();server.close();children.forEach(p=>p.kill());process.exitCode=code;}
server.listen(18889,'127.0.0.1',()=>{
 child(['/usr/bin/node','/usr/local/share/podroid-host-services/audio.js']);
 const capture=child(['/usr/bin/pacat','--record','--raw','--device=podroid_mic','--rate=48000','--channels=1','--format=s16le']);
 capture.stdout.on('data',d=>{micBytes+=d.length;for(let i=0;i+1<d.length;i+=2)if(d.readInt16LE(i)!==0)nonzero++;});
 const player=child(['/usr/bin/pacat','--playback','--raw','--device=podroid_sink','--rate=44100','--channels=2','--format=s16le']);
 const tone=Buffer.alloc(1764);for(let i=0;i<tone.length;i+=2)tone.writeInt16LE(777,i);
 playInterval=setInterval(()=>player.stdin.write(tone),10);
 setTimeout(()=>{playback=net.connect(4713,'127.0.0.1');playback.on('data',d=>{playBytes+=d.length;for(let i=0;i+1<d.length;i+=2)if(d.readInt16LE(i)!==0)playNonzero++;});playback.on('error',e=>{console.error(e);finish(1);});},1000);
 setTimeout(()=>{try{assert(authenticated);assert(micBytes>96000);assert(nonzero>10000);assert(playBytes>176400);assert(playNonzero>10000);console.log(JSON.stringify({result:'PASS',authenticated,micBytes,nonzero,playBytes,playNonzero}));finish(0);}catch(e){console.error(e);finish(1);}},7000);
});
setTimeout(()=>{console.error('Timeout');finish(1);},15000).unref();
