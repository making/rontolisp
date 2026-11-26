// index.js -- the same handler as ../httpbin, reached through the component
// model. The one line worth comparing is the `handleRequest` call below: plain
// JavaScript strings in and out. What that costs is in ../README.md.

import core0 from "./dist/worker.core.wasm";
import { instantiate } from "./dist/worker.js";

// jco's glue asks the host for each core module by the file name it emitted.
// A --no-wasi reactor component has exactly ONE -- the whole program -- and
// nothing to import: the second argument really is the empty object (the
// generated .d.ts says `interface ImportObject {}`).
//
// The Lisp top level runs inside `instantiate` (the core module's start
// section), so a `defparameter` in worker.lisp is already assigned before the
// first request -- the reactor counterpart of ../httpbin calling _initialize.
//
// At module scope, so the cost is isolate startup rather than request work.
const getCoreModule = () => core0;
let lisp = instantiate(getCoreModule, {});

// The envelope is worker.lisp's, not this directory's, so this function is
// ../../httpbin/src/index.js's unchanged -- the component model moves the
// boundary, not the contract. Its two load-bearing fields (the RAW target and
// the forwarded content-length) are commented there.
/** The raw facts the Lisp side turns into the Clack environment. */
async function requestToJson(request) {
  const url = new URL(request.url);
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const body = hasBody ? await request.text() : "";
  const headers = Object.fromEntries(request.headers);
  if (body) headers["content-length"] = String(new TextEncoder().encode(body).length);

  return JSON.stringify({
    method: request.method,
    target: url.pathname + url.search,
    scheme: url.protocol.replace(":", ""),
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
      reply = JSON.parse(lisp.handleRequest(input));
    } catch (error) {
      lisp = instantiate(getCoreModule, {}); // retire the trapped one
      console.error("handleRequest failed:", error);
      return new Response("internal error\n", { status: 500 });
    }
    // An ARRAY of [name, value] pairs, so a repeated Set-Cookie survives.
    return new Response(reply.body ?? "", {
      status: reply.status ?? 200,
      headers: reply.headers ?? [],
    });
  },
};
