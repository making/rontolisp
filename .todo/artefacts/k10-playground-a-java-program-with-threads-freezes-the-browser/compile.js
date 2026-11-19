// node compile.js <dist> <lang> <file> <out.wasm> : the playground's Compile to WASM, written to a file.
const fs = require("fs"), vm = require("vm"), path = require("path");
const [dist, lang, file, out] = process.argv.slice(2);
const js = path.join(dist, "rontoplayground.js"), wasm = path.join(dist, "rontoplayground.js.wasm");
globalThis.fetch = async () => new Response(fs.readFileSync(wasm), { headers: { "content-type": "application/wasm" } });
vm.runInThisContext(fs.readFileSync(js, "utf8"), { filename: js });
const config = new GraalVM.Config(); config.wasm_path = wasm;
GraalVM.run([], config).then(() => {
  globalThis.rontoSetLanguage(lang);
  const res = globalThis.rontoCompileWasm(fs.readFileSync(file, "utf8"));
  if (res.startsWith("ERROR:")) { console.log(res.slice(0, 300)); process.exit(1); }
  fs.writeFileSync(out, Buffer.from(res, "base64"));
  process.exit(0);
});
