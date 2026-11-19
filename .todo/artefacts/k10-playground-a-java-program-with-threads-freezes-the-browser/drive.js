// node drive.js <dist> <lang> <fn> <source-file> [stdin]
const fs = require("fs"), vm = require("vm"), path = require("path");
const [dist, lang, fn, file] = process.argv.slice(2);
const js = path.join(dist, "rontoplayground.js");
const wasm = path.join(dist, "rontoplayground.js.wasm");
globalThis.fetch = async (p) => new Response(fs.readFileSync(wasm), { headers: { "content-type": "application/wasm" } });
vm.runInThisContext(fs.readFileSync(js, "utf8"), { filename: js });
const config = new GraalVM.Config();
config.wasm_path = wasm;
const t0 = Date.now();
GraalVM.run([], config).then(() => {
  const t1 = Date.now();
  console.error("runtime ready in " + (t1 - t0) + " ms");
  const src = fs.readFileSync(file, "utf8");
  console.error(globalThis.rontoSetLanguage(lang));
  const res = fn === "rontoRunProgram" ? globalThis.rontoRunProgram(src, "") : globalThis[fn](src);
  console.error(fn + " took " + (Date.now() - t1) + " ms");
  console.log(typeof res === "string" && res.length > 2000 ? res.slice(0, 200) + "... (" + res.length + " chars)" : res);
}).catch((e) => { console.error("FAILED", e); process.exit(2); });
