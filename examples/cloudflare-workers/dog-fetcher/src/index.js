// The whole Worker. `./worker.js` is generated from worker.lisp's own
// declarations (build.sh's --emit-js-glue) and owns all of it: the import
// object, the (ptr, len) staging, the __ronto_alloc bracket, the JSPI wiring,
// the one-call-at-a-time queue, the four host functions this boundary declares
// -- env.fetch and the three body imports -- and the Request -> envelope ->
// Response mapping.
//
// That the STREAMING boundary needs no more of a host than ../btc-ticker's
// envelope one is the point of the pair: the bodies here cross as octets
// through imports of their own, and where they come from is the Request this
// file already has and the Response it is already building, so the glue writes
// that too. What the boundary buys is in the README: a binary body crossing
// exactly, a large one never doubling linear memory, an upstream reply
// forwarded a chunk at a time.
//
// To take any of it over, hand `worker` a host: whatever it supplies is laid
// over the derived entries one at a time.
//
//   import { worker, suspending } from "./worker.js";
//   export default worker(module, {
//     remoteAddr: (request) => request.headers.get("cf-connecting-ip"),
//     host: { env: { fetch: suspending(myOwnFetch) } },
//   });

import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
