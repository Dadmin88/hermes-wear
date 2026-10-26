import { readFileSync, unlinkSync } from "node:fs";
import { timingSafeEqual } from "node:crypto";

const MAX_PAIR_BODY = 2048;
const VALID_TICKET = /^[A-Za-z0-9_-]{32,100}$/;
const headers = {
  "Cache-Control": "no-store, max-age=0",
  "Pragma": "no-cache",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
};
function json(res, status, obj) {
  res.writeHead(status, { ...headers, "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(obj));
}
function same(a, b) {
  const aa = Buffer.from(a ?? "");
  const bb = Buffer.from(b ?? "");
  return aa.length === bb.length && timingSafeEqual(aa, bb);
}
function ticketFrom(filePath) {
  if (!filePath) return null;
  try {
    const data = JSON.parse(readFileSync(filePath, "utf8"));
    if (!VALID_TICKET.test(data.ticket) || !Number.isFinite(data.expiresAt)
        || data.expiresAt <= Date.now()) return null;
    return data;
  } catch { return null; }
}
async function body(req) {
  let result = "";
  for await (const chunk of req) {
    result += chunk.toString("utf8");
    if (result.length > MAX_PAIR_BODY) return null;
  }
  try { return JSON.parse(result); } catch { return null; }
}
function page(ticket) {
  const code = JSON.stringify(ticket);
  return `<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair Hermes Bridge</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{background:#071714;color:#e6f4ef;margin:0;padding:24px;font-family:system-ui,-apple-system,sans-serif}
main{max-width:510px;margin:8vh auto}h1{color:#8df0bf;margin:0 0 8px;font-size:29px}p{line-height:1.5;color:#c5d4cf}
button{background:#70e1ae;color:#061712;border:0;border-radius:12px;padding:15px 18px;font-size:17px;font-weight:700;cursor:pointer;min-height:48px}
.value{overflow-wrap:anywhere;background:#142a24;padding:14px;border-radius:12px;user-select:all;font-family:monospace}
.field{margin-top:24px}small{color:#96b7a9}#details{display:none}#error{color:#ffb7b7}
</style></head>
<body><main><h1>✦ HERMES / WEAR OS</h1><p>This private, single-use setup page connects your Galaxy phone to the relay host. The pairing link expires soon and can be redeemed only once.</p>
<button id="reveal" type="button">Reveal connection details</button>
<p id="error" role="alert"></p>
<section id="details" aria-live="polite">
<div class="field"><b>Relay URL</b><p id="url" class="value"></p><button type="button" id="copyUrl">Copy URL</button></div>
<div class="field"><b>Relay access token</b><p id="token" class="value"></p><button type="button" id="copyToken">Copy token</button></div>
<p>Open <b>Hermes Bridge</b>. Paste each value into its matching field, press <b>Save connection</b>, then <b>Test relay</b>.</p>
<small>The token is a private credential. Don't post it in chat or screenshots. This page cannot be reopened after revealing it.</small>
</section></main>
<script>
const ticket = ${code};
const el = id => document.getElementById(id);
el('reveal').onclick = async () => {
  el('reveal').disabled = true;
  try {
    const response = await fetch('/pair/redeem', {
      method:'POST',headers:{'content-type':'application/json'},credentials:'omit',
      cache:'no-store',body:JSON.stringify({ticket})
    });
    const obj = await response.json();
    if (!response.ok || !obj.ok) throw new Error('The link expired or was already used. Request a new pairing link.');
    el('url').textContent = obj.url;
    el('token').textContent = obj.token;
    el('details').style.display = 'block';
    el('reveal').style.display = 'none';
  } catch(e) { el('error').textContent = e.message; el('reveal').disabled = false; }
};
async function copy(id) {
  const value = el(id).textContent;
  try { await navigator.clipboard.writeText(value); }
  catch { window.prompt('Copy this value:', value); }
}
el('copyUrl').onclick=()=>copy('url');
el('copyToken').onclick=()=>copy('token');
</script></body></html>`;
}
const SETUP_CODE = /^[A-F0-9]{12}$/;
function allowedSetupOrigin(req, publicUrl) {
  if (req.headers.origin === publicUrl) return true;
  // A page opened directly on the relay host (without Tailscale Serve) is also valid.
  const remote = req.socket?.remoteAddress || "";
  if (!["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(remote)) return false;
  try {
    const local = new URL(req.headers.origin);
    return local.protocol === "http:" &&
      ["127.0.0.1", "localhost"].includes(local.hostname) &&
      local.host === req.headers.host;
  } catch { return false; }
}
let failedSetupAttempts = 0;
let lastAttemptTicket = "";

function setupPage() {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Set up Hermes Bridge</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}body{background:#071714;color:#e6f4ef;margin:0;padding:22px;font-family:system-ui,sans-serif}
main{max-width:500px;margin:6vh auto}h1{color:#8df0bf;font-size:28px}p{line-height:1.5;color:#c5d4cf}
input{width:100%;font-size:22px;letter-spacing:2px;text-transform:uppercase;border-radius:12px;padding:14px;background:#182d28;border:1px solid #719b88;color:white}
button{background:#70e1ae;color:#061712;border:0;border-radius:12px;padding:14px 17px;font-size:17px;font-weight:700;min-height:48px;margin-top:14px}
.value{overflow-wrap:anywhere;background:#142a24;padding:14px;border-radius:12px;user-select:all;font-family:monospace}
.field{margin-top:23px}small{color:#96b7a9}#details{display:none}#error{color:#ffb7b7}
</style></head>
<body><main><h1>✦ HERMES / WEAR OS</h1>
<p>Enter the 12-character setup code shown on the relay host. No long URL to copy. This private page works over Tailscale.</p>
<label for="code"><b>One-time pairing code</b></label>
<input id="code" maxlength="14" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="A1B2C3D4E5F6">
<button id="connect" type="button">Connect Hermes Bridge</button><p id="error" role="alert"></p>
<section id="details" aria-live="polite">
<div class="field"><b>Relay URL</b><p id="url" class="value"></p><button type="button" id="copyUrl">Copy URL</button></div>
<div class="field"><b>Relay access token</b><p id="token" class="value"></p><button type="button" id="copyToken">Copy token</button></div>
<p>Paste both values into <b>Hermes Bridge</b>, then tap <b>Save connection</b> and <b>Test relay</b>.</p>
<small>Keep the token private. This setup code can be used once only.</small>
</section></main>
<script>
const el = id => document.getElementById(id);
el('connect').onclick = async () => {
  const code = el('code').value.trim().replaceAll('-', '').toUpperCase();
  if(!/^[A-F0-9]{12}$/.test(code)) { el('error').textContent='Enter the 12 characters shown on the relay host.'; return; }
  el('connect').disabled = true;
  try {
    const response = await fetch('/setup/redeem', {
      method:'POST', headers:{'content-type':'application/json'}, credentials:'omit',
      cache:'no-store', body:JSON.stringify({code})
    });
    const obj = await response.json();
    if(!response.ok || !obj.ok) throw new Error(obj.error || 'Pairing failed. Check the relay host.');
    el('url').textContent=obj.url;
    el('token').textContent=obj.token;
    el('code').value='';
    el('details').style.display='block';
    el('connect').style.display='none';
  } catch(e) { el('error').textContent=e.message; el('connect').disabled=false; }
};
async function copy(id) {
  const value=el(id).textContent;
  try { await navigator.clipboard.writeText(value); }
  catch { window.prompt('Copy:', value); }
}
el('copyUrl').onclick=()=>copy('url');
el('copyToken').onclick=()=>copy('token');
</script></body></html>`;
}

/** Serve only an expiring one-time ticket, never credentials in a public page. */
export async function handlePairing(req, res, { pairingFile = "", publicUrl = "", token = "" } = {}) {
  if (!pairingFile || !publicUrl) return false;
  const route = req.url || "";
  if (req.method === "GET" && (route === "/health" || route.startsWith("/watch/"))) return false;
  if (req.method !== "GET" && !route.startsWith("/pair/") && route !== "/setup/redeem") return false;
  const source = ticketFrom(pairingFile);
  // Each freshly generated pairing ticket has its own attempt budget.
  // Previously a lockout survived issuing a new code until the process restarted.
  if (source?.ticket !== lastAttemptTicket) {
    failedSetupAttempts = 0;
    lastAttemptTicket = source?.ticket || "";
  }
  if (req.method === "GET" && route === "/") {
    res.writeHead(200, { ...headers, "Content-Type": "text/html; charset=utf-8" });
    res.end(setupPage());
    return true;
  }
  if (req.method === "GET" && route === "/setup") {
    res.writeHead(200, { ...headers, "Content-Type": "text/html; charset=utf-8" });
    res.end(setupPage());
    return true;
  }
  if (req.method === "GET" && /^\/pair\/[A-Za-z0-9_-]{32,100}$/.test(req.url)) {
    const ticket = req.url.slice("/pair/".length);
    if (!source || !same(source.ticket,ticket)) {
      res.writeHead(200, { ...headers, "Content-Type": "text/html; charset=utf-8" });
      res.end(setupPage());
      return true;
    }
    res.writeHead(200, {...headers,"Content-Type":"text/html; charset=utf-8"});
    res.end(page(ticket));
    return true;
  }
  if (req.method === "GET") {
    res.writeHead(200, { ...headers, "Content-Type": "text/html; charset=utf-8" });
    res.end(setupPage());
    return true;
  }
  if (req.method === "POST" && route === "/setup/redeem") {
    if (!allowedSetupOrigin(req, publicUrl)) {
      json(res, 403, {ok:false,error:"Open the setup page directly on the relay host or through the Tailscale HTTPS address."}); return true;
    }
    const payload = await body(req);
    const code = typeof payload?.code === "string"
      ? payload.code.trim().replace(/-/g, "").toUpperCase() : "";
    if (!source) {
      json(res, 410, {ok:false,error:"The pairing code is no longer active. Generate a fresh code on the relay host."});
      return true;
    }
    if (!SETUP_CODE.test(code)) {
      json(res, 400, {ok:false,error:"Enter exactly 12 hexadecimal characters."});
      return true;
    }
    if (failedSetupAttempts >= 6) {
      json(res, 429, {ok:false,error:"Too many attempts for this code. Generate a new code on the relay host."});
      return true;
    }
    if (!SETUP_CODE.test(source.setupCode || "") || !same(source.setupCode, code)) {
      failedSetupAttempts++;
      json(res, 403, {ok:false,error:"This code does not match the current code generated on the relay host."});
      return true;
    }
    try { unlinkSync(pairingFile); }
    catch { json(res,503,{ok:false,error:"Pairing could not complete"});return true; }
    failedSetupAttempts = 0;
    json(res,200,{ok:true,url:publicUrl,token});
    return true;
  }
  if (req.method === "POST" && req.url === "/pair/redeem") {
    if (req.headers.origin !== publicUrl) {
      json(res,403,{ok:false,error:"Invalid pairing request"});return true;
    }
    const payload = await body(req);
    if (!source || !payload || typeof payload.ticket !== "string" || !same(source.ticket,payload.ticket)) {
      json(res,404,{ok:false,error:"Pairing link unavailable"});return true;
    }
    try { unlinkSync(pairingFile); }
    catch { json(res,503,{ok:false,error:"Pairing could not complete"}); return true; }
    json(res,200,{ok:true,url:publicUrl,token});
    return true;
  }
  json(res,404,{ok:false,error:"Not found"});
  return true;
}
