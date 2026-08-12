# The reactor boundary carries the request body inside its JSON envelope; split it, and give Preview 1 a real stream

Difficulty: High

A host-driven reactor (`--no-wasi` / `--no-gc`, every Cloudflare Worker in
`examples/cloudflare-workers/`) meets the program at ONE export,
`handle-request(request-json) -> response-json`, and the request BODY rides
inside that JSON string (`eval/http-reactor.lisp`'s envelope header,
`HttpReactorInliner`: "the request body always arrives buffered in the
envelope"). Everything below follows from that one decision, and all of it is
measured, not argued:

- the body path is **quadratic** -- 256 KiB of body costs 55 s;
- the arena holds the body **17x** over;
- a **binary** body cannot cross at all;
- `:raw-body` is a synchronous buffered stream on the reactor while it is an
  asynchronous stream on the other three backends, so a checked-in example that
  drains it the portable way answers **500** as soon as a request carries a
  body (finding 6 -- this one is a live bug, not a design smell);
- the host must read the whole body before it may call in, so a Worker cannot
  stream an upload even though its own `Request.body` is a `ReadableStream`.

Since `:async t` landed, a suspending host import is a declared, supported fact,
and the spike below shows the boundary can be rebuilt around it. Breaking the
envelope is in scope; rewriting every reactor example is in scope.

## What the spike settled (2026-08-13)

Node 24.18 `--experimental-wasm-jspi`, wasm-GC `--no-wasi`, jar at 8a36ce55.
Scripts are throwaway; every number below is reproducible from the shapes
described.

**1. A chunked pull through a suspending import works, and the host picks the
strategy.** A `:async t` import called in a loop from two frames below the
export, entered through `WebAssembly.promising`: four suspensions inside ONE
export call, body reassembled correctly. The SAME module then ran against a
plain synchronous function (no `Suspending`, no `promising`) and produced the
same answer -- so "eagerly buffered, fast" and "truly streamed, serialised" are
a HOST choice over one module, exactly as the `:async t` obligation line says.

**2. Chunking alone does NOT bound memory -- the buffer must be reused.** 4 MiB
pulled as 64 x 64 KiB, module counts the bytes and keeps nothing:

| host convention | arena growth | linear memory |
| --- | --- | --- |
| `__ronto_alloc` per chunk (today's `:string` result rule) | **+4.00 MiB** | 0.25 -> 4.13 MiB |
| ONE buffer, rewritten per chunk | **+0.00 MiB** | 0.25 -> 0.25 MiB |

The whole memory argument for streaming dies unless the import result
convention changes for bytes. See the design below for the shape that follows:
the CALLER passes the buffer.

**3. A handle parameter would give a pull import call identity -- and is not
needed.** Two serialised `body(handle)` calls each drained their own chunk
list; without a handle the import is a global cursor and the second call eats
the first's chunks. But the re-entry guard already guarantees there is only
ever ONE call inside the module (it fired exactly as designed when two
suspending calls were started concurrently: the first completed, the second
trapped), so a serialising host needs nothing but a "current request" variable
-- which is what its promise queue already establishes. Deliberately NO handle
in the protocol: add one when the per-call arena scope and per-task dynamic
store that `.kb/wasm-import.md` defers under "Deliberately NOT per-call state"
make real overlap possible, and not before.

**4. A `:string` result cannot carry bytes.** Handing back three bytes
`ff fe 41` produced three characters, the first of them code point **2089058**
(0x1FE062 -- outside Unicode) and the other two 0. Valid UTF-8 survives exactly,
including an embedded NUL (`00 41` -> `(0 65)`, length honoured). The decoder is
non-validating by construction, so binary needs its own type designator, not
care at the call site.

**5. The quadratic is `json-parse` over the envelope, not the boundary.**
`examples/cloudflare-workers/httpbin` unedited (`--no-wasi --optimize=size`),
one request per instance:

| body | `handle-request` | arena growth | linear memory |
| --- | --- | --- | --- |
| 4 KiB | 24 ms | +68 KiB | 256 KiB |
| 16 KiB | 252 ms | +272 KiB | 256 -> 384 KiB |
| 64 KiB | 3453 ms | +1088 KiB | 256 -> 1216 KiB |
| 256 KiB | **54701 ms** | **+4352 KiB** | 256 -> 4672 KiB |

4x the body costs ~16x the time and ~17x its own size in arena. Split by phase
(same module, 16 KiB -> 64 KiB): the `:string` boundary itself is FLAT (256 KiB
crosses in 1.0 ms), `json-parse` alone is 64 -> 858 ms, the buffered `:raw-body`
construction adds ~1.7x on top, and the example's own `read-char` drain adds
another ~1.9x. So the envelope's JSON is the cost, and the cause is `.todo/185`
(`(char s i)` is O(i) on all three compile backends) -- reported there as a
third sighting. json-parse of the same input is LINEAR on the interpreter (it
has a Java implementation) and quadratic on the JVM compile path too (16K/64K/
256K -> 28/325/5238 ms), which is the same `.todo/185` walk.

Fixing `.todo/185` would take the constant down but not the shape: the body
would still be JSON-escaped text, still copied several times, still un-binary,
still not a stream. Both are worth doing and neither substitutes for the other.

**6. The portable drain is BROKEN on a reactor today.**
`examples/net/httpbin.lisp` -- checked in, and commented "The env :raw-body is
an asynchronous stream on every backend" -- compiled `--no-wasi` and handed a
5-byte POST body answers

```
500 {"error":"rontolisp:STREAM-READ requires the interpreter, the JVM backend
     or an asynchronous --component program (streams come from rontolisp:fetch
     / rontolisp:http-handler bodies there)"}
```

because the reactor's `:raw-body` is the buffered Gray stream and `read-all`'s
drain loop reaches `stream-read`, which Preview 1 stubs. Nothing catches it:
`examples/examples.yaml` pins that file on `jvm-compile` and `wasm-component`
only, and the Worker examples that DO run a reactor with a body
(`cloudflare-workers/httpbin`, `-clack`) all read the body the buffered way
instead. Phase 1 is what fixes this; until then the portable spelling is
three-backend, not four, and the comment in that file is wrong.

## The design

**One idea carries it: the reactor transport takes a request HEAD and a BODY
SOURCE, and the body source is an abstract Lisp value.** The head stays JSON
and stays small (method, target, headers, scheme, remote-addr) so the quadratic
never touches a body again. The body source is one of

- `nil` -- no body,
- a string -- already buffered (what a host that prefers today's concurrency
  hands over),
- a **pull thunk** -- arity 0, answers the next chunk, nil/empty = EOF.

That keeps ONE transport for all backends, which matters because
`clack-handler-reactor` runs on every one of them: on the interpreter and the
JVM the caller passes a closure or a string directly, and on the WASM backends
the synthesized export builds the thunk over a `:async t` import. The wasm case
becomes a special case of the shared shape instead of a fork.

The whole WASM boundary is then three entries:

```
;; module -> host
handle-request(headPtr, headLen) -> (ptr, len)   ; request head JSON -> response head JSON

;; host -> module (:async t or synchronous -- the host's choice, finding 1)
env.readRequestBody(ptr, cap) -> i32   ; write up to cap bytes at ptr, return n; 0 = EOF
env.writeResponseBody(ptr, len)        ; take len bytes from ptr
```

**The CALLER passes the buffer** -- the `read(2)` shape, and the answer to
finding 2. There is deliberately no `__ronto_*` export handing a buffer out:
the `__ronto_*` namespace is for general runtime hooks (`alloc`, `set_time`,
the seed), an HTTP-shaped entry there is a layering break, and a module-owned
buffer would have to be named for a body it cannot see from the boundary
layer. Passing (ptr, cap) at the call also means the host cannot hold a pointer
across calls, and it survives the per-call arena scope unchanged -- where the
buffer LIVES becomes a module-side decision the protocol never mentions. It
generalises past HTTP the same way: `env.kvGet(keyPtr, keyLen, bufPtr, bufCap)
-> i32`, with the return being the FULL length so an undersized buffer is a
retry, not a truncation.

`:string` results keep today's rule (the host `__ronto_alloc`s and returns
(ptr, len)). That is not an inconsistency but the line worth drawing:
**`:string` is a value, `:bytes` is a transfer.** A bounded control-plane value
may be allocated per call; an unbounded byte stream may not.

Phases, each independently landable and each with its own gate:

**Phase 0 -- the boundary types.** A `:bytes` designator for
`wasm-import`/`wasm-export` params and results: an `(unsigned-byte 8)` vector,
no UTF-8 decode in either direction (finding 4), and a RESULT declared `:bytes`
takes the caller-passed (ptr, cap) pair and answers a length rather than
allocating (finding 2, and the design note above). Both are general -- they are
what any byte-shaped host import needs, HTTP or not. Gate: a preload E2E
round-tripping arbitrary bytes (including the `ff fe 41` that finding 4
corrupts today), and the finding-2 table re-measured flat.

**Phase 1 -- Preview 1 gets a real stream value.** `TYPE_P1_STREAM
{mut i32 eof, mut readFn, mut closeFn}` -- the same three fields as
`TYPE_WASI_STREAM`, in the base type table beside `TYPE_P1_FUTURE` -- with
`stream-read` answering a settled `TYPE_P1_FUTURE` of the next chunk and
`stream-close` running the close thunk once. Most of the runtime already
exists: `WasmFutureRuntimeBuilder.buildWasiStreamRead` has a `sched == null`
degenerate path that is exactly this (call the read thunk, close once at EOF,
wrap settled), and `%wasi-stream-new` is already thunk-driven with nothing
WASI about it. What is new is the type index (adding one to the base table
shifts `TYPE_STR_BYTES` and the wrapper/import type derivations) and settling
over `TYPE_P1_FUTURE` instead of `TYPE_FUTURE`. This RETIRES the "no stream
value can exist here" call-time stubs in `WasmExprCompiler` for Preview 1, and
with them the divergence in `.kb/async-await.md`. Adjacent once it exists:
`--host-fetch`'s response `:body`, an eager string today, can become the same
stream value (`read-all`'s `stringp` arm stays -- it is pinned and useful --
but it stops being the only reason a reactor's bodies are strings). Gate:
`(await (read-all s))` over a host-backed stream on a `--no-wasi` module, and
`streamp` answering T on all four backends for the same program.

**Phase 2 -- the envelope splits.** `%http-reactor-dispatch` takes head +
body source; `%http-reactor-request-tuple` passes a STREAM built from the
source (`%http-body-stream` gains a from-thunk constructor beside its from-
string one) instead of always buffering; `:raw-body :buffered` drains that
stream into the existing Gray class, so Clack applications are untouched. The
synthesized export changes shape accordingly (`HttpReactorInliner`), and the
`:raw-body` mode the directive currently drops unevaluated starts meaning
something on a reactor. Gate: the finding-5 table re-measured (the target is
linear and one body-sized copy), plus the existing four-backend ci-spec http
cases unchanged.

**Phase 3 -- the response body, symmetrically.** Today it is one string in
linear memory, so a large or streamed response pays the same way. Same
convention in reverse (`env.writeResponseBody(ptr, len)`, the module choosing
where those bytes sit), and `%http-normalize-response`'s stream arm stops being
drained before sending on this transport.

**Phase 4 -- the examples, and the glue that stops being hand-written.** Ten of
the eleven `examples/cloudflare-workers/` directories speak this envelope
(`hello/` is the exception: three direct `wasm-export`s, no reactor), through
FOUR distinct glue files -- `httpbin/src/index.js` (byte-identical in five
directories), `hello-clack/src/index.js` (in three), `dog-fetcher/src/index.js`
and `httpbin-component`'s generated glue. All four move, and
`httpbin/worker.lisp`'s hand-rolled `read-char` drain becomes the library path.
Two things to fix while there: `dog-fetcher/src/index.js` sends NO body at all
today (it inherited hello-clack's bodyless envelope, so a POST to it is
silently dropped), and `httpbin/src/index.js` reads the body of every non-GET
request even when the route drops it. This is where `.todo/340` (generate the
suspending host glue) should land rather than earlier -- with a uniform
boundary the glue is derivable, and generating it is what stops the copies from
drifting.

## What breaks, deliberately

- The reactor JSON envelope is documented (`doc/{en,ja}/guides/clack.md`, the
  `clack.handler.reactor:dispatch` surface) and changes shape. Both language
  trees move in the same commit.
- `handle-request`'s exported signature changes, so every host glue file
  changes with it. That is the point of moving all four at once.
- A program that calls `%http-reactor-dispatch` directly (the docs show one)
  needs its second argument.

## What must NOT change

- The Clack environment contract and the response contract (`.kb/http-server.md`)
  -- this item moves how the body ARRIVES, never what a handler sees.
- **The key is `:raw-body`, not `:body`.** It is upstream's, and the `raw` is
  load-bearing: `lack/src/request.lisp` parses `:raw-body` through
  `http-body:parse` and appends `:body-parameters` TO THE SAME PLIST, so the
  two names are a pair distinguishing the unparsed stream from the parsed view.
  `request-raw-body` is also exported, so renaming would break lack, ningle and
  every application over them. Same file is the reason `:buffered` must stay
  seekable: lack wraps it in a `circular-input-stream` and `file-position`s it
  back to 0, which a pull stream cannot serve -- so the lack ecosystem keeps
  `:buffered` and the streaming mode serves everything else.
- The four-backend `:raw-body` spelling: after Phase 1 the portable
  `(await (read-all (getf env :raw-body)))` must work on all four, which is the
  whole point.
- Byte-identity for modules that neither serve nor declare a suspending import
  (the Phase 0/1 type additions must be gated, like every previous type
  addition).
