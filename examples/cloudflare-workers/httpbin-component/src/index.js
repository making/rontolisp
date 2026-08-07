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

let lisp = null;

function boot() {
  if (!lisp) {
    // No initialisation call: there is no `_initialize` on this path, and the
    // `wasi:cli/run` export that would stand in for it cannot be driven through
    // jco -- which is why app.lisp keeps its state inside functions.
    lisp = instantiate((path) => CORE_MODULES[path], WASI_STUBS);
  }
  return lisp;
}

// Called here at module scope so instantiation is isolate startup rather than
// request work, and `wrangler deploy` reports and budget-checks it.
boot();

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
      reply = JSON.parse(boot().handleRequest(body));
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
