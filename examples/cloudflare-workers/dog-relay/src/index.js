// The whole Worker. `./worker.js` is generated from worker.lisp's own
// declarations (build.sh's --emit-js-glue) and owns all of it: the import
// object, the (ptr, len) staging, the __ronto_alloc bracket, the JSPI wiring,
// the four host functions this boundary declares -- env.fetch and the three
// body imports -- and the Request -> envelope -> Response mapping.
//
// What differs from ../dog-fetcher/src/index.js is nothing here and everything
// in the generated file: this module was compiled --reentrant, so worker()
// has NO one-call-at-a-time queue. Requests overlap on the one instance, and
// the body state that used to be "the current call's" is a map keyed by the
// call id worker() mints per request and the envelope carries -- every pull
// of a request body, every response chunk and every chunk of a relayed reply
// names the call it belongs to.
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
