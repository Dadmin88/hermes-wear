import test from "node:test";
import assert from "node:assert/strict";
import {once} from "node:events";
import {createApp} from "../server.mjs";

test("authenticated metadata collector filters private fields and emits trace boundaries",async t=>{
 const captured=[];
 const server=createApp({token:"s".repeat(36),hermesKey:"h".repeat(36),
   traceSink:e=>captured.push(e),upstream:async()=>new Response(JSON.stringify({run_id:"run_123456",status:"completed",output:"OK"}))});
 server.listen(0,"127.0.0.1"); await once(server,"listening");
 t.after(()=>server.close());
 const base="http://127.0.0.1:"+server.address().port;
 const post=async(path,body,auth=true)=>fetch(base+path,{method:"POST",headers:{
  "content-type":"application/json",...(auth?{authorization:"Bearer "+"s".repeat(36)}:{})},body:JSON.stringify(body)});
 assert.equal((await post("/watch/trace",{id:"turn-123456",source:"watch",events:[]},false)).status,401);
 const r=await post("/watch/trace",{id:"turn-123456",source:"watch",events:[
  {id:"turn-123456",stage:"watch_ui_updated",utcMs:100,monoMs:90,state:"completed",text:"PRIVATE",token:"PRIVATE"},
  {id:"other-id",stage:"watch_ui_updated",utcMs:100,monoMs:90}
 ]});
 assert.equal(r.status,200);
 assert.equal(captured.filter(e=>e.stage==="watch_ui_updated").length,1);
 assert.ok(!JSON.stringify(captured).includes("PRIVATE"));
 await post("/watch/ask",{id:"turn-123456",sessionId:"session-123456",text:"PRIVATE"});
 await post("/watch/status",{id:"turn-123456",runId:"run_123456"});
 for(const stage of ["relay_received","hermes_accepted","hermes_completed_observed"])
  assert.ok(captured.some(e=>e.stage===stage),stage);
 assert.ok(!JSON.stringify(captured).includes("PRIVATE"));
});
