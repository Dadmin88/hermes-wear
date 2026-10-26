import http from "node:http";
import { Store, hash } from "./store.mjs";
import { createHash } from "node:crypto";
import { synthesizeNous, VOICES } from "./nous_voice.mjs";
import { timingSafeEqual } from "node:crypto";
import { handlePairing } from "./pairing.mjs";

const MAX_BODY = 12 * 1024;
const ID = /^[a-zA-Z0-9_-]{8,100}$/;
const RUN_ID = /^run_[a-zA-Z0-9_-]{6,100}$/;
const DONE = new Set(["completed", "failed", "cancelled", "interrupted"]);

function equalSecret(actual, expected) {
  if (!actual || !expected) return false;
  const a = Buffer.from(actual);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}
function reply(res, code, body) {
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"
  });
  res.end(JSON.stringify(body));
}
async function readBody(req) {
  const parts = [];
  let size = 0;
  for await (const part of req) {
    size += part.length;
    if (size > MAX_BODY) {
      const err = new Error("Request too large"); err.status = 413; throw err;
    }
    parts.push(part);
  }
  let parsed;
  try { parsed = JSON.parse(Buffer.concat(parts).toString("utf8")); }
  catch { const err = new Error("Invalid JSON"); err.status = 400; throw err; }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    const err = new Error("Expected an object"); err.status = 400; throw err;
  }
  return parsed;
}
function string(x, max) {
  return typeof x === "string" && x.length <= max ? x.trim() : "";
}
function responseText(raw) {
  const output = raw.output ?? raw.answer ?? "";
  if (typeof output === "string") return output;
  if (Array.isArray(output)) return output.map(x => x?.text ?? x?.content ?? "").filter(Boolean).join("\n");
  return typeof output === "object" && output ? JSON.stringify(output) : "";
}
function approvalOf(raw) {
  const controls = Array.isArray(raw.pending_controls) ? raw.pending_controls : [];
  const c = controls.find(item => {
    const kind = String(item?.kind ?? item?.type ?? "approval");
    return kind.includes("approval") || kind.includes("consent");
  }) ?? (raw.status === "waiting_for_approval" ? controls[0] : null);
  const legacy = raw.approval && typeof raw.approval === "object" ? raw.approval : {};
  const source = c || legacy;
  if (!source || !Object.keys(source).length) return null;
  const requestId = source.prompt_id ?? source.request_id ?? legacy.request_id;
  const generation = source.execution_generation ?? legacy.execution_generation;
  const prompt = source.description ?? source.prompt ?? source.message ?? source.command ??
    legacy.description ?? legacy.command ?? "Hermes requests approval";
  return {
    requestId: typeof requestId === "string" ? requestId : null,
    generation: Number.isInteger(generation) ? generation : null,
    prompt: String(prompt).slice(0, 800),
  };
}
export function normalizeRun(raw) {
  const status = String(raw.status || "unknown");
  const approval = approvalOf(raw);
  return {
    ok: true,
    state: status === "waiting_for_approval" || approval ? "approval" : DONE.has(status) ? status : "working",
    status,
    runId: raw.run_id,
    sessionId: raw.session_id,
    text: responseText(raw).slice(0, 12000),
    approval,
    timings: {createdAt: Number(raw.created_at)||null, updatedAt: Number(raw.updated_at)||null},
    error: raw.error ? String(typeof raw.error === "string" ? raw.error : raw.error.message || "Hermes error").slice(0, 300) : null,
  };
}
export function createApp({ token, hermesKey, hermesUrl = "http://127.0.0.1:8642", watchProvider = "", watchModel = "", pairingFile = "", publicUrl = "", upstream = fetch, synthesizeAudio = synthesizeNous, traceSink = e => console.info(JSON.stringify(e)), storePath = ":memory:", legacyAuth = true, remoteVoice = true } = {}) {
  if (!token || token.length < 24 || !hermesKey || hermesKey.length < 16) {
    throw new Error("WATCH_RELAY_TOKEN (>=24 chars) and HERMES_API_KEY (>=16 chars) are required");
  }
  const store = new Store(storePath);
  const flights = new Map();
  const limits = new Map();
  function limit(key, max) {
    const now=Date.now(); let r=limits.get(key);
    if(!r || r.at+60000<now) { r={at:now,n:0}; limits.set(key,r); }
    if(limits.size>2000) for(const [k,v] of limits) if(v.at+60000<now) limits.delete(k);
    return ++r.n<=max;
  }
  const base = new URL(hermesUrl);
  if (base.protocol !== "http:" || !["127.0.0.1", "localhost", "::1"].includes(base.hostname)) {
    throw new Error("Hermes API must be on local loopback HTTP");
  }
  async function hermes(path, method = "GET", body, idem) {
    const headers = { Authorization: "Bearer " + hermesKey, Accept: "application/json" };
    if (body) headers["Content-Type"] = "application/json";
    if (idem) headers["Idempotency-Key"] = idem;
    let remote;
    try {
      remote = await upstream(new URL(path, base).toString(), {
        method, headers, body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(10000)
      });
    } catch {
      const error = new Error("Hermes API is unreachable or timed out"); error.status = 502; throw error;
    }
    let data;
    try { data = await remote.json(); }
    catch { const error = new Error("Invalid response from Hermes"); error.status = 502; throw error; }
    if (!remote.ok) {
      const error = new Error(String(data?.error?.message || data?.message || "Hermes rejected request").slice(0, 240));
      error.status = remote.status >= 400 && remote.status < 500 ? remote.status : 502;
      throw error;
    }
    return data;
  }
  const trace = (id, stage, fields = {}) => {
    if (!ID.test(id || "")) return;
    traceSink({id, stage, source: "relay", utcMs: Date.now(), monoMs: Math.round(performance.now()), ...fields});
  };
  const retryAfter=new Map();
  function admit(device,row) {
    const b={id:row.id,sessionId:row.session,text:row.text};
    const text=row.text;
    const flightKey=device+":"+b.id;
        if(!flights.has(flightKey)) flights.set(flightKey,(async()=>{
          // Native idempotency retention is 24h; never replay ambiguous older admissions.
          if(Date.now()-row.created>23*3600000) return store.publish(device,b.id,{ok:false,state:"failed",error:"Admission expired; execution outcome needs review",retryable:false});
          const modelSelection = watchProvider && watchModel ? { provider: watchProvider, model: watchModel } : {};
          const nativeKey=device==="legacy" ? "hermes-wear-"+b.id : "wear-"+hash(device+":"+b.id);
          const session=device==="legacy" ? "wear-"+b.sessionId : "wear-"+hash(device+":"+b.sessionId).slice(0,40);
          const run=await hermes("/v1/runs","POST",{input:text,session_id:session,...modelSelection},nativeKey);
          if(!RUN_ID.test(run.run_id||"")) throw Object.assign(new Error("Invalid Hermes run identity"),{status:502});
          trace(b.id,"hermes_accepted");
          const nowRow=store.get(device,b.id);
          if(nowRow?.result && JSON.parse(nowRow.result).state==="cancelled") {
            await hermes("/v1/runs/"+encodeURIComponent(run.run_id)+"/stop","POST",{});
            return JSON.parse(nowRow.result);
          }
          return store.publish(device,b.id,{ok:true,state:"working",runId:run.run_id,sessionId:b.sessionId,text:""});
        })().catch(error=>{
          // The durable reservation survives. Recover with the SAME native key on retry.
          if(legacyAuth) throw error;
          retryAfter.set(flightKey,Date.now()+10000);
          return store.publish(device,b.id,{ok:!(error.status>=400 && error.status<500),state:error.status>=400 && error.status<500 ? "failed" : "retrying",runId:"",error:error.status>=400 && error.status<500 ? "Hermes rejected admission" : "Hermes admission unavailable; safely retrying",retryable:!(error.status>=400 && error.status<500)});
        }).finally(()=>flights.delete(flightKey)));
    return flights.get(flightKey);
  }
  const eventStages = new Set(["watch_request_created","watch_nodes_requested","watch_send_invoked",
    "watch_send_submitted","watch_send_failed","watch_nodes_failed","watch_no_phone",
    "watch_https_received","watch_tts_started","watch_tts_queued","watch_response_received","watch_response_ignored","watch_ui_updated","watch_error_visible","watch_resumed",
    "phone_request_received","phone_worker_started","phone_send_invoked","phone_send_submitted",
    "phone_send_failed","phone_send_unconfirmed","phone_https_started","phone_https_received",
    "phone_https_failed","phone_worker_interrupted","phone_watch_trace_received"]);
  // Short-lived, bounded reuse avoids billing duplicate speech for watch retries.
  const voiceCache = new Map();
  const cachedSpeech = (text, voice) => {
    const key = createHash("sha256").update(voice + "\\0" + text).digest("hex");
    const now = Date.now();
    const entry = voiceCache.get(key);
    if (entry && entry.expires > now) return entry.promise;
    if (voiceCache.size >= 8) voiceCache.delete(voiceCache.keys().next().value);
    const promise = Promise.resolve().then(() => synthesizeAudio(text, voice))
      .catch(error => { voiceCache.delete(key); throw error; });
    voiceCache.set(key, { promise, expires: now + 4 * 60 * 1000 });
    return promise;
  };
  const server = http.createServer(async (req, res) => {
    const peer=req.socket.remoteAddress || "unknown";
    if(!limit("ip:"+peer,600)) {reply(res,429,{ok:false,error:"Rate limit; retry shortly"});return;}
    if(!legacyAuth && req.url === "/setup/redeem" && req.method === "POST") {
      if(!limit("pair:"+peer,6)) {reply(res,429,{ok:false,error:"Pairing rate limit"});return;}
      try {
        const b=await readBody(req);
        const code=String(b.code||"").replace(/-/g,"").toUpperCase();
        if(!/^[A-F0-9]{12}$/.test(code)) {reply(res,400,{ok:false,error:"Enter the 12-character pairing code"});return;}
        reply(res,200,{ok:true,url:publicUrl,...store.redeem(code)});
      }catch(e){reply(res,e.status||400,{ok:false,error:e.message});}
      return;
    }
    if (legacyAuth && await handlePairing(req, res, { pairingFile, publicUrl, token })) return;
    if (req.url === "/health" && req.method === "GET") {
      reply(res, 200, { ok: true, service: "hermes-wear-relay", hermes: "not_checked", readiness: "Use authenticated conversation test" });
      return;
    }
    const bearer=String(req.headers.authorization||"").replace(/^Bearer /, "");
    const device=store.authenticate(bearer) || (legacyAuth && equalSecret(bearer,token) ? "legacy" : "");
    if (!device) {
      reply(res, 401, { ok: false, error: "Unauthorized" }); return;
    }
    if(!limit("device:"+device,300)) {reply(res,429,{ok:false,error:"Device rate limit"});return;}
    if(req.url==="/watch/auth" && req.method==="POST") {reply(res,200,{ok:true,deviceId:device,remoteVoice,profile:"hermes-wear"});return;}
    if(req.url==="/watch/rotate" && req.method==="POST" && device!=="legacy") {reply(res,200,{ok:true,token:store.rotate(device)});return;}
    if (req.method !== "POST" ||
        !["/watch/ask", "/watch/status", "/watch/approve", "/watch/tts", "/watch/trace", "/watch/events", "/watch/cancel"].includes(req.url)) {
      reply(res, 404, { ok: false, error: "Not found" }); return;
    }
    try {
      const b = await readBody(req);
      if (req.url === "/watch/events") {
        const row=store.get(device,b.id);
        if(!row) {reply(res,404,{ok:false,error:"Unknown request"});return;}
        reply(res,200,{ok:true,events:store.events(device,b.id,Number(b.after)||0)});return;
      }
      if (req.url === "/watch/trace") {
        if (!ID.test(b.id || "") || !["watch","phone"].includes(b.source) ||
            !Array.isArray(b.events) || b.events.length > 40) {
          reply(res,400,{ok:false,error:"Invalid trace"}); return;
        }
        for (const e of b.events) {
          if (!e || e.id !== b.id || !eventStages.has(e.stage) ||
              !Number.isFinite(e.utcMs) || !Number.isFinite(e.monoMs)) continue;
          const safe = {id:b.id,source:b.source,stage:e.stage,utcMs:e.utcMs,monoMs:e.monoMs};
          for (const key of ["seq","attempt","durationMs","http","count"]) {
            if (Number.isFinite(e[key])) safe[key]=e[key];
          }
          if (typeof e.nearby === "boolean") safe.nearby=e.nearby;
          if (["accepted","working","completed","approval","failed","cancelled","interrupted"].includes(e.state)) safe.state=e.state;
          traceSink(safe);
        }
        reply(res,200,{ok:true}); return;
      }
      trace(b.id, "relay_received", {op:req.url.slice(7)});
      if (req.url === "/watch/tts") {
        if(!remoteVoice) {reply(res,503,{ok:false,error:"Remote speech is disabled; use watch voice"});return;}
        const text = string(b.text, 700);
        const voice = string(b.voice, 32) || "onyx";
        if (!ID.test(b.id || "") || !text || !VOICES.has(voice)) {
          reply(res, 400, {ok:false, error:"Invalid speech request"});
          return;
        }
        const audio = await cachedSpeech(text, voice);
        if (!Buffer.isBuffer(audio) || audio.length < 100 || audio.length > 550000) {
          reply(res, 502, {ok:false, error:"Invalid synthesized audio"});
          return;
        }
        res.writeHead(200, {
          "Content-Type":"audio/mpeg",
          "Content-Length":String(audio.length),
          "Cache-Control":"no-store",
          "X-Content-Type-Options":"nosniff",
        });
        res.end(audio);
      } else if (req.url === "/watch/ask") {
        const text = string(b.text, 2000);
        if (!ID.test(b.id || "") || !ID.test(b.sessionId || "") || !text) {
          reply(res, 400, { ok: false, error: "Valid id, sessionId and nonempty text required (max 2000 chars)" }); return;
        }
        b.text=text;
        const row=store.reserve(device,b);
        if(row.run || (row.result && DONE.has(JSON.parse(row.result).state))) {reply(res,200,JSON.parse(row.result));return;}
        const flightKey=device+":"+b.id;
        const admission=Date.now()>=(retryAfter.get(flightKey)||0) ? admit(device,row) : Promise.resolve(JSON.parse(row.result));
        if(legacyAuth) reply(res,200,await admission);
        else {
          // Ack durable receipt immediately; inference and even native admission are separate.
          const current=store.get(device,b.id);
          const result=current.result ? JSON.parse(current.result) : store.publish(device,b.id,{ok:true,state:"accepted",runId:"",sessionId:b.sessionId,text:""});
          reply(res,202,result);
        }
      } else if (["/watch/status","/watch/cancel"].includes(req.url)) {
        const row=b.runId ? store.byRun(device,b.runId) : store.get(device,b.id);
        if (!row && (!legacyAuth || device!=="legacy")) {reply(res,404,{ok:false,error:"Unknown request"});return;}
        if(req.url==="/watch/cancel" && row && !row.run) {
          reply(res,200,store.publish(device,row.id,{ok:true,state:"cancelled",runId:"",text:"Cancelled"}));return;
        }
        const runId=row?.run || b.runId;
        if (!RUN_ID.test(runId || "")) {reply(res,400,{ok:false,error:"Invalid runId"});return;}
        const cached=row?.result?JSON.parse(row.result):null;
        if(cached && DONE.has(cached.state)) {reply(res,200,cached);return;}
        if(req.url==="/watch/cancel") await hermes("/v1/runs/"+encodeURIComponent(runId)+"/stop","POST",{});
        const run = await hermes("/v1/runs/" + encodeURIComponent(runId));
        if (run.status === "completed") trace(b.id,"hermes_completed_observed");
        const normalized=normalizeRun(run);
        reply(res, 200, row ? store.publish(device,row.id,normalized) : normalized);
      } else {
        if (!RUN_ID.test(b.runId || "") || !string(b.requestId, 256) ||
            !Number.isInteger(b.generation) || b.generation < 0 ||
            !["once", "deny"].includes(b.choice)) {
          reply(res, 400, { ok: false, error: "Exact approval identity and once/deny required" }); return;
        }
        const owned=store.byRun(device,b.runId);
        if(!owned && (!legacyAuth || device!=="legacy")) {reply(res,404,{ok:false,error:"Unknown request"});return;}
        const current = await hermes("/v1/runs/" + encodeURIComponent(b.runId));
        if (current.status !== "waiting_for_approval") {
          reply(res, 409, { ok: false, error: "Run is not waiting for approval" }); return;
        }
        const a = approvalOf(current);
        if (!a || a.requestId !== b.requestId || a.generation !== b.generation) {
          reply(res, 409, { ok: false, error: "Approval request expired or changed" }); return;
        }
        await hermes("/v1/runs/" + encodeURIComponent(b.runId) + "/approval", "POST", {
          request_id: b.requestId, execution_generation: b.generation, choice: b.choice
        });
        reply(res, 200, { ok: true, state: "working", runId: b.runId, text: "Decision submitted" });
      }
    } catch (error) {
      reply(res, error.status || 500, { ok: false, error: error.message || "Unexpected relay error" });
    }
  });
  server.requestTimeout=15000; server.headersTimeout=10000; server.maxHeadersCount=32;
  let recovery;
  const monitors=new Map(),nextObserve=new Map();
  server.on("listening",()=>{
    if(!legacyAuth) recovery=setInterval(()=>{
      for(const row of store.pending()) {
        if(flights.size>=4)break;
        const key=row.device+":"+row.id;
        if(!flights.has(key) && Date.now()>=(retryAfter.get(key)||0)) void admit(row.device,row);
      }
      for(const row of store.active()) {
        if(monitors.size>=4)break;
        if(monitors.has(row.run) || Date.now()<(nextObserve.get(row.run)||0))continue;
        nextObserve.set(row.run,Date.now()+5000);
        const job=hermes("/v1/runs/"+encodeURIComponent(row.run)).then(raw=>{
          store.publish(row.device,row.id,normalizeRun(raw));
          if(DONE.has(raw.status)) {trace(row.id,"hermes_completed_cached");nextObserve.delete(row.run);}
        }).catch(()=>{}).finally(()=>monitors.delete(row.run));
        monitors.set(row.run,job);
      }
    },1000).unref();
  });
  server.on("close",()=>{clearInterval(recovery);void Promise.allSettled([...flights.values(),...monitors.values()]).then(()=>store.close());});
  return server;
}
if (process.argv[1] && import.meta.url === new URL("file://" + process.argv[1]).href) {
  const port = Number(process.env.WATCH_RELAY_PORT || "8796");
  try {
    if (!process.env.WATCH_STORE_PATH || process.env.WATCH_STORE_PATH === ":memory:") throw new Error("Set WATCH_STORE_PATH to a persistent SQLite file");
    const app = createApp({
      token: process.env.WATCH_RELAY_TOKEN,
      hermesKey: process.env.HERMES_API_KEY,
      hermesUrl: process.env.HERMES_API_URL || "http://127.0.0.1:8642",
      watchProvider: process.env.WATCH_HERMES_PROVIDER || "",
      watchModel: process.env.WATCH_HERMES_MODEL || "",
      pairingFile: process.env.WATCH_PAIRING_FILE || "",
      publicUrl: process.env.WATCH_RELAY_PUBLIC_URL || "",
      storePath: process.env.WATCH_STORE_PATH || ":memory:",
      legacyAuth: false,
      remoteVoice: false
    });
    app.listen(port, "127.0.0.1", () => {
      process.stdout.write("Hermes Wear relay listening on localhost:" + port + "\n");
    });
  } catch (error) {
    process.stderr.write(error.message + "\n"); process.exitCode = 1;
  }
}
