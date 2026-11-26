// The whole Worker. `./worker.js` is generated from worker.lisp's own
// declarations (build.sh's --emit-js-glue) and owns all of it: the import
// object, the (ptr, len) staging, the __ronto_alloc bracket, the two body
// imports this boundary declares -- fed from the Request it is already holding
// and the Response it is already building -- and the Request -> envelope ->
// Response mapping over them.
//
// That the STREAMING boundary needs no more of a host than ../hello-clack's
// envelope one is the point of the pair: what the boundary buys is a binary
// body crossing exactly and a large one never doubling linear memory, not a
// bigger host. ../httpbin is the one Worker here that still writes its own,
// because it declares `handle-request` by hand and the compile path recognises
// the SYNTHESIZED bridge -- read that directory's src/index.js for what this
// file would otherwise say.
//
// BYTE-IDENTICAL in every httpbin-* directory that goes through clackup. A new
// sibling copies this file, it does not edit it.

import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module, {
  // Clack's :remote-addr. Which header carries the client address is the
  // platform's business and not the glue's, so it is the one thing worker()
  // leaves to its caller; Cloudflare puts it here. There is no peer port to
  // report, so :remote-port stays nil.
  remoteAddr: (request) => request.headers.get("cf-connecting-ip"),
});
