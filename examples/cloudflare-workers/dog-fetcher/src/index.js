// The whole Worker: Request -> JSON -> Lisp -> JSON -> Response, plus the
// module's own outgoing request, answered here through JSPI.

import module from "./worker.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// The upstream reply the module is currently reading, and what of its last
// chunk did not fit in the buffer the module passed. One cursor, moved by each
// env.fetch below -- which is sound for the same reason the request cursor is:
// a suspended handler returns to the event loop, and the queue admits one Lisp
// call at a time.
let upstream = null;
let upstreamRest = null;
let upstreamFailed = null;

// env.fetch, the --host-fetch import: request JSON in, response HEAD JSON out.
// The envelope is rontolisp's, derived from FetchResponseShape and pinned by
// HostFetchLibraryTest, so this host and the compiler cannot drift apart:
//   in   {"url": ..., "method": ..., "headers": [[name, value], ...],
//         "body": ...}                       // body absent when the fetch has none
//   out  {"status": ..., "headers": [[name, value], ...]}
//        {"error": "..."}                    // the transport failed; fetch signals
// The reply BODY is NOT in there: it crosses through env.readResponseBody
// below, so a large or binary reply never has to become a JSON string.
async function hostFetch(lisp, ptr, len) {
  // Read before the await: memory growth detaches the buffer.
  const request = JSON.parse(
    decoder.decode(new Uint8Array(lisp.memory.buffer, ptr, len)),
  );

  upstream = null;
  upstreamRest = null;
  upstreamFailed = null;
  let envelope;
  try {
    const response = await fetch(request.url, {
      method: request.method,
      headers: request.headers,
      body: request.body,
    });
    // The reader is the body; the module pulls it after this call returns.
    upstream = response.body ? response.body.getReader() : null;
    envelope = { status: response.status, headers: [...response.headers] };
  } catch (error) {
    // The error arm becomes a Lisp condition at the fetch; throwing would trap.
    envelope = { error: String(error) };
  }

  // A :string result is host-written bytes: allocate, return [ptr, len].
  const bytes = encoder.encode(JSON.stringify(envelope));
  const out = lisp.__ronto_alloc(bytes.length);
  new Uint8Array(lisp.memory.buffer, out, bytes.length).set(bytes);
  return [out, bytes.length];
}

// env.readResponseBody, the other half of the fetch boundary: write up to `cap`
// octets of the reply that the last env.fetch opened at `ptr` and answer how
// many. 0 is end of stream; a NEGATIVE count is a transfer that failed after
// the head crossed, which the module signals at the drain -- the same place
// every other backend does. Reading a ReadableStream is asynchronous, so this
// one really does suspend.
async function readResponseBody(lisp, ptr, cap) {
  if (upstreamFailed) return -1;
  try {
    while (!upstreamRest || upstreamRest.length === 0) {
      if (!upstream) return 0;
      const { value, done } = await upstream.read();
      if (done) {
        upstream = null;
        return 0;
      }
      upstreamRest = value;
    }
  } catch (error) {
    upstreamFailed = error;
    return -1;
  }
  // After the await: re-read memory.buffer, which a growth may have detached.
  const n = Math.min(cap, upstreamRest.length);
  new Uint8Array(lisp.memory.buffer, ptr, n).set(upstreamRest.subarray(0, n));
  upstreamRest = upstreamRest.subarray(n);
  return n;
}

// The request body the module pulls, and how much of it has already crossed.
// Set inside the queue below, not beside it: a suspended handler returns to the
// event loop, so a second request must not move the cursor under the first.
const NO_BODY = new Uint8Array(0);
let body = NO_BODY;
let bodyOffset = 0;

// And the response body coming back the same way. Read inside the queue too,
// for the same reason the cursor is set there.
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

// Instantiated on the first request: a Worker forbids crypto in global scope.
let lisp = null;
const lispInstance = () => (lisp ??= instantiate());

function instantiate() {
  let exports;
  const instance = new WebAssembly.Instance(module, {
    env: {
      // Suspending parks the wasm stack until the promise settles, so the Lisp
      // side needs no async. Only callable on a stack entered through
      // promising() below -- _initialize must never reach it.
      fetch: new WebAssembly.Suspending((ptr, len) =>
        hostFetch(exports, ptr, len),
      ),
      // The reply body, out of band. Suspending too -- a ReadableStream is only
      // readable asynchronously, which is exactly the host the module's
      // :async t declaration allows for.
      readResponseBody: new WebAssembly.Suspending((ptr, cap) =>
        readResponseBody(exports, ptr, cap),
      ),
      // The request body, out of band: write up to `cap` octets at `ptr` and
      // answer how many, 0 for end of stream. Synchronous, so it needs no
      // Suspending -- the module declares the import :async t and accepts
      // either host.
      readRequestBody: (ptr, cap) => {
        const n = Math.min(cap, body.length - bodyOffset);
        if (n <= 0) return 0;
        new Uint8Array(exports.memory.buffer, ptr, n).set(
          body.subarray(bodyOffset, bodyOffset + n),
        );
        bodyOffset += n;
        return n;
      },
      // The response body, out of band and the other way round: take these
      // octets, they are the next chunk. Copy now -- the module pops the
      // staging behind the pointer when this returns. Synchronous for the same
      // reason the reader is.
      writeResponseBody: (ptr, len) => {
        responseChunks.push(
          new Uint8Array(exports.memory.buffer.slice(ptr, ptr + len)),
        );
      },
    },
  });
  exports = instance.exports;
  // Entropy and clock, before the Lisp top level runs (nanoseconds since epoch).
  exports.__ronto_seed_random(
    new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0],
  );
  exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
  exports._initialize();
  return {
    exports,
    handleRequest: WebAssembly.promising(exports["handle-request"]),
  };
}

// One Lisp call at a time: a suspended handler returns to the event loop, and
// the module's re-entry guard traps a second call while one is parked.
let queue = Promise.resolve();
function serialized(work) {
  const done = queue.then(work, work);
  queue = done.then(
    () => {},
    () => {},
  );
  return done;
}

async function handleRequest(lisp, input) {
  const wasm = lisp.exports;
  wasm.__ronto_set_time(BigInt(Date.now()) * 1000000n);

  const bytes = encoder.encode(input);
  const mark = wasm.__ronto_alloc_mark();
  const ptr = wasm.__ronto_alloc(bytes.length);
  new Uint8Array(wasm.memory.buffer, ptr, bytes.length).set(bytes);

  const [resultPtr, resultLen] = await lisp.handleRequest(ptr, bytes.length);

  // Copy out before resetting: the result sits in the scratch it frees.
  const result = decoder.decode(
    new Uint8Array(wasm.memory.buffer.slice(resultPtr, resultPtr + resultLen)),
  );
  wasm.__ronto_alloc_reset(mark);
  return result;
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const bytes = request.body
      ? new Uint8Array(await request.arrayBuffer())
      : NO_BODY;
    const headers = Object.fromEntries(request.headers);
    // lack/request parses no body without a content-length, and a chunked
    // request carries none -- set it from the octets we actually have.
    if (bytes.length) headers["content-length"] = String(bytes.length);
    const input = JSON.stringify({
      method: request.method,
      // The RAW target: the Lisp side splits and decodes it.
      target: url.pathname + url.search,
      scheme: url.protocol.replace(":", ""),
      headers,
    });

    let reply;
    try {
      // The chunks are read inside the critical section: they belong to the one
      // call that is running, and a suspended handler returns to the event loop.
      reply = await serialized(async () => {
        body = bytes;
        bodyOffset = 0;
        responseChunks = [];
        const head = JSON.parse(await handleRequest(lispInstance(), input));
        return { ...head, body: head.body ?? responseBody() };
      });
    } catch (error) {
      // Lisp errors answer 500 themselves, so this is a trap: the arena reset
      // was skipped and state may be half-written. Replace the instance.
      lisp = instantiate();
      console.error("handle-request failed:", error);
      return new Response("internal error\n", { status: 500 });
    }

    // Headers as an ARRAY of pairs: that is what keeps two Set-Cookie headers two.
    return new Response(reply.body, {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
