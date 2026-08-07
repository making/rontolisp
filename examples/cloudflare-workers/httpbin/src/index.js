// index.js -- the whole Worker: Request -> JSON -> Lisp -> JSON -> Response.
// The mark/reset bracket in handleRequest is explained in ../README.md.

import module from "./app.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// Instantiated here at module scope, so the cost lands on isolate startup
// rather than on a request -- `wrangler deploy` then reports and budget-checks
// it ("Worker Startup Time: 12 ms"). Doing it lazily on the first request also
// works, measured, but then nothing checks the cost at deploy time. Set back to
// null when a trap retires the instance, below.
let lisp = instantiate();

// `_initialize` is where app.lisp's top-level forms run. ../hello has no such
// entry point at all -- it is --no-gc with nothing to initialise -- which is why
// it needs none of this.
function instantiate() {
  const instance = new WebAssembly.Instance(module, {});
  instance.exports._initialize();
  return instance.exports;
}

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
      if (!lisp) lisp = instantiate();
      reply = JSON.parse(handleRequest(lisp, body));
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
