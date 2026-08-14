// The whole Worker. `./worker.js` is generated from worker.lisp's own
// declarations (build.sh's --emit-js-glue), and on the ENVELOPE boundary it
// owns all of it: the import object, the (ptr, len) staging, the __ronto_alloc
// bracket, the JSPI wiring, the one-call queue, the env.fetch host half, and
// the Request -> envelope -> Response mapping. Nothing is left to say here.
//
// The other boundary is ../dog-fetcher: there the bodies stream through imports
// of their own, the host owns the readers behind them, and its src/index.js is
// the ninety-odd lines that say what each one does and map Request onto the
// envelope.

import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
