# The streaming body protocol has no call identity, so --reentrant refuses it

Difficulty: High

`--reentrant` (todo-348) lets a JSPI host overlap calls into one instance, and
it REFUSES `--host-boundary=streaming` -- eagerly, by import name, with a
message pointing at the envelope boundary. This item is the work that refusal
defers: giving the streaming body protocol per-call identity so the two flags
compose.

## Why the refusal is where the line sits today

`.todo/341` finding 3 settled that the reactor body protocol takes **no handle
parameter**, and said exactly why: the re-entry guard guarantees one call inside
the module, so `env.readRequestBody` / `env.writeResponseBody` (and
`--host-fetch`'s `env.readResponseBody`) are a GLOBAL cursor on the host side --
"the current request's body", "the last fetch's reply" -- and that is safe.
Relaxing the guard invalidates that argument, so todo-348 kept it valid by
construction: wherever the no-handle protocol exists, the guard (or the
serialising queue) still holds. The envelope boundary carries no cursor at all,
which is why it is the overlap-ready shape and the one the guides already
recommend for document-shaped bodies.

What the refusal costs: a workload that wants BOTH overlap and a streamed /
binary / relayed body (dog-fetcher's literal shape) has to choose. Today it
chooses serialisation (dog-fetcher, unchanged) or the envelope's copies.

## The design the identity work needs

- **A call id in the envelope.** The glue mints an id per request and writes it
  as a new `ReactorEnvelope` key; the dispatcher threads it to the body thunks.
  That changes the thunk arity the shared transport (`http-reactor.lisp`) calls
  with, the `HttpReactorInliner` synthesis (a leading `:int` param on both body
  imports, reentrant builds only), and the glue's `worker()` (cursors keyed by
  id instead of one `requestBody`/`responseChunks` pair).
- **The fetch reply pull needs its own identity**, not the request's: a second
  fetch inside one call supersedes the first (the `lisp.drop` contract). The
  natural key is an id the glue writes into the reply HEAD's JSON, which the
  module hands back on every `env.readResponseBody(id, ptr, cap)` pull --
  `FetchResponseShape` gains the field, `http.lisp`'s drain threads it.
- **Byte-pins move**: the four `httpbin-*` workers' generated `src/worker.js`
  regenerate only if their (non-reentrant) emission changes -- it must not; the
  reentrant emission is new surface. `ReactorEnvelopeTest` pins the key lists
  against `http-reactor.lisp` and will catch a key added on one side only.

## Non-goals

- Changing the non-reentrant protocol in any byte: the id is reentrant-only
  surface, exactly as the task record and the park allocator are.
- dog-fetcher's boundary: it stays the streaming showcase; whether it ALSO opts
  into `--reentrant` once this lands is its own decision (the controlled
  comparison with btc-ticker is worth keeping either way).

## Gate

- Two overlapped streaming requests, one uploading `ff fe 41` and one a text
  body, each answered its OWN echo -- the corruption the refusal currently
  prevents, inverted.
- Two overlapped calls each fetching a different upstream and relaying the
  reply body chunk-at-a-time: each client receives its own upstream's octets.
- The refusal in `WasmReentrantCompilerTest.reentrantRefusesTheStreamingBodyBoundary`
  replaced by the composed build.
