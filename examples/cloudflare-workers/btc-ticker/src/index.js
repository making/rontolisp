// The whole Worker. `./worker.js` is generated from worker.lisp's own
// declarations (build.sh's --emit-js-glue), and on the ENVELOPE boundary it
// owns all of it: the import object, the (ptr, len) staging, the __ronto_alloc
// bracket, the JSPI wiring, the one-call queue, the env.fetch host half, and
// the Request -> envelope -> Response mapping. Nothing is left to say here.
//
// The other boundary is ../dog-fetcher: there the bodies stream through imports
// of their own -- and its src/index.js is these same three lines, because the
// readers behind those imports are the Request the glue is already holding and
// the Response it is already building. What the boundary changes is what happens
// to a body, not how much host it costs.

import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
