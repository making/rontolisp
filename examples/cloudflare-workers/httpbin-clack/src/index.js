// index.js -- the whole Worker: Request -> JSON -> Lisp -> JSON -> Response.
//
// Byte for byte the boundary code of ../../httpbin/src/index.js -- the module
// exports the same six names, so the arena bracket, the module-scope
// instantiation and the trap recovery are identical (all explained in
// ../../README.md and ../../httpbin/README.md). What differs is only
// `requestToJson`: the Lisp half feeds a CLACK environment (through the built-in
// clack-handler-cloudflare-workers handler backend), so this side must hand over the
// facts Clack's environment is built from rather than a pre-chewed request. Two
// of them are easy to get wrong -- see the comments in that function.

import module from "./app.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// Instantiated here at module scope, so the cost lands on isolate startup
// rather than on a request -- `wrangler deploy` then reports and budget-checks
// it. It is where clack's whole load-time runs, which is most of this Worker's
// startup: measured ~24 ms of `_initialize`, against ~19 ms for ../httpbin.
let lisp = instantiate();

function instantiate() {
  const instance = new WebAssembly.Instance(module, {});
  instance.exports._initialize();
  return instance.exports;
}

// Synchronous on purpose: a Worker isolate interleaves concurrent requests only
// at `await` points, so nothing else can allocate inside the bracket.
function handleRequest(lisp, input) {
  const bytes = encoder.encode(input);
  const mark = lisp.__ronto_alloc_mark();

  const ptr = lisp.__ronto_alloc(bytes.length);
  new Uint8Array(lisp.memory.buffer, ptr, bytes.length).set(bytes);

  const [resultPtr, resultLen] = lisp["handle-request"](ptr, bytes.length);

  // Copy out before resetting: the result sits in the scratch the reset frees.
  const result = decoder.decode(
    new Uint8Array(lisp.memory.buffer.slice(resultPtr, resultPtr + resultLen)),
  );

  lisp.__ronto_alloc_reset(mark);
  return result;
}

/** The raw facts clack.handler.cloudflare-workers:handle turns into the Clack environment. */
async function requestToJson(request) {
  const url = new URL(request.url);
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const body = hasBody ? await request.text() : "";
  const headers = Object.fromEntries(request.headers);

  // Forward content-length. `%http-make-env` reads :content-length off the
  // header table, and lack/request's body parsing returns nothing without it --
  // while a request that arrived chunked has no content-length header at all.
  // We have just read the body, so set it from the bytes we actually hold.
  if (body) headers["content-length"] = String(encoder.encode(body).length);

  return JSON.stringify({
    method: request.method,
    // The RAW target -- path and query still joined, still percent-encoded --
    // NOT the pre-split path + query object ../httpbin sends. `%http-make-env`
    // does the "?" split and the decoding itself, and :path-info /
    // :query-string have to come from it for a Clack application to see what
    // Clack promises.
    target: url.pathname + url.search,
    scheme: url.protocol.replace(":", ""),
    // Clack's :remote-addr. Cloudflare puts the client IP here; there is no
    // peer port to report, so :remote-port stays nil.
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
      reply = JSON.parse(handleRequest(lisp, input));
    } catch (error) {
      // The handler backend answers 500 for Lisp errors itself, so reaching here
      // means a WASM trap -- which skipped the arena reset and may have left
      // Lisp state half-written. Replace the instance rather than keep serving.
      lisp = instantiate();
      console.error("handle-request failed:", error);
      return new Response("internal error\n", { status: 500 });
    }
    // The headers arrive as an ARRAY of [name, value] pairs, not an object:
    // that is what keeps a Clack application's two Set-Cookie headers two
    // headers instead of one.
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
