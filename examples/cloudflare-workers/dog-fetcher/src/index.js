// The whole Worker: Request -> JSON -> Lisp -> JSON -> Response, plus the
// module's own outgoing request, answered here through JSPI.
//
// The BOUNDARY is not in this file. `./worker.js` is generated from
// worker.lisp's own declarations (build.sh's --emit-js-glue) and owns all of
// it: the import object, the (ptr, len) staging, the __ronto_alloc bracket, the
// WebAssembly.Suspending wrappers, the WebAssembly.promising entry and the
// one-call-at-a-time queue that keeps a suspended handler from being re-entered.
// What is left here is the part a declaration cannot state -- what each host
// function DOES -- and the Worker's own Request/Response mapping.

import module from "./worker.wasm";
import { instantiate, suspending } from "./worker.js";

// The instance. Declared up here because a host function below reaches back
// into it (`lisp.drop`) while being an ARGUMENT to the `instantiate` that
// produces it -- the one circular reference in this file, and the reason this
// is a mutable binding rather than a parameter. Assigned on the first request:
// a Worker forbids drawing entropy in global scope, and the glue seeds the
// module's generator before running its top level.
let lisp = null;

// The upstream reply the module is currently reading. The generated glue holds
// what of a chunk did not fit; this is only WHERE the octets come from, which is
// the one thing it cannot derive.
let upstream = null;

// env.fetch, the --host-fetch import: request JSON in, response HEAD JSON out.
// The envelope is rontolisp's, derived from FetchResponseShape and pinned by
// HostFetchLibraryTest, so this host and the compiler cannot drift apart:
//   in   {"url": ..., "method": ..., "headers": [[name, value], ...],
//         "body": ...}                       // body absent when the fetch has none
//   out  {"status": ..., "headers": [[name, value], ...]}
//        {"error": "..."}                    // the transport failed; fetch signals
// The reply BODY is NOT in there: it crosses through env.readResponseBody
// below, so a large or binary reply never has to become a JSON string.
async function hostFetch(head) {
  const request = JSON.parse(head);
  upstream = null;
  // This fetch REPLACES the reply body the module was reading, and the glue is
  // holding whatever of the old one did not fit -- octets that belong to a
  // reply nobody may read again. Only this side knows the source moved.
  lisp.drop("env.readResponseBody");
  try {
    const response = await fetch(request.url, {
      method: request.method,
      headers: request.headers,
      body: request.body,
    });
    // The reader is the body; the module pulls it after this call returns.
    upstream = response.body ? response.body.getReader() : null;
    return JSON.stringify({
      status: response.status,
      headers: [...response.headers],
    });
  } catch (error) {
    // The error arm becomes a Lisp condition at the fetch; throwing would trap.
    return JSON.stringify({ error: String(error) });
  }
}

// env.readResponseBody: the next chunk of the reply the last env.fetch opened,
// null at the end of it. Reading a ReadableStream is asynchronous, so this one
// really does suspend -- and a read that THROWS becomes the negative count the
// module signals at the drain, which the glue answers on our behalf.
async function readResponseBody() {
  if (!upstream) return null;
  const { value, done } = await upstream.read();
  if (done) {
    upstream = null;
    return null;
  }
  return value;
}

// The request body the module pulls, and the response body coming back the
// same way. Both are set inside the queued call below, not beside it: a
// suspended handler returns to the event loop, so they must belong to the call
// that is running.
let requestBody = null;
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

// Set when a call TRAPPED: that instance skipped its arena reset and its Lisp
// state may be half-written, so nothing else may run on it. The flag rather
// than a bare `lisp = null` is what protects a request already QUEUED on it --
// it is next in that instance's own queue, and it checks this first.
let poisoned = false;
function lispInstance() {
  if (poisoned) {
    lisp = null;
    poisoned = false;
  }
  return (lisp ??= instantiate(module, {
    env: {
      // Marked: these two answer promises, so the wasm stack parks until they
      // settle. The other two do not, and pay nothing for it -- the reactor
      // declares both of THOSE :async t, which says it accepts either host
      // (env.fetch is not declared so, and --host-fetch is what allows its
      // suspension instead).
      fetch: suspending(hostFetch),
      readResponseBody: suspending(readResponseBody),
      readRequestBody: () => {
        const chunk = requestBody;
        requestBody = null;
        return chunk;
      },
      writeResponseBody: (chunk) => responseChunks.push(chunk),
    },
  }));
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const bytes = request.body
      ? new Uint8Array(await request.arrayBuffer())
      : null;
    const headers = Object.fromEntries(request.headers);
    // lack/request parses no body without a content-length, and a chunked
    // request carries none -- set it from the octets we actually have.
    if (bytes?.length) headers["content-length"] = String(bytes.length);
    const input = JSON.stringify({
      method: request.method,
      // The RAW target: the Lisp side splits and decodes it.
      target: url.pathname + url.search,
      scheme: url.protocol.replace(":", ""),
      headers,
    });

    let reply;
    try {
      // Inside the glue's critical section, because all of this belongs to the
      // one call that is running: a suspended handler returns to the event loop,
      // so setting it beside the call would let the next request move it. The
      // INSTANCE is chosen in here too -- a request admitted while an earlier
      // one is still parked must not bind an instance that request is about to
      // throw away.
      reply = await lispInstance().serially(async (entry) => {
        if (poisoned) throw new Error("instance discarded by an earlier trap");
        requestBody = bytes;
        responseChunks = [];
        const head = JSON.parse(await entry.handleRequest(input));
        return { ...head, body: head.body ?? responseBody() };
      });
    } catch (error) {
      // Lisp errors answer 500 themselves, so this is a trap: the arena reset
      // was skipped and state may be half-written. Discard the instance -- and
      // refuse whatever is already queued behind it rather than run it there.
      poisoned = true;
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
