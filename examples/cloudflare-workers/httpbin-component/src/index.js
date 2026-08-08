// index.js -- the same handler as ../httpbin, reached through the component
// model. The one line worth comparing is the `handleRequest` call below: plain
// JavaScript strings in and out. What that costs is in ../README.md.

import core0 from "./dist/worker.core.wasm";
import core1 from "./dist/worker.core2.wasm";
import core2 from "./dist/worker.core3.wasm";
import { instantiate } from "./dist/worker.js";

// jco's glue asks the host for each core module by the file name it emitted.
// Rebuild after changing the Lisp and check these still match -- the count can
// change with the program.
const CORE_MODULES = {
  "worker.core.wasm": core0,
  "worker.core2.wasm": core1,
  "worker.core3.wasm": core2,
};

// A wasm-GC component imports these whether or not the program does any I/O.
// worker.lisp never prints, so stubs are enough.
const WASI_STUBS = {
  "wasi:cli/stdout": { writeViaStream: async () => ({ tag: "ok", val: undefined }) },
  "wasi:cli/types": {},
  "wasi:filesystem/types": { Descriptor: class Descriptor {} },
};

// jco's `instantiate` is the whole of it, and there is no initialisation call
// to follow it with: this path has no `_initialize`, and the `wasi:cli/run`
// export that would stand in for one cannot be driven through jco -- which is
// why worker.lisp keeps its state inside functions.
//
// At module scope, so the cost is isolate startup rather than request work.
const getCoreModule = (path) => CORE_MODULES[path];
let lisp = instantiate(getCoreModule, WASI_STUBS);

// The envelope is worker.lisp's, not this directory's, so this function is
// ../../httpbin/src/index.js's unchanged -- the component model moves the
// boundary, not the contract. Its two load-bearing fields (the RAW target and
// the forwarded content-length) are commented there.
/** The raw facts the Lisp side turns into the Clack environment. */
async function requestToJson(request) {
  const url = new URL(request.url);
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const body = hasBody ? await request.text() : "";
  const headers = Object.fromEntries(request.headers);
  if (body) headers["content-length"] = String(new TextEncoder().encode(body).length);

  return JSON.stringify({
    method: request.method,
    target: url.pathname + url.search,
    scheme: url.protocol.replace(":", ""),
    "remote-addr": request.headers.get("cf-connecting-ip"),
    headers,
    body,
  });
}

export default {
  async fetch(request) {
    const input = await requestToJson(request);
    let reply;
    try {
      reply = JSON.parse(lisp.handleRequest(input));
    } catch (error) {
      lisp = instantiate(getCoreModule, WASI_STUBS); // retire the trapped one
      console.error("handleRequest failed:", error);
      return new Response("internal error\n", { status: 500 });
    }
    // An ARRAY of [name, value] pairs, so a repeated Set-Cookie survives.
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
