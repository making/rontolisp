// The whole Worker. `./worker.js` is generated from worker.lisp's own
// declarations (build.sh's --emit-js-glue) and owns all of it: instantiation,
// the entropy and the clock a --no-wasi module cannot draw for itself, the
// (ptr, len) staging around the entry point, and the Request -> envelope ->
// Response mapping.
//
// There is no import object to write because there are no imports: on the
// DEFAULT (envelope) boundary every body rides the head, so this module asks
// the host for nothing at all. ../httpbin-clack is the same application on the
// streaming boundary, where the bodies cross as octets through imports of their
// own -- and its src/index.js is the same call, because the glue writes those
// too.
//
// BYTE-IDENTICAL in every hello-* directory here, and in the httpbin-* ones bar
// the client-address hook: how the Lisp half routes is not visible from
// JavaScript. A new sibling copies this file, it does not edit it.

import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
