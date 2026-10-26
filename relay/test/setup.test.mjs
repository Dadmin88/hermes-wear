import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../server.mjs";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { once } from "node:events";

test("fixed setup page and one-time code", async t => {
  const dir = mkdtempSync(join(tmpdir(), "hw-"));
  const file = join(dir, "pair.json");
  writeFileSync(file, JSON.stringify({
    ticket: "Z".repeat(43),
    setupCode: "ABCDEF123456",
    expiresAt: Date.now() + 60000
  }));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const origin = "https://relay.example.com";
  const server = createApp({
    token: "w".repeat(36),
    hermesKey: "h".repeat(36),
    pairingFile: file,
    publicUrl: origin
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());
  const base = "http://127.0.0.1:" + server.address().port;
  const root = await fetch(base + "/", { redirect: "manual" });
  assert.equal(root.status, 200);
  assert.match(await root.text(), /One-time pairing code/);
  const page = await fetch(base + "/setup");
  assert.equal(page.status, 200);
  assert.match(await page.text(), /One-time pairing code/);
  const truncated = await fetch(base + "/pair/old-invalid-link");
  assert.equal(truncated.status, 200);
  assert.match(await truncated.text(), /One-time pairing code/);
  const query = await fetch(base + "/?refresh=2");
  assert.equal(query.status, 200);
  assert.match(await query.text(), /One-time pairing code/);
  const protectedApi = await fetch(base + "/watch/status");
  assert.equal(protectedApi.status, 401);
  const redeem = code => fetch(base + "/setup/redeem", {
    method: "POST",
    headers: { "content-type": "application/json", origin },
    body: JSON.stringify({ code })
  });
  assert.equal((await redeem("000000000000")).status, 403);
  const valid = await redeem("abcdef123456");
  assert.equal(valid.status, 200);
  assert.equal((await valid.json()).url, origin);
  assert.equal((await redeem("ABCDEF123456")).status, 410);
});

test("failed attempts reset on new code; direct the relay host browser origin is allowed", async t => {
  const dir = mkdtempSync(join(tmpdir(), "hw-rotate-"));
  const file = join(dir, "pair.json");
  const writeCode = (ticket, setupCode) => writeFileSync(file, JSON.stringify({
    ticket, setupCode, expiresAt: Date.now() + 60000
  }));
  writeCode("A".repeat(43), "ABCDEF123456");
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const server = createApp({
    token: "w".repeat(36),
    hermesKey: "h".repeat(36),
    pairingFile: file,
    publicUrl: "https://relay.example.com"
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());
  const base = "http://127.0.0.1:" + server.address().port;
  const redeem = code => fetch(base + "/setup/redeem", {
    method: "POST",
    headers: { "content-type": "application/json", origin: base },
    body: JSON.stringify({ code })
  });
  for (let i = 0; i < 6; i++) {
    assert.equal((await redeem("000000000000")).status, 403);
  }
  assert.equal((await redeem("ABCDEF123456")).status, 429);
  writeCode("B".repeat(43), "123456ABCDEF");
  const valid = await redeem("123456ABCDEF");
  assert.equal(valid.status, 200);
  assert.equal((await valid.json()).ok, true);
  assert.equal((await redeem("123456ABCDEF")).status, 410);
});

test("cross-origin pairing is rejected, without consuming valid code", async t => {
  const dir = mkdtempSync(join(tmpdir(), "hw-origin-"));
  const file = join(dir, "pair.json");
  writeFileSync(file, JSON.stringify({
    ticket: "C".repeat(43), setupCode: "FACEB00C1234", expiresAt: Date.now() + 60000
  }));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const origin = "https://relay.example.com";
  const server = createApp({
    token: "w".repeat(36),
    hermesKey: "h".repeat(36),
    pairingFile: file,
    publicUrl: origin
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());
  const base = "http://127.0.0.1:" + server.address().port;
  const redeem = fromOrigin => fetch(base + "/setup/redeem", {
    method: "POST",
    headers: { "content-type": "application/json", origin: fromOrigin },
    body: JSON.stringify({ code: "FACEB00C1234" })
  });
  const blocked = await redeem("https://attacker.invalid");
  assert.equal(blocked.status, 403);
  assert.match((await blocked.json()).error, /setup page/);
  const allowed = await redeem(origin);
  assert.equal(allowed.status, 200);
});
