import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {once} from 'node:events';
import {Store} from '../store.mjs';
import {createApp} from '../server.mjs';

test('pairing is one-use; rotation and revocation enforce access',()=>{
 const s=new Store();const code=s.pairCode();const a=s.redeem(code);
 assert.throws(()=>s.redeem(code));assert.equal(s.authenticate(a.token),a.deviceId);
 const next=s.rotate(a.deviceId);assert.equal(s.authenticate(a.token),undefined);
 assert.equal(s.authenticate(next),a.deviceId);s.revoke(a.deviceId);assert.equal(s.authenticate(next),undefined);s.close();
});
test('restart, concurrent duplicates, payload conflict, device ownership and ordered replay',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'wear-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const path=join(dir,'state.sqlite');let s=new Store(path);
 const a=s.redeem(s.pairCode()),b=s.redeem(s.pairCode());s.close();
 let creates=0;
 const upstream=async (url,opts)=>{
  if(opts.method==='POST') {creates++; await new Promise(r=>setTimeout(r,15));return Response.json({run_id:'run_durable123'});}
  return Response.json({run_id:'run_durable123',status:'completed',output:'Recovered answer'});
 };
 async function start(){const server=createApp({token:'x'.repeat(32),hermesKey:'h'.repeat(32),legacyAuth:false,storePath:path,upstream,traceSink:()=>{}});server.listen(0,'127.0.0.1');await once(server,'listening');return server;}
 let server=await start();
 const body={id:'request-123456',sessionId:'session-123456',text:'Hello'};
 async function post(route,body,token=a.token){const r=await fetch('http://127.0.0.1:'+server.address().port+route,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,body:await r.json()};}
 const copies=await Promise.all(Array.from({length:10},()=>post('/watch/ask',body)));
 assert.equal(creates,1);assert.ok(copies.every(r=>r.body.id===body.id));
 await new Promise(r=>setTimeout(r,30));
 assert.equal((await post('/watch/ask',{...body,text:'Changed'})).status,409);
 assert.equal((await post('/watch/status',{runId:'run_durable123'},b.token)).status,404);
 assert.equal((await post('/watch/approve',{runId:'run_durable123',requestId:'a',generation:0,choice:'once'},b.token)).status,404);
 await new Promise(r=>server.close(r)); server=await start();
 const recovered=await post('/watch/ask',body);assert.equal(creates,1);assert.equal(recovered.body.runId,'run_durable123');
 const done=await post('/watch/status',{id:body.id});assert.equal(done.body.text,'Recovered answer');
 const events=await post('/watch/events',{id:body.id,after:0});assert.deepEqual(events.body.events.map(x=>x.seq),[1,2,3]);
 assert.equal((await post('/watch/status',{id:body.id})).body.seq,3);
 await new Promise(r=>server.close(r));
});
test('durable receipt precedes admission; abandoned queued work resumes without a watch',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'wear-queued-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
 const path=join(dir,'state.sqlite');let s=new Store(path);
 const device=s.redeem(s.pairCode());
 s.reserve(device.deviceId,{id:'queued-before-crash',sessionId:'session-before-crash',text:'Continue'});s.close();
 let calls=0;
 const server=createApp({token:'x'.repeat(32),hermesKey:'h'.repeat(32),legacyAuth:false,storePath:path,traceSink:()=>{},upstream:async()=>{calls++;return Response.json({run_id:'run_restartqueue'});}});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 await new Promise(r=>setTimeout(r,1200));
 s=new Store(path);assert.equal(s.get(device.deviceId,'queued-before-crash').run,'run_restartqueue');assert.equal(calls,1);s.close();
 await new Promise(r=>server.close(r));
});
test('slow Hermes admission cannot hold the device acknowledgment hostage',async()=>{
 const s=new Store();const device=s.redeem(s.pairCode());s.close();
 const dir=mkdtempSync(join(tmpdir(),'wear-slow-')),path=join(dir,'state.sqlite');
 const db=new Store(path),paired=db.redeem(db.pairCode());db.close();
 let release;const gate=new Promise(r=>release=r);let finished=false;
 const server=createApp({token:'x'.repeat(32),hermesKey:'h'.repeat(32),legacyAuth:false,storePath:path,traceSink:()=>{},upstream:async()=>{await gate;finished=true;return Response.json({run_id:'run_slowadmission'});}});
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const at=performance.now();
 const r=await fetch('http://127.0.0.1:'+server.address().port+'/watch/ask',{method:'POST',headers:{Authorization:'Bearer '+paired.token,'Content-Type':'application/json'},body:JSON.stringify({id:'slow-request-123',sessionId:'slow-session-123',text:'Hi'})});
 assert.equal(r.status,202);assert.equal((await r.json()).state,'accepted');assert.equal(finished,false);assert.ok(performance.now()-at<2000);
 release();await new Promise(r=>setTimeout(r,20));await new Promise(r=>server.close(r));rmSync(dir,{recursive:true,force:true});
});
test('answers are cached durably with no client status polling',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'wear-unattended-')),path=join(dir,'state.sqlite');
 const db=new Store(path),paired=db.redeem(db.pairCode());
 db.reserve(paired.deviceId,{id:'unattended-request',sessionId:'unattended-session',text:'Work'});
 db.publish(paired.deviceId,'unattended-request',{ok:true,state:'working',runId:'run_unattended123',text:''});db.close();
 const server=createApp({token:'x'.repeat(32),hermesKey:'h'.repeat(32),legacyAuth:false,storePath:path,traceSink:()=>{},upstream:async()=>Response.json({run_id:'run_unattended123',status:'completed',output:'Saved without the watch'})});
 server.listen(0,'127.0.0.1');await once(server,'listening');await new Promise(r=>setTimeout(r,1200));
 const saved=new Store(path);assert.equal(JSON.parse(saved.get(paired.deviceId,'unattended-request').result).text,'Saved without the watch');saved.close();
 await new Promise(r=>server.close(r));rmSync(dir,{recursive:true,force:true});
});
