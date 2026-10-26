import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// Source-level invariant: the watch must not use the chat pending lane for
// background speech. Hardware/Data Layer tests still required.
const source = readFileSync(
  new URL("../../wear/src/main/java/io/github/hermeswear/MainActivity.kt", import.meta.url),
  "utf8"
);
const section = (a,b) => {
  const begin=source.indexOf(a), end=source.indexOf(b,begin+a.length);
  assert.ok(begin>=0 && end>begin, "Expected source region present");
  return source.slice(begin,end);
};
test("Nous speech sends independently of pending chat", () => {
  const voice = section("private fun requestCloudVoice","private fun fallbackFromNous");
  assert.doesNotMatch(voice, /request\("tts"/);
  assert.doesNotMatch(voice, /pending\s*=(?!=)/);
  assert.match(voice, /sendMessage\(/);
});
test("chat send guards pending before clearing typed message", () => {
  const chat=section("private fun ask(","private fun decide(");
  assert.ok(chat.indexOf("if(pending!=null)")>=0);
  assert.ok(chat.indexOf('entry.setText("")')>chat.indexOf("if(pending!=null)"));
});

test("run polling is owned by durable phone work", () => {
  assert.doesNotMatch(source, /request\("status"/);
  const phone = readFileSync(new URL("../../mobile/src/main/java/io/github/hermeswear/PhoneListenerService.kt", import.meta.url), "utf8");
  assert.match(phone, /PhoneTurns.submit/);
  const worker = readFileSync(new URL("../../mobile/src/main/java/io/github/hermeswear/ChatWorker.kt", import.meta.url), "utf8");
  assert.match(worker, /:Worker\(/);
});

test("phone bridge keeps cloud voice off the chat worker pool", () => {
  const mobile = readFileSync(
    new URL("../../mobile/src/main/java/io/github/hermeswear/PhoneListenerService.kt", import.meta.url),
    "utf8"
  );
  assert.match(mobile, /private val chatWorkers = Executors\.newFixedThreadPool\(2\)/);
  assert.match(mobile, /private val speechWorkers = ThreadPoolExecutor\(/);
  assert.match(mobile, /if \(op == "tts"\) speechWorkers else chatWorkers/);
  assert.match(mobile, /speechWorkers\.shutdownNow\(\)/);
});

test("only the durable inbox owns normal response dispatch", () => {
  const listener=section("override fun onMessageReceived(event:", "private fun handle(value:");
  assert.doesNotMatch(listener, /handle\(/);
  assert.match(listener, /cloudRequestId/);
  assert.match(listener, /hermes-wear\/audio/);
});
