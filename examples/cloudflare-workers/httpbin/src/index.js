// index.js -- the whole Worker: Request -> JSON -> Lisp -> JSON -> Response.
//
// This file is BYTE-IDENTICAL in ../../httpbin and ../../httpbin-clack, and that
// is the readable proof that the two directories differ only in how the Lisp
// half is written. Both speak the same JSON envelope, and on both sides of it
// sits the same Clack application; what differs is only what builds the Clack
// environment from the envelope -- thirty hand-written lines in ../app.lisp
// there, the built-in clack-handler-cloudflare-workers handler backend that
// `clackup` installs here. Neither is visible from JavaScript.
//
//   diff ../../httpbin/src/index.js ../../httpbin-clack/src/index.js   # no output
//
// The envelope is documented in ../../httpbin/README.md; two of its fields are
// easy to get wrong, and requestToJson below says which.

import module from "./app.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// Instantiated here at module scope, so the cost lands on isolate startup
// rather than on a request -- `wrangler deploy` then reports and budget-checks
// it ("Worker Startup Time: 12 ms"). Doing it lazily on the first request also
// works, measured, but then nothing checks the cost at deploy time.
let lisp = instantiate();

// `_initialize` is where the Lisp program's top-level forms run. ../../hello has
// no such entry point at all -- it is --no-gc with nothing to initialise --
// which is why it needs none of this.
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

/** The raw facts the Lisp side turns into the Clack environment. */
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
    // The RAW target -- path and query still joined, still percent-encoded.
    // `%http-make-env` does the "?" split and the decoding itself, and
    // :path-info / :query-string have to come from it for a Clack application
    // to see what Clack promises. A pre-split path leaves :query-string nil.
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
      // The Lisp side answers 500 for Lisp errors itself, so reaching here
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
