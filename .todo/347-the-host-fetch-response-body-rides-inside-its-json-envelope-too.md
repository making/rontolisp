# The host-fetch response body rides inside its JSON envelope too

Difficulty: Medium

`.todo/341` diagnosed a shape: a reactor boundary that carries a BODY inside a
JSON envelope pays for it in copies, in memory, in the impossibility of binary,
and in never being able to stream. That item fixes the INCOMING request body.
The same shape is present, unfixed, in the other direction -- the reply to an
OUTGOING request:

```
env.fetch(request-json) -> response-json
```

is one import, and `response-json` holds the whole reply body as a JSON string
(`HostFetchLibrary`, the envelope defuns derived from
`compiler/FetchResponseShape`; `.kb/fetch-http.md`). So on a `--no-wasi`
reactor `rontolisp:fetch`'s `:body` is **one eager string**, decided before any
Lisp runs.

## Why this is a divergence and not a preference

`.kb/fetch-http.md` states the contract as **"the result plist is
`(:status <int> :headers <alist> :body <stream>)` on every backend"** -- an
asynchronous stream drained with `(await (read-all ...))`. Three of the four
backends really answer a stream (the interpreter and the JVM natively, the
`--component` path a `TYPE_WASI_STREAM` over the wasi byte stream). The
`--host-fetch` reactor answers a string instead, and only `read-all`'s
`stringp` arm hides the difference from the drain spelling.

The divergence was written down with its REASON, which is the part that has
expired: there was no stream value on Preview 1 to answer with. `.todo/341`
Phase 1 built one (`rontolisp::%stream-new`, all four backends, 2026-08-13), so
the reason no longer holds -- which is exactly the re-evaluation trigger
`CLAUDE.md` asks a divergence to carry. `.todo/341` itself names this under
Phase 1 as "still adjacent, and untouched".

Consequences today:

- a large reply is fully materialised in linear memory before the handler sees
  a byte, twice (the JSON string, then the extracted body);
- a binary reply cannot cross at all -- the same finding-4 wall `.todo/341`
  measured (a `:string` result is a non-validating UTF-8 decode; `ff fe 41`
  came back as three characters, the first of them code point 0x1FE062);
- a Worker cannot forward a streamed upstream response to its own client even
  though both sides of it are streams in JavaScript.

## What will NOT fix it

**Wrapping the string in a stream on the Lisp side.** By then the bytes have
already crossed the boundary as one JSON string; a stream over them adds a copy
and buys nothing. The boundary itself has to split, exactly as in `.todo/341`.

## The shape

Mirror `.todo/341`'s design with the arrow reversed. `env.fetch` answers the
response HEAD (status, headers) and the body becomes a second import, in the
`read(2)` shape Phase 0 established -- the CALLER passes the buffer, the answer
is the FULL length so an undersized buffer is a retry:

```
env.fetch(headPtr, headLen) -> (ptr, len)      ; request head JSON -> response head JSON
env.readResponseBody(ptr, cap) -> i32          ; :async t or synchronous -- the host's choice
```

and `:body` becomes `%stream-new` over a thunk on that import, which is
literally the construction `.todo/341` Phase 2b synthesizes for the request
side. `:bytes` (Phase 0) is what makes the binary case work.

**One semantic change this forces, and it must be stated rather than
discovered.** Today "started == settled": the host call blocks the wasm stack
for the whole round trip, so `(await (fetch ...))` never suspends and a
transport failure signals at the CALL. Split the body off and the fetch future
settles when the HEADERS arrive; a mid-body transport failure then signals at
the DRAIN, like every other backend. That is the correct semantics and it is
what the other three already do -- but it is a documented contract changing
(`doc/{en,ja}/guides/http-fetch.md`, ".todo/341"-style "what breaks,
deliberately"), and both language trees move in the same commit.

## Ordering

Do this AFTER `.todo/341` Phase 2b and `.todo/340`, not before:

- Phase 2b is where the "pull thunk over a suspending host import" synthesis is
  written; doing the fetch side first would write it twice and then have to
  merge the copies.
- `.todo/340` (generate the suspending host glue) is what stops this from
  adding a THIRD hand-written import to every Worker's `src/index.js`.

Landed prerequisites: `.todo/341` Phase 0 (`:bytes`, the caller-passed buffer)
and Phase 1 (`%stream-new` on all four backends).

## What must NOT change

- The result plist itself -- `(:status :headers :body)`, its keys and their
  order stay derived from `compiler/FetchResponseShape`.
- `read-all`'s `stringp` arm: it is pinned and useful, and it stops being the
  only reason a reactor's fetch bodies are strings rather than being removed.
- The other three backends' fetch paths, which already stream.
- The nil-on-start-failure contract of `rontolisp:fetch`, and the build's host
  obligation lines (`WebAssembly.Suspending` + `promising` + serialised calls,
  the todo-337 re-entrancy guard).

## Gate

- A node-gated JSPI E2E in the fetch direction, the mirror of
  `WasmHostStreamE2eTest`: a `--no-wasi --host-fetch` module draining a chunked
  reply through the portable `(await (read-all (getf res :body)))`, chunks
  reassembled in order, one pull per chunk plus the EOF one, and
  `memory.buffer.byteLength` unchanged across a many-chunk pull (the finding-2
  flat-memory pin).
- A binary reply crossing exactly (`ff fe 41`), which is impossible today.
- `examples/net/dog-fetcher.lisp` and
  `examples/cloudflare-workers/dog-fetcher/worker.lisp` UNEDITED, and the
  four-backend fetch ci-spec cases unchanged.
- `HostFetchLibraryTest`'s envelope pins updated with the split, including the
  generated `src/index.js` half.
