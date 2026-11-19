// node page.js <dist> <markdown-file> : runs the page's Run cells in order, as docs.js does.
const fs = require("fs"), vm = require("vm"), path = require("path");
const [dist, file] = process.argv.slice(2);
const js = path.join(dist, "rontoplayground.js");
const wasm = path.join(dist, "rontoplayground.js.wasm");
globalThis.fetch = async () => new Response(fs.readFileSync(wasm), { headers: { "content-type": "application/wasm" } });
vm.runInThisContext(fs.readFileSync(js, "utf8"), { filename: js });
const config = new GraalVM.Config();
config.wasm_path = wasm;
const md = fs.readFileSync(file, "utf8");
const javaPage = file.includes("/java/");
const blocks = [];
const re = /^```(\w*)\n([\s\S]*?)^```$/gm;
let m;
while ((m = re.exec(md))) blocks.push({ lang: m[1], text: m[2], line: md.slice(0, m.index).split("\n").length });
GraalVM.run([], config).then(() => {
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    if (!["lisp", "scheme", "clojure", "starlark", "java"].includes(b.lang)) continue;
    if (b.lang === "java" && !javaPage) continue;
    if (b.lang === "scheme" && /^;+ file: /.test(b.text)) { globalThis.rontoPutFile(/^;+ file: (\S+)/.exec(b.text)[1], b.text); continue; }
    const stdin = i > 0 && blocks[i - 1].lang === "stdin" ? blocks[i - 1].text : "";
    if ((process.env.SKIP || "").split(",").includes(String(b.line))) continue;
    process.stdout.write("START " + b.lang + " line " + b.line + "\n");
    globalThis.rontoSetLanguage(b.lang);
    const t = Date.now();
    let res;
    if (b.lang === "lisp") res = globalThis.rontoEval(b.text);
    else if (b.lang !== "starlark" && b.lang !== "java" && b.text.indexOf("; =>") >= 0) res = globalThis.rontoRunSession(b.text, stdin);
    else res = globalThis.rontoRunProgram(b.text, stdin);
    const expected = i + 1 < blocks.length && blocks[i + 1].lang === "" ? blocks[i + 1].text : null;
    const program = b.lang === "starlark" || b.lang === "java" || (b.lang !== "lisp" && b.text.indexOf("; =>") < 0);
    let verdict = "ran";
    if (typeof res === "string" && res.startsWith("ERROR:")) verdict = "ERROR " + JSON.stringify(res.slice(0, 300));
    else if (program && expected !== null && res !== expected) verdict = "DIFF got " + JSON.stringify(String(res).slice(0, 300));
    process.stdout.write("END " + (Date.now() - t) + " ms " + verdict + "\n");
  }
  process.exit(0);
}).catch((e) => { console.log("FAILED " + e); process.exit(2); });
