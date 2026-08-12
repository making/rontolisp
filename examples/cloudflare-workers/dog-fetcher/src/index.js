// The whole Worker: Request -> JSON -> Lisp -> JSON -> Response, plus the
// module's own outgoing request, answered here through JSPI.

import module from "./worker.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// env.fetch, the --host-fetch import: request JSON in, response JSON out. The
// envelope is rontolisp's, derived from FetchResponseShape and pinned by
// HostFetchLibraryTest, so this host and the compiler cannot drift apart:
//   in   {"url": ..., "method": ..., "headers": [[name, value], ...],
//         "body": ...}                       // body absent when the fetch has none
//   out  {"status": ..., "headers": [[name, value], ...], "body": ...}
//        {"error": "..."}                    // the transport failed; fetch signals
async function hostFetch(lisp, ptr, len) {
  // Read before the await: memory growth detaches the buffer.
  const request = JSON.parse(
    decoder.decode(new Uint8Array(lisp.memory.buffer, ptr, len)),
  );

  let envelope;
  try {
    const response = await fetch(request.url, {
      method: request.method,
      headers: request.headers,
      body: request.body,
    });
    envelope = {
      status: response.status,
      headers: [...response.headers],
      body: await response.text(),
    };
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
    const input = JSON.stringify({
      method: request.method,
      // The RAW target: the Lisp side splits and decodes it.
      target: url.pathname + url.search,
      scheme: url.protocol.replace(":", ""),
      headers: Object.fromEntries(request.headers),
    });

    let reply;
    try {
      reply = JSON.parse(
        await serialized(() => handleRequest(lispInstance(), input)),
      );
    } catch (error) {
      // Lisp errors answer 500 themselves, so this is a trap: the arena reset
      // was skipped and state may be half-written. Replace the instance.
      lisp = instantiate();
      console.error("handle-request failed:", error);
      return new Response("internal error\n", { status: 500 });
    }

    // Headers as an ARRAY of pairs: that is what keeps two Set-Cookie headers two.
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
