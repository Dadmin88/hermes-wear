import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp, normalizeRun } from "../server.mjs";

const secret = "s".repeat(36);
const hermesKey = "h".repeat(36);

async function start(upstream, opts = {}) {
  const server = createApp({ token: secret, hermesKey, upstream, ...opts });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return { server, base: "http://127.0.0.1:" + server.address().port };
}
const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { "content-type": "application/json" }
});
async function post(base, path, body, authorized = true) {
  const headers = { "Content-Type": "application/json" };
  if (authorized) headers.Authorization = "Bearer " + secret;
  const r = await fetch(base + path, { method: "POST", headers, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json() };
}

test("relay requires token and validates request identity", async (t) => {
  const { server, base } = await start(() => json({}));
  t.after(() => server.close());
  assert.equal((await post(base, "/watch/ask", { text: "Hi" }, false)).status, 401);
  assert.equal((await post(base, "/watch/ask", { id: "small", sessionId: "validuuid123", text: "Hi" })).status, 400);
  const r = await fetch(base + "/health");
  assert.equal((await r.json()).ok, true);
});

test("ask forwards named session and Hermes native Idempotency-Key", async (t) => {
  const calls = [];
  const { server, base } = await start(async (url, init) => {
    calls.push({ url, init }); return json({ run_id: "run_aabbccddeeff", status: "started" }, 202);
  });
  t.after(() => server.close());
  const request = { id: "aabbccdd-1111-2222", sessionId: "01234567-3333-4444", text: "Test" };
  const r1 = await post(base, "/watch/ask", request);
  const r2 = await post(base, "/watch/ask", request);
  assert.equal(r1.status, 200);
  assert.equal(r1.body.runId, "run_aabbccddeeff");
  assert.equal(r2.body.runId, r1.body.runId);
  assert.equal(calls[0].init.headers["Idempotency-Key"], "hermes-wear-" + request.id);
  assert.equal(calls.length, 1); // durable relay replay avoids a second upstream admission
  assert.equal(JSON.parse(calls[0].init.body).session_id, "wear-" + request.sessionId);
  assert.equal(JSON.parse(calls[0].init.body).input, "Test");
});

test("status reports response and approval prompt identities", async (t) => {
  const upstream = async () => json({
    run_id: "run_aabbccddeeff", status: "waiting_for_approval", session_id: "wear-one",
    pending_controls: [{ kind: "approval", prompt_id: "pr_123", execution_generation: 4,
      description: "Run a file edit?" }]
  });
  const { server, base } = await start(upstream);
  t.after(() => server.close());
  const r = await post(base, "/watch/status", { runId: "run_aabbccddeeff" });
  assert.equal(r.body.state, "approval");
  assert.equal(r.body.approval.requestId, "pr_123");
  assert.equal(r.body.approval.generation, 4);
  assert.equal(normalizeRun({ status: "completed", output: "Done" }).text, "Done");
});

test("approval sends only exact once or deny decision; stale request rejected", async (t) => {
  const requests = [];
  const upstream = async (url, opts) => {
    requests.push({ url, opts });
    if (url.endsWith("/approval")) return json({ resolved: 1 });
    return json({ run_id: "run_abc123", status: "waiting_for_approval",
      pending_controls: [{ prompt_id: "prompt_one", execution_generation: 3, kind: "approval", prompt: "Edit?" }] });
  };
  const { server, base } = await start(upstream);
  t.after(() => server.close());
  const b = { runId: "run_abc123", requestId: "prompt_one", generation: 3, choice: "once" };
  assert.equal((await post(base, "/watch/approve", b)).status, 200);
  assert.equal(JSON.parse(requests[1].opts.body).choice, "once");
  assert.equal(JSON.parse(requests[1].opts.body).request_id, "prompt_one");
  assert.equal(JSON.parse(requests[1].opts.body).execution_generation, 3);
  assert.equal((await post(base, "/watch/approve", { ...b, generation: 2 })).status, 409);
  assert.equal(requests.filter(x => x.url.endsWith("/approval")).length, 1);
  assert.equal((await post(base, "/watch/approve", { ...b, choice: "always" })).status, 400);
});

test("watch-only provider override leaves global Hermes routing untouched", async (t) => {
  let payload;
  const { server, base } = await start(async (_url, init) => {
    payload = JSON.parse(init.body);
    return json({ run_id: "run_model_override" }, 202);
  }, { watchProvider: "openai-codex", watchModel: "gpt-5.6-sol" });
  t.after(() => server.close());
  const r = await post(base, "/watch/ask", { id: "request-abc123", sessionId: "session-abc123", text: "Hello" });
  assert.equal(r.status, 200);
  assert.equal(payload.provider, "openai-codex");
  assert.equal(payload.model, "gpt-5.6-sol");
});

test("private pairing ticket redeems once and is not embedded in HTML", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "hermes-wear-pair-"));
  const file = join(dir,"ticket.json");
  const ticket = "A".repeat(40);
  writeFileSync(file, JSON.stringify({ticket, expiresAt: Date.now() + 60000}), {mode:0o600});
  t.after(() => rmSync(dir, {recursive: true, force:true}));
  const publicUrl = "https://relay.example.com";
  const {server,base} = await start(async () => json({}), {pairingFile:file,publicUrl});
  t.after(() => server.close());
  const page = await fetch(base + "/pair/" + ticket);
  assert.equal(page.status, 200);
  const html = await page.text();
  assert.equal(html.includes(secret), false);
  const denied = await fetch(base+"/pair/redeem", {
    method:"POST",headers:{"content-type":"application/json","origin":"https://evil.example"},
    body:JSON.stringify({ticket})
  });
  assert.equal(denied.status,403);
  const good = await fetch(base+"/pair/redeem", {
    method:"POST",headers:{"content-type":"application/json","origin":publicUrl},
    body:JSON.stringify({ticket})
  });
  assert.equal(good.status,200);
  const data = await good.json();
  assert.equal(data.url,publicUrl);
  assert.equal(data.token,secret);
  const replay = await fetch(base+"/pair/redeem", {
    method:"POST",headers:{"content-type":"application/json","origin":publicUrl},
    body:JSON.stringify({ticket})
  });
  assert.equal(replay.status,404);
});

test("managed speech endpoint is authenticated, binary, and caches retries", async (t) => {
  const sample = Buffer.concat([Buffer.from("ID3"), Buffer.alloc(2048, 5)]);
  let calls = 0;
  const {server,base} = await start(() => json({}), {
    synthesizeAudio: async (text,voice) => {
      calls++;
      assert.equal(text,"Hello Hermes");
      assert.equal(voice,"onyx");
      return sample;
    }
  });
  t.after(() => server.close());
  const body = {id:"tts-request-12345",text:"Hello Hermes",voice:"onyx"};
  const endpoint = base+"/watch/tts";
  const unauthorized = await fetch(endpoint, {
    method:"POST", headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body)
  });
  assert.equal(unauthorized.status,401);
  async function ask(data) {
    return fetch(endpoint, {
      method:"POST",
      headers:{"Content-Type":"application/json","Authorization":"Bearer "+secret},
      body:JSON.stringify(data)
    });
  }
  const a=await ask(body);
  assert.equal(a.status,200);
  assert.equal(a.headers.get("content-type"),"audio/mpeg");
  assert.deepEqual(Buffer.from(await a.arrayBuffer()),sample);
  const b=await ask(body);
  assert.equal(b.status,200);
  assert.equal(calls,1);
  assert.equal((await ask({...body,voice:"unsupported"})).status,400);
  assert.equal((await ask({...body,text:"x".repeat(701)})).status,400);
});

test("Hermes unreachable fails closed", async (t) => {
  const { server, base } = await start(async () => { throw new Error("down"); });
  t.after(() => server.close());
  const r = await post(base, "/watch/status", { runId: "run_abc123" });
  assert.equal(r.status, 502);
  assert.equal(r.body.ok, false);
});
