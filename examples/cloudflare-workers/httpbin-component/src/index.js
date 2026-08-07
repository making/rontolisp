// index.js -- the same handler as ../httpbin, reached through the component
// model. The one line worth comparing is the `handleRequest` call below: plain
// JavaScript strings in and out. What that costs is in ../README.md.

import core0 from "./dist/app.core.wasm";
import core1 from "./dist/app.core2.wasm";
import core2 from "./dist/app.core3.wasm";
import { instantiate } from "./dist/app.js";

// jco's glue asks the host for each core module by the file name it emitted.
// Rebuild after changing the Lisp and check these still match -- the count can
// change with the program.
const CORE_MODULES = {
  "app.core.wasm": core0,
  "app.core2.wasm": core1,
  "app.core3.wasm": core2,
};

// A wasm-GC component imports these whether or not the program does any I/O.
// app.lisp never prints, so stubs are enough.
const WASI_STUBS = {
  "wasi:cli/stdout": { writeViaStream: async () => ({ tag: "ok", val: undefined }) },
  "wasi:cli/types": {},
  "wasi:filesystem/types": { Descriptor: class Descriptor {} },
};

// jco's `instantiate` is the whole of it, and there is no initialisation call
// to follow it with: this path has no `_initialize`, and the `wasi:cli/run`
// export that would stand in for one cannot be driven through jco -- which is
// why app.lisp keeps its state inside functions.
//
// At module scope, so the cost is isolate startup rather than request work.
let lisp = instantiate((path) => CORE_MODULES[path], WASI_STUBS);

/** The request, in the shape app.lisp's `handle-request` reads. */
async function requestToJson(request) {
  const url = new URL(request.url);
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  return JSON.stringify({
    method: request.method,
    url: request.url,
    path: url.pathname,
    query: Object.fromEntries(url.searchParams),
    headers: Object.fromEntries(request.headers),
    body: hasBody ? await request.text() : "",
  });
}

export default {
  async fetch(request) {
    const body = await requestToJson(request);
    let reply;
    try {
      if (!lisp) lisp = instantiate((path) => CORE_MODULES[path], WASI_STUBS);
      reply = JSON.parse(lisp.handleRequest(body));
    } catch (error) {
      lisp = null; // a trapped instance cannot be trusted afterwards
      console.error("handleRequest failed:", error);
      return new Response("internal error\n", { status: 500 });
    }
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? {},
    });
  },
};
