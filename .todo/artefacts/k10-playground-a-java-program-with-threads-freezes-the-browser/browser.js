// node browser.js <dist> <script.js> : serves <dist>, opens headless Chrome, runs the scenario
// script's default export `async (page) => ...` and prints what it returns. Everything this
// starts (the server, the browser) is stopped at the end.
const http = require("http"), fs = require("fs"), path = require("path"), { spawn } = require("child_process");
const [dist, scenarioFile] = process.argv.slice(2);
const CHROME = process.env.CHROME || path.join(process.env.HOME, ".cache/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-linux64/chrome-headless-shell");
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".wasm": "application/wasm", ".css": "text/css", ".json": "application/json", ".png": "image/png", ".ico": "image/x-icon" };
const isolated = process.env.ISOLATED === "1";
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, "http://x").pathname);
  if (p.endsWith("/")) p += "index.html";
  const file = path.join(dist, p);
  if (!file.startsWith(path.resolve(dist)) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end("no"); return; }
  const headers = { "content-type": TYPES[path.extname(file)] || "application/octet-stream" };
  if (isolated) { headers["cross-origin-opener-policy"] = "same-origin"; headers["cross-origin-embedder-policy"] = "require-corp"; }
  res.writeHead(200, headers);
  fs.createReadStream(file).pipe(res);
});
let chrome = null;
function stop(code) {
  try { if (chrome) chrome.kill("SIGKILL"); } catch (e) {}
  server.close();
  process.exit(code);
}
server.listen(0, "127.0.0.1", async () => {
  const base = "http://127.0.0.1:" + server.address().port;
  const profile = fs.mkdtempSync(path.join(require("os").tmpdir(), "ronto-chrome-"));
  chrome = spawn(CHROME, ["--remote-debugging-port=0", "--user-data-dir=" + profile, "--no-sandbox", "--disable-gpu", "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const wsUrl = await new Promise((resolve, reject) => {
    let buf = "";
    chrome.stderr.on("data", (d) => { buf += d; const m = /DevTools listening on (ws:\/\/\S+)/.exec(buf); if (m) resolve(m[1]); });
    chrome.on("exit", () => reject(new Error("chrome exited: " + buf)));
    setTimeout(() => reject(new Error("chrome did not start: " + buf)), 20000);
  });
  const ws = new WebSocket(wsUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  let seq = 0; const pending = new Map(); const events = [];
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id !== undefined && pending.has(m.id)) { const { resolve, reject } = pending.get(m.id); pending.delete(m.id); m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result); }
    else events.push(m);
  };
  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params, sessionId })); });
  const { targetId } = await send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await send("Target.attachToTarget", { targetId, flatten: true });
  await send("Page.enable", {}, sessionId);
  await send("Runtime.enable", {}, sessionId);
  const page = {
    base,
    goto: async (p) => { await send("Page.navigate", { url: base + p }, sessionId); await page.waitFor("document.readyState === 'complete'", 30000); },
    // Evaluates an expression on the page; rejects with "FROZEN" when the page's thread does not answer in time.
    eval: (expression, limit = 10000) => Promise.race([
      send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true }, sessionId).then((r) => { if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception ? r.exceptionDetails.exception.description : r.exceptionDetails.text); return r.result.value; }),
      new Promise((_, reject) => setTimeout(() => reject(new Error("FROZEN: the page did not answer in " + limit + " ms")), limit)),
    ]),
    waitFor: async (expression, limit) => { const end = Date.now() + limit; for (;;) { const v = await page.eval(expression, Math.max(5000, Math.min(limit, 15000))); if (v) return v; if (Date.now() > end) throw new Error("TIMEOUT waiting for " + expression); await new Promise((r) => setTimeout(r, 100)); } },
    console: () => events.filter((m) => m.method === "Runtime.consoleAPICalled" || m.method === "Runtime.exceptionThrown").map((m) => JSON.stringify(m.params).slice(0, 400)),
  };
  try {
    const out = await require(path.resolve(scenarioFile))(page);
    console.log(typeof out === "string" ? out : JSON.stringify(out, null, 1));
    stop(0);
  } catch (e) {
    console.log("SCENARIO FAILED: " + e.message);
    for (const line of page.console().slice(-5)) console.log("  console: " + line);
    stop(1);
  }
});
