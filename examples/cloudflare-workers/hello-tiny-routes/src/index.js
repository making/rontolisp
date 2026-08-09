// index.js -- the whole Worker: Request -> JSON -> Lisp -> JSON -> Response.
//
// This file is BYTE-IDENTICAL in ../../hello-clack and ../../hello-tiny-routes:
// how the Lisp half routes is not visible from JavaScript.
//
// It is ../../httpbin-clack/src/index.js with everything these examples do not
// need removed: no body (the application ignores it), and no arena bracket
// around the call, because a request that carries no body is a small
// allocation the module's own heap absorbs. Read that directory's index.js if
// you want the full version -- the envelope is identical.

import module from "./worker.wasm";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

// Instantiated at module scope, so the cost lands on isolate startup rather
// than on a request. `_initialize` is where clack loads and clackup runs.
let lisp = instantiate();

function instantiate() {
  const instance = new WebAssembly.Instance(module, {});
  // Hand the module real entropy before its top level runs: a --no-wasi build
  // imports nothing, so its `random` starts from a constant and every isolate
  // would otherwise draw the same sequence. Seeding here -- BEFORE _initialize
  // -- also covers the load-time draws inside quickloaded libraries.
  instance.exports.__ronto_seed_random(
    new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0],
  );
  instance.exports._initialize();
  return instance.exports;
}

function handleRequest(input) {
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
    // The RAW target -- path and query still joined and still percent-encoded.
    // The Lisp side does the "?" split and the decoding, and :path-info /
    // :query-string have to come from there for a Clack application to see what
    // Clack promises.
    const input = JSON.stringify({
      method: request.method,
      target: url.pathname + url.search,
      scheme: url.protocol.replace(":", ""),
      headers: Object.fromEntries(request.headers),
    });

    let reply;
    try {
      reply = JSON.parse(handleRequest(input));
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
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
