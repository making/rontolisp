// index.js -- the whole Worker: Request -> JSON head + body octets -> Lisp ->
// JSON -> Response.
//
// BYTE-IDENTICAL in every httpbin-* directory that drives the module directly
// (httpbin-component has its own generated glue instead), and that is the
// point: how the Lisp half is written is not visible from JavaScript. A new
// sibling copies this file rather than editing it.
//
// The envelope is documented in ../../httpbin/README.md; two of its fields are
// easy to get wrong, and requestToHead below says which.

import module from "./worker.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// Instantiated on the FIRST REQUEST, not at module scope. Module scope would be
// the nicer place -- `wrangler deploy` reports and budget-checks what happens
// there as "Worker Startup Time" -- but a Worker forbids GENERATING RANDOM
// VALUES in the global scope, and the seed below has to be handed over before
// `_initialize` runs the Lisp top level. So the cost lands on one request
// instead, and nothing checks it at deploy time.
let lisp = null;
const lispInstance = () => (lisp ??= instantiate());

// The request body the module is about to pull, and how much of it has already
// crossed. Module-level rather than passed in, because the module asks for it
// from inside its own call -- see readRequestBody below.
const NO_BODY = new Uint8Array(0);
let body = NO_BODY;
let bodyOffset = 0;

// `_initialize` is where the Lisp program's top-level forms run. ../../hello has
// no such entry point at all -- it is --no-gc with nothing to initialise --
// which is why it needs none of this.
function instantiate() {
  let instance;
  const env = {
    // The request body, out of band. The module owns the buffer and hands it
    // over per call -- write up to `cap` octets at `ptr` and answer how many,
    // 0 for end of stream -- so nothing here may hold on to the pointer, and
    // the body never has to exist as one JSON-escaped string inside the
    // envelope. A binary upload crosses exactly for the same reason.
    //
    // Synchronous, which is one of the two hosts the module accepts: it
    // declares the import `:async t`, so a host may equally wrap this in
    // `WebAssembly.Suspending` and pull straight from `request.body`'s reader
    // -- at the price of entering `handle-request` through
    // `WebAssembly.promising` and serialising the calls, because a suspended
    // module can be re-entered (see ../../dog-fetcher for that shape).
    readRequestBody(ptr, cap) {
      const n = Math.min(cap, body.length - bodyOffset);
      if (n <= 0) return 0;
      new Uint8Array(instance.exports.memory.buffer, ptr, n).set(
        body.subarray(bodyOffset, bodyOffset + n),
      );
      bodyOffset += n;
      return n;
    },
  };
  instance = new WebAssembly.Instance(module, { env });
  // Hand the module real entropy before its top level runs: a --no-wasi build
  // imports nothing else, so its `random` starts from a constant and every
  // isolate would otherwise draw the same sequence. Seeding here -- BEFORE
  // _initialize -- also covers the load-time draws inside quickloaded
  // libraries.
  instance.exports.__ronto_seed_random(
    new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0],
  );
  // And a clock, the same way and for the same reason: a --no-wasi build
  // imports none, so its time is whatever a host writes through
  // __ronto_set_time (nanoseconds since the Unix epoch). Before _initialize,
  // so a library that timestamps while it LOADS sees one -- unset, the clock
  // built-ins signal rather than report 1970.
  instance.exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
  instance.exports._initialize();
  return instance.exports;
}

// Synchronous on purpose: a Worker isolate interleaves concurrent requests only
// at `await` points, so nothing else can allocate inside the bracket -- and
// nothing else can take the body cursor above, which is the same guarantee the
// module's own re-entry guard makes on the other side.
function handleRequest(lisp, input) {
  // The module's clock moves only when we move it, so move it per request. That
  // is not a workaround for a frozen clock -- a Worker's own `Date.now()` is
  // frozen for the duration of a request as a timing-attack mitigation, so a
  // value that changes exactly once per request is what this platform has.
  lisp.__ronto_set_time(BigInt(Date.now()) * 1000000n);
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
function requestToHead(request, bodyLength) {
  const url = new URL(request.url);
  const headers = Object.fromEntries(request.headers);

  // Forward content-length. `%http-make-env` reads :content-length off the
  // header table, and lack/request's body parsing returns nothing without it --
  // while a request that arrived chunked has no content-length header at all.
  // We are holding the body, so set it from the octets we actually have.
  if (bodyLength) headers["content-length"] = String(bodyLength);

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
  });
}

export default {
  async fetch(request) {
    // `request.body` is null exactly when there is none, so a GET reads
    // nothing. This is the one place a synchronous host has to buffer: the
    // module pulls the octets from inside its own call, and this side cannot
    // await there without JSPI.
    body = request.body ? new Uint8Array(await request.arrayBuffer()) : NO_BODY;
    bodyOffset = 0;
    const input = requestToHead(request, body.length);
    let reply;
    try {
      reply = JSON.parse(handleRequest(lispInstance(), input));
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
