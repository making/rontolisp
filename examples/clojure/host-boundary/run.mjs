// A JavaScript host for main.wasm: --emit-js-glue writes main.js beside the module,
// and the host supplies only what the declaration cannot -- one plain function per
// import.
//
//   rontolisp main.clj -o main.wasm --no-wasi --emit-js-glue
//   node run.mjs                                                  # 42
import fs from 'fs';
import { instantiate } from './main.js';

const module = new WebAssembly.Module(fs.readFileSync(new URL('./main.wasm', import.meta.url)));
const lisp = instantiate(module, { host: { add: (a, b) => a + b } });
console.log(lisp.add10(32));
