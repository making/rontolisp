// index.js -- the whole Worker: Request -> JSON -> Lisp -> JSON -> Response.
//
// BYTE-IDENTICAL in every hello-* directory here: how the Lisp half routes is
// not visible from JavaScript. A new sibling copies this file, it does not edit
// it.
//
// It is ../../httpbin-clack/src/index.js with everything these examples do not
// need removed: no arena bracket around the call, because the head of a request
// is a small allocation the module's own heap absorbs. Read that directory's
// index.js if you want the full version -- the envelope is identical.

import module from "./worker.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// The request body the module is about to pull, and how much of it has already
// crossed. These applications answer on the path alone, but the module imports
// the reader either way, so handing it the real body is what keeps a POST from
// being silently dropped by a file that gets copied.
const NO_BODY = new Uint8Array(0);
let body = NO_BODY;
let bodyOffset = 0;

// And the response body coming back the same way, chunk by chunk, in order.
// Reset per request.
let responseChunks = [];

function responseBody() {
  const all = new Uint8Array(responseChunks.reduce((n, c) => n + c.length, 0));
  let at = 0;
  for (const chunk of responseChunks) {
    all.set(chunk, at);
    at += chunk.length;
  }
  return all;
}

// Instantiated on the FIRST REQUEST, not at module scope: a Worker forbids
// generating random values in the global scope, and the seed below has to be in
// before `_initialize` runs the Lisp top level. So the cost lands on one
// request instead of on isolate startup, where `wrangler deploy` would have
// reported it as "Worker Startup Time".
let lisp = null;
const lispInstance = () => (lisp ??= instantiate());

function instantiate() {
  let instance;
  const env = {
    // The request body, out of band: write up to `cap` octets at `ptr` and
    // answer how many, 0 for end of stream. The module owns the buffer and
    // hands it over per call, so the body never has to ride the envelope as a
    // JSON string -- and a binary one crosses exactly.
    readRequestBody(ptr, cap) {
      const n = Math.min(cap, body.length - bodyOffset);
      if (n <= 0) return 0;
      new Uint8Array(instance.exports.memory.buffer, ptr, n).set(
        body.subarray(bodyOffset, bodyOffset + n),
      );
      bodyOffset += n;
      return n;
    },
    // The response body, out of band and the other way round: take these
    // octets, they are the next chunk. Copy now -- the module pops the staging
    // behind the pointer when this returns. A body that never becomes a JSON
    // string is also one that can be binary, and one a large response never
    // holds twice.
    writeResponseBody(ptr, len) {
      responseChunks.push(
        new Uint8Array(instance.exports.memory.buffer.slice(ptr, ptr + len)),
      );
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

function handleRequest(lisp, input) {
  // The module's clock moves only when we move it, so move it per request. That
  // is not a workaround for a frozen clock -- a Worker's own `Date.now()` is
  // frozen for the duration of a request as a timing-attack mitigation, so a
  // value that changes exactly once per request is what this platform has.
  lisp.__ronto_set_time(BigInt(Date.now()) * 1000000n);
  const bytes = encoder.encode(input);
  const ptr = lisp.__ronto_alloc(bytes.length);
  new Uint8Array(lisp.memory.buffer, ptr, bytes.length).set(bytes);

  const [resultPtr, resultLen] = lisp["handle-request"](ptr, bytes.length);
  return decoder.decode(
    new Uint8Array(lisp.memory.buffer.slice(resultPtr, resultPtr + resultLen)),
  );
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    // `request.body` is null exactly when there is none, so a GET reads
    // nothing. This is the one place a synchronous host has to buffer: the
    // module pulls the octets from inside its own call.
    body = request.body ? new Uint8Array(await request.arrayBuffer()) : NO_BODY;
    bodyOffset = 0;
    responseChunks = [];
    const headers = Object.fromEntries(request.headers);
    // lack/request parses no body without a content-length, and a chunked
    // request carries none -- set it from the octets we actually have.
    if (body.length) headers["content-length"] = String(body.length);
    // The RAW target -- path and query still joined and still percent-encoded.
    // The Lisp side does the "?" split and the decoding, and :path-info /
    // :query-string have to come from there for a Clack application to see what
    // Clack promises.
    const input = JSON.stringify({
      method: request.method,
      target: url.pathname + url.search,
      scheme: url.protocol.replace(":", ""),
      headers,
    });

    let reply;
    try {
      reply = JSON.parse(handleRequest(lispInstance(), input));
    } catch (error) {
      // The handler backend answers 500 for Lisp errors itself, so reaching
      // here means a WASM trap, which may have left Lisp state half-written.
      // Replace the instance rather than keep serving.
      lisp = instantiate();
      console.error("handle-request failed:", error);
      return new Response("internal error\n", { status: 500 });
    }

    // The headers arrive as an ARRAY of [name, value] pairs, not an object:
    // that is what keeps a Clack application's two Set-Cookie headers two
    // headers instead of one.
    //
    // A "body" key is present only when the body did NOT cross out of band, and
    // then it WINS: the 500 a failing handler answers rides the head, so the
    // chunks taken before it are discarded rather than prepended to it.
    return new Response(reply.body ?? responseBody(), {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
