// index.js -- the whole Worker: Request -> JSON -> Lisp -> JSON -> Response.
// The mark/reset bracket in handleRequest is explained in ../README.md.

import module from "./app.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

let lisp = null;

// A function rather than a module-scope const, for two reasons the simpler
// ../hello does not have: `_initialize` has to run once (it is where app.lisp's
// top-level forms happen), and a trap has to be able to retire the instance so
// the next request gets a fresh one.
function boot() {
  if (!lisp) {
    const instance = new WebAssembly.Instance(module, {});
    instance.exports._initialize();
    lisp = instance.exports;
  }
  return lisp;
}

// Called here at module scope so instantiation is isolate startup rather than
// request work (`wrangler deploy` then reports it: "Worker Startup Time: 12 ms",
// and enforces a budget on it). Doing it lazily on the first request also works
// -- measured -- but then nothing checks the cost at deploy time.
boot();

// Synchronous on purpose: a Worker isolate interleaves concurrent requests only
// at `await` points, so nothing else can allocate inside the bracket.
function handleRequest(exports, input) {
  const bytes = encoder.encode(input);
  const mark = exports.__ronto_alloc_mark();

  const ptr = exports.__ronto_alloc(bytes.length);
  new Uint8Array(exports.memory.buffer, ptr, bytes.length).set(bytes);

  const [resultPtr, resultLen] = exports["handle-request"](ptr, bytes.length);

  // Copy out before resetting: the result sits in the scratch the reset frees.
  const result = decoder.decode(
    new Uint8Array(exports.memory.buffer.slice(resultPtr, resultPtr + resultLen)),
  );

  exports.__ronto_alloc_reset(mark);
  return result;
}

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
      reply = JSON.parse(handleRequest(boot(), body));
    } catch (error) {
      // app.lisp answers 500 for Lisp errors itself, so reaching here means a
      // WASM trap: the instance's Lisp heap can no longer be trusted.
      lisp = null;
      console.error("handle-request failed:", error);
      return new Response("internal error\n", { status: 500 });
    }
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? {},
    });
  },
};
