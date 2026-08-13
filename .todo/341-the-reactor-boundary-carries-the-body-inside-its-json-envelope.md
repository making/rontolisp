# The reactor boundary carries the request body inside its JSON envelope; split it, and give Preview 1 a real stream

Difficulty: High

A host-driven reactor (`--no-wasi`; a `--no-gc` one cannot carry the HTTP
transport at all, see Phase 2a below) meets the program at ONE export,
`handle-request(request-json) -> response-json`, and the request BODY rides
inside that JSON string (`eval/http-reactor.lisp`'s envelope header,
`HttpReactorInliner`: "the request body always arrives buffered in the
envelope"). Everything below follows from that one decision, and all of it is
measured, not argued:

- ~~the body path is **quadratic** -- 256 KiB of body costs 55 s~~ -- **CLOSED
  by `.todo/185`, not by this item** (2026-08-13). The quadratic was
  `(char s i)` walking from index 0 on the compile backends, which this item
  reported as a third sighting; with that fixed the same unedited module answers
  a 256 KiB body in **197 ms**, and the curve is linear. Re-measured table below;
- the arena holds the body **17x** over -- **unchanged**, to the kilobyte;
- a **binary** body cannot cross at all;
- ~~`:raw-body` is a synchronous buffered stream on the reactor while it is an
  asynchronous stream on the other three backends~~ -- **CLOSED by Phase 2a**
  (finding 6, 2026-08-13);
- ~~the host must read the whole body before it may call in, so a Worker cannot
  stream an upload even though its own `Request.body` is a `ReadableStream`~~ --
  **the MODULE side is CLOSED** by Phase 2b (step 2, and step 3 pins a suspending
  host end to end); no checked-in glue file streams yet, and step 3 says why.

**So the motivation is now MEMORY, BINARY and STREAMING -- not speed.** Anyone
picking this up should say so out loud before spending the boundary change: the
headline number that opened this item is gone, and it was taken by a one-line
fix somewhere else. (Of those three, BINARY and STREAMING are done on the module
side; MEMORY is done for the TRANSPORT and open for the DRAIN -- `.todo/350`.
What is left here is the RESPONSE body's WASM boundary, Phase 3 step b -- its
Lisp half landed 2026-08-13.)

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

**Re-measured after `.todo/185` closed** (2026-08-13, `714b9086`; node 24.19,
same module built the same way, one request per instance, one run per size).
The prediction above held exactly -- the constant went, the shape stayed:

| body | `handle-request` | vs. before | arena growth | linear memory |
| --- | --- | --- | --- | --- |
| 4 KiB | 21 ms | 1.1x | +68 KiB | 256 KiB |
| 16 KiB | 21 ms | **12x** | +272 KiB | 256 -> 384 KiB |
| 64 KiB | 46 ms | **75x** | +1088 KiB | 256 -> 1216 KiB |
| 256 KiB | 197 ms | **277x** | **+4352 KiB** | 256 -> 4672 KiB |

4x the body now costs ~4x the time -- linear. The arena and linear-memory
columns are IDENTICAL to the kilobyte, which is the point: `.todo/185` took the
time and left every byte. What this item is still for is those two columns,
plus binary and streaming.

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
instead. **Phase 2a fixed this** (2026-08-13; Phase 1 was the original guess
and was not enough: the reactor's `:raw-body` was a Gray instance, and no
stream TYPE makes `read-all` able to drain one -- what had to change is which
value `:raw-body` holds, which in turn needed the directive's `:raw-body` mode
to reach the reactor at all). The comment in that file is right again.

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

**Phase 0 -- the boundary types. DONE (2026-08-13).** A `:bytes` designator for
`wasm-import`/`wasm-export` params and results: an `(unsigned-byte 8)` vector,
no UTF-8 decode in either direction (finding 4), and a RESULT declared `:bytes`
takes the caller-passed (ptr, cap) pair and answers a length rather than
allocating (finding 2, and the design note above). Both are general -- they are
what any byte-shaped host import needs, HTTP or not.

What landed, and the two things a later phase inherits from it:

- `BoundaryType.BYTES` (no WIT spelling), accepted by both directives on the GC
  CORE-module paths; `--component` refuses eagerly naming the missing `list<u8>`
  lift, `--no-gc` refuses naming the missing arrays. The value is the bare
  `TYPE_I8ARR` packed vector, so `aref`/`length` on it are the ordinary ones.
- **The result convention is the whole point**: one trailing Lisp parameter (the
  receive buffer) and a trailing host `(ptr, cap)` pair, answering the FULL
  length -- so an undersized buffer is a retry. Every backend derives the arity
  from ONE place (`WasmImportDirective.lispParamCount` /
  `WasmImportCompiler.lispArity`), which is what keeps the interpreter/JVM stubs
  loading at the same arity.
- The import wrapper takes a HEAP MARK on entry and pops to it on return, so the
  staged `(ptr,len)` regions do not accumulate. That is what makes the finding-2
  table flat, and it is why a `:bytes` parameter is bump-ALLOCATED rather than
  staged at the un-advanced `HEAP_PTR` scratch the `:string` boundary uses:
  several can coexist within one call.
- Three gated helpers (`_bytes_from_mem`, `_bytes_copy`, `_bytes_fill`) sharing
  one appended signature; a module without the designator is byte-identical
  (pinned).

Gate, met: `WasmBytesBoundaryE2eTest` (node-gated, plain JS host -- content can
only cross against a host that shares the module's memory) round-trips
`ff fe 41` exactly in all four directions, checks the full-length answer on an
undersized buffer with no overrun, and pins the flat-memory pull loop (10000
pulls staging 64 KiB each, `memory.buffer.byteLength` unchanged). The wasmtime
preload leg pins the plumbing through the lengths, which are what cross two
disjoint memories. `.kb/wasm-import.md` has the mechanics.

Not done here, deliberately: nothing HTTP-shaped. The boundary entries
`env.readRequestBody` / `env.writeResponseBody` are Phases 2 and 3; this phase
only made a type that can express them.

**Phase 1 -- a real stream value on every backend. DONE (2026-08-13).**
`TYPE_P1_STREAM {mut i32 eof, mut readFn, mut closeFn}` -- the same three fields
as `TYPE_WASI_STREAM` -- with `stream-read` answering a settled `TYPE_P1_FUTURE`
of the next chunk and `stream-close` running the close thunk once, plus the
interpreter/JVM halves of the same primitive.

What landed on the WASM side:

- `WasmP1StreamRuntimeBuilder`, two functions (`_p1_stream_read` /
  `_p1_stream_close`). Nothing on this tier can suspend, so there is no pending
  arm and no scheduler -- which is why it is its OWN builder rather than the
  `sched == null` path of `buildWasiStreamRead`: sharing would have meant
  threading a second future type and a second settle shape through every arm of
  a function whose whole body is the pending case.
- `%wasi-stream-new` is now `rontolisp::%stream-new`: nothing about it was ever
  WASI (a read thunk, a close thunk, a flag), and both tiers build their stream
  from it. `WasmStreamCompiler` (was `WasmWasiStreamCompiler`) picks the tier.
- The type went NOT in the base table but in the slot the async block would have
  used (`p1StreamTypeBase()` / `p1StreamFuncBase()`; the two modes are mutually
  exclusive), so no existing type or function index moves and the byte-identity
  invariant below is kept rather than spent -- pinned by
  `WasmLispCompilerTest.theP1StreamBlockRidesOnlyAStreamCreatingModule`.
- The read thunk's answer is resolved through `_p1_future_await` before the EOF
  test. A `:async t` import and an `async-lambda` both answer a SETTLED future
  here, and a future wrapping nil is not nil -- without the resolve such a thunk
  could never report EOF. That is what makes "the host pulls the chunks" (finding
  1) work as written.
- The call-time stub is retired where a stream can exist. Where none can, only
  `stream-read`/`stream-close` keep it: `streamp` there is now the CONSTANT NIL,
  because nothing being a stream is an answer, not a failure (it used to signal).

And on the interpreter/JVM side, which is what makes `%stream-new` portable
enough for Phase 2's `%http-body-stream` (clack-handler-reactor runs on every
backend):

- The interpreter gets a PULL mode on `LispStream` (`LispStream.pull`): no
  buffer, no write end, one thunk call per read. The thunks arrive as a
  `Supplier`/`Runnable` the EVALUATOR closes over -- the root package may not
  import `eval` -- and the evaluator's callback also does the RESOLVE
  (`awaitValue` of the applied thunk), so `LispStream` never sees a future and
  the "resolve before the end-of-stream test" rule is one rule, not four.
- The JVM's pull stream is the buffered stream's `Object[3]` with the
  `{readFn, closeFn}` pair where the queue would be, so `_streamp` and the
  `#<STREAM>` print are untouched; `_stream_read` runs the thunk through `_await`
  and answers a settled future rather than the `{RMARKER, queue, state}` token.
  `_drain_body` now reads through `_stream_read` + `_await` instead of taking
  off the queue directly -- ONE drain for both modes, and shorter than what it
  replaced.
- `stream-write` on a pull stream is its OWN refusal ("the stream has no write
  end") on both, rather than the misleading "the stream is closed".

Gate, met: the `stream-new-builds-a-pull-stream-on-every-backend` ci-spec case
(one program, four backends, identical output -- the `streamp`-answers-T half
that was open), the `AsyncEvalTest`/`JvmAsyncCompilerTest` pairs (the same drain
plus an ASYNC read thunk and the no-write-end edge),
`WasmLispCompilerIntegrationTest.preview1HasAFirstClassStreamValueOverAPairOfThunks`,
and `WasmHostStreamE2eTest` -- a `--no-wasi` module pulling its body one chunk at
a time through a suspending host import and draining it with the portable
`(await (read-all s))`, against a JS host that shares its memory (chunks
reassembled in order, one pull per chunk plus the EOF one, close protocol run
exactly once). `.kb/async-await.md` has the mechanics.

Also still adjacent, and untouched: `--host-fetch`'s response `:body`, an eager
string today, can become the same stream value (`read-all`'s `stringp` arm stays
-- it is pinned and useful -- but it stops being the only reason a reactor's
bodies are strings).

Finding 6 is NOT fixed here (its paragraph above is corrected accordingly): what
Phase 1 bought is the value the reactor's `:raw-body` can BECOME, and moving
`:raw-body` onto it is Phase 2.

**Phase 2a -- the transport takes a head and a body source. DONE
(2026-08-13).** (`.todo/342` is done, 2026-08-12: that fast path's
`(stringp s)` was O(body) on wasm whenever the body is a mutable buffer, which
is what a pulled body is, and is constant time now -- so the measuring half of
this phase measures what it means to.)

The LISP half of the split, which is the half that does not move a single host
glue file:

- `%http-reactor-dispatch` / `-handle` (and both `clack.handler.reactor`
  entries) take an optional BODY SOURCE beside the head: nil, a string, or a
  PULL THUNK (arity 0, next chunk, nil/`""` = EOF, possibly a FUTURE of one --
  `%http-reactor-pull` resolves it, since this transport is synchronous code
  where `await` is not legal). The envelope's `"body"` key IS the string case
  and is the fallback, so nothing that speaks the old envelope changed.
- **`:raw-body` means something on a reactor now**, and that is what fixed
  finding 6. The mode is REGISTERED with the app (`%http-reactor-register app
  [:buffered]`, `%http-reactor-buffered`) because a reactor has no
  `http-handler` call at run time; both Clack backends' `run` register
  `:buffered`, and `HttpReactorInliner.lowerHttpHandler` now THREADS the
  directive's pair instead of dropping it. Default (`:stream`) builds a
  first-class pull stream over the source with Phase 1's `%stream-new`
  (`%http-reactor-body-stream`); `:buffered` drains the same source
  (`%http-reactor-body-text`) into the existing Gray class, so Clack
  applications are untouched.
- Verified against the finding-6 program itself: `examples/net/httpbin.lisp`
  unedited, `--no-wasi`, a 5-byte POST through a plain node host answers 200
  with the echoed `data` where the todo measured a 500.

Gate, met: the `http-reactor-body-source` ci-spec case (four backends: a pull
thunk, an in-band string and no body at all in the default mode, then the same
pull source under `:buffered`), the two `LispEvaluatorAsdfTest` reactor-body
tests, `HttpReactorInlinerTest.theLoweredHttpHandlerDirectiveKeepsItsRawBodyMode`,
and the existing four-backend ci-spec http cases unchanged. `.kb/clack.md`
("The head and the body source") has the mechanics.

One premise of this item corrected on the way: **a `--no-gc` reactor cannot
carry the HTTP transport at all**, and could not before this phase either --
`http-server.lisp`'s `%http-drain` / `%http-serve-request` are `async-defun`s,
which that backend rejects by name, so the splice fails long before anything
asks for a stream. The `--no-gc` Worker is the `wasm-export`-only one
(`examples/cloudflare-workers/hello`). A gate that would have caught this
earlier: `examples/examples.yaml` has no reactor backend, so no checked-in
example compiles `--no-wasi` in CI (`examples/net/httpbin.lisp` is pinned on
`jvm-compile` and `wasm-component` only).

**Phase 2b -- the WASM boundary splits.**

*Step 1, the CHUNK TYPE, done (2026-08-13).* Before the wasm boundary can hand
a source over there has to be a source shape it can hand over: the `:bytes`
import reads into a reusable buffer and answers OCTETS, and a `:string` result
is exactly what finding 2 and finding 4 rule out. So a chunk is now a string
**or** an `(unsigned-byte 8)` vector, and both drains read through ONE adapter,
`%http-reactor-text-source` -- which is also where the two rules a chunked
source needs live:

- an open UTF-8 sequence is CARRIED into the next chunk. The host that cut the
  body knows nothing about code points, so a character straddling a boundary is
  the normal case, not a corner one -- decoding each chunk on its own answers
  two malformed characters per split. `http-server.lisp`'s decoder became a
  RANGE decoder for this (`%http-utf8-decode-octets` / `%http-utf8-length` /
  `%http-utf8-complete-end`; the old list spelling is one line over it);
- the adapter never answers `""` before the end, because an empty answer IS end
  of stream to both consumers and a chunk whose every byte was carried over
  decodes to nothing.

Nothing outside `http-reactor.lisp` / `http-server.lisp` moved, and no host
glue changed. Gate: the `http-reactor-body-source` ci-spec case grew an octet
pull source through BOTH modes whose every character straddles a chunk boundary
(four backends), plus a third `LispEvaluatorAsdfTest` reactor-body test through
the Clack shim. `.kb/clack.md` / `.kb/http-server.md` have the mechanics.

Known and deliberately left: `:buffered` over an octet source decodes to text
and `%http-body-stream` re-encodes it to octets. Phase 2b step 2 should hand
the buffered mode the octets directly rather than adding a second conversion.

*Step 2, the BOUNDARY, done (2026-08-13).* The synthesized export now takes the
request HEAD and pulls the body out of band, so the whole wasm boundary is the
two entries of the design:

```
handle-request(headPtr, headLen) -> (ptr, len)   ; the JSON head, no "body" key
env.readRequestBody(ptr, cap) -> i32             ; :bytes, :async t; 0 = EOF
```

`HttpReactorInliner` synthesizes the import and the thunk over it beside the
bridge; the thunk is two transport calls (`%http-reactor-buffer` /
`%http-reactor-chunk`), so the reused buffer and the chunk-boundary decode stay
in ONE place and a hand-written reactor gets them by naming them. Three rules
came out of building it, all in `.kb/clack.md`:

- the thunk CALLS the import rather than taking `#'name`, or the build's
  suspending-import report widens to "any export may suspend";
- an empty source is NO BODY (`:raw-body` nil, upstream's `(when raw-body ...)`),
  which once the body left the envelope only the host can answer -- so the
  transport pulls ONCE and pushes the chunk back, and an empty source still falls
  back to the envelope's own `"body"` key;
- **Preview 1 core modules only**: `--component` refuses both `wasm-import` and
  the packed array behind `:bytes`, so a reactor component keeps the in-band
  body. Re-evaluate when the component path grows a `list<u8>` lift.

**Measured, same shape as the finding-5 table** (node 24.19, `--no-wasi
--optimize=size`, one instance, arena bracket per request). The last two columns
were the target, and the boundary column is now flat:

| body | boundary (handler drops it) | with the handler's own `read-all` |
| --- | --- | --- |
| 4 KiB | 256 KiB, +0 | 256 KiB, +0 |
| 16 KiB | 256 KiB, +0 | 256 -> 384 KiB |
| 64 KiB | 256 KiB, +0 | 256 -> 1088 KiB |
| 256 KiB | **256 KiB, +0** | 256 -> 4160 KiB |

So the envelope's 17x is gone: what a 256 KiB body costs the TRANSPORT is now
zero linear memory, four requests in a row. The right column is what READING the
body costs, which is not this boundary -- and is worth remembering before quoting
the left column as "the body is free". (This paragraph used to say the right
column was `read-all` building the body as one string. It is not: a drain that
keeps nothing pays the same. See step 3 above and `.todo/350`.)

A binary body crosses exactly (the `ff fe 41` a `:string` result corrupts), and
the `#-rontolisp-component` guard needed a reader feature that did not exist:
`:rontolisp-component` (`reader/Features.COMPONENT`), because
`:rontolisp-reactor` cannot say "not a component" -- a reactor component has it
too.

*Step 3, the STREAMING HOST, verified (2026-08-13).* The second host the
`:async t` declaration promises is now a pinned fact rather than a design claim:
`WasmReactorStreamingHostE2eTest` puts a `rontolisp:http-handler` program through
the SAME reactor pipeline the synchronous test compiles, then drives it with
`WebAssembly.Suspending` over a `ReadableStream`'s reader,
entered with `WebAssembly.promising`, with the chunks handed over on the
MACROTASK queue -- so a host that had not parked inside `handle-request` could
not have them at all. Eight 2-octet chunks of `こんにちは` (every boundary inside
a code point) come back as the text that was sent, an empty reader is still no
body, and 256 KiB streamed to a handler that drops it leaves linear memory where
it was. Nothing in the compiler changed and no flag differs: which host drives it
is not a build-time question, which is the whole point of the declaration.

*Still open after step 3.* The response body (Phase 3), and making a CHECKED-IN
Worker glue file stream. The second is no longer a question of whether the module
supports it -- the test above is the shape, ~40 lines of JS -- but of which
example should pay for it: a suspending body import forces the promising/queue
serialisation on every request (`.kb/wasm-import.md`, the re-entry guard), and
`httpbin/src/index.js` is deliberately synchronous AND byte-identical in five
directories, so converting it would trade all five directories' concurrency for
a streaming upload none of their handlers needs. `dog-fetcher` already
serialises (its `fetch` import suspends) but its routes are GET-only, so the
streaming path there would be dead code. The honest trigger is `.todo/348`:
once a reactor no longer has to serialise, a streaming glue costs nothing and
every directory can have it.

**And the memory column is NOT closed** -- the boundary is, the drain is not.
The Phase 2b table above blames the right column on `read-all` building the body
as one string; re-measured, that attribution is wrong. A handler that drains the
body chunk at a time and keeps NOTHING pays the same ~15x, because
`%http-utf8-decode-octets` decodes each chunk with a per-character `write-char`
into a `with-output-to-string`, and a WASM string output stream persists a
linear-memory copy plus a 12-byte record PER WRITE. Measured: 256 KiB -> 4.1 MB,
1 MiB -> 15.9 MB, 4 MiB -> 63.0 MB of linear memory, against a flat 256 KiB for
the same body never read. Root cause and the two fix shapes: **`.todo/350`** --
it is not this boundary, and closing it is what makes streaming a body actually
bounded.

**Phase 3 -- the response body, symmetrically.** Today it is one string in
linear memory, so a large or streamed response pays the same way. Same
convention in reverse (`env.writeResponseBody(ptr, len)`, the module choosing
where those bytes sit), and `%http-normalize-response`'s stream arm stops being
drained before sending on this transport.

*Step a, the LISP half, done (2026-08-13)* -- the half that moves no host glue,
exactly as Phase 2a was:

- `%http-reactor-handle` / `-dispatch` (and both `clack.handler.reactor` entries)
  take an optional BODY SINK beside the head: a one-argument chunk writer,
  possibly answering a FUTURE, so a suspending host writer is legal
  (`%http-reactor-write` resolves it through the same `%http-reactor-force` the
  request side uses). Given one the head's `"body"` key is **absent** -- not
  empty: a host must be able to tell "the body crossed out of band" from "the
  body is the empty string" -- and given none nothing changed.
- **a STREAM body is forwarded, not collected** (`%http-reactor-body-out`): the
  transport pulls it chunk at a time straight into the sink. Without a sink it is
  DRAINED into the envelope, which is a fix and not a fallback -- this transport
  used to hand the stream value itself to `json-stringify`.
- the chunks cross BEFORE the head, so a head carrying a `"body"` key WINS over
  anything already written. That is what makes a handler error mid-body
  recoverable: the 500 arm passes no sink and answers its report in band.

Gate, met: the `http-reactor-body-sink` ci-spec case (four backends: a string
body with and without a sink, a stream body both ways, the error arm staying in
band) and `LispEvaluatorAsdfTest.aReactorSinkTakesTheResponseBodyOutOfTheHead`.
`.kb/clack.md` ("The body SINK") has the mechanics.

**Two backend bugs it surfaced, both pre-existing, both fixed here** -- and both
are the reason a stream response body had never worked on this transport:

- **the JVM answered `(consp a-stream)` = T.** A stream is an `Object[3]` there
  and nothing in the cons-shaped predicates excluded it, so `%http-body-string`'s
  `consp` arm caught a stream body before its `streamp` arm could -- on the JVM
  alone (the interpreter and both WASM backends answer nil).
  `JvmEmitHelper.emitAsyncValueExclusion`, gated on `Ctx.mayUseAsyncValues` so an
  async-free program stays byte-identical. `.kb/instance-syntax.md` has it beside
  the instance exclusion it mirrors.
- **`%future-force` trapped in an asyncMode module with no scheduler.**
  `OFF_SCHED_LOOP` was an unreachable stub when the module binds no async-calling
  interface -- but nothing in such a module can suspend, so every future in it is
  settled by the time anything forces it. Forcing is polling there, exactly as on
  the Preview 1 tier (`WasmFutureRuntimeBuilder.buildSyncForce`).
  `.kb/async-await.md`.

*Still open (step b, the WASM boundary).* `env.writeResponseBody(ptr, len)` and
the sink the synthesized bridge builds over it, plus the glue files. One thing
the Lisp half deliberately left for it: an `(unsigned-byte 8)` response body is
already TEXT by the time the sink sees it (`%http-body-string` renders it one
character per octet, which is what every transport that writes the bytes back out
one at a time needs), so a byte-shaped sink must not re-encode it as UTF-8 --
which means `%http-normalize-response` has to stop flattening that arm, the same
way it already keeps a stream.

**Phase 4 -- the examples, and the glue that stops being hand-written. DONE with
step 2 (2026-08-13), except the generation.** Ten of the eleven
`examples/cloudflare-workers/` directories speak this envelope (`hello/` is the
exception: three direct `wasm-export`s, no reactor), through FOUR distinct glue
files -- `httpbin/src/index.js` (byte-identical in five directories),
`hello-clack/src/index.js` (in three), `dog-fetcher/src/index.js` and
`httpbin-component`'s generated glue. The first three moved with the boundary
(each provides `env.readRequestBody` and stops filling `"body"`);
`httpbin-component` keeps the envelope, which is now a documented divergence
rather than a copy left behind. `httpbin/worker.lisp` -- the library-free one --
writes the import and the two transport calls out by hand, which is what that
example is for; its `read-char` drain over the buffered `:raw-body` is unchanged.
Both fixes-while-there landed: `dog-fetcher` sent NO body at all (a POST was
silently dropped) and now sends one, and the httpbin glue reads the body only
when `request.body` is non-null instead of on every non-GET.

What is NOT done: `.todo/340` (generate the suspending host glue). With a uniform
boundary the glue is derivable -- and it is now three near-identical files that
differ only in the arena bracket, which is exactly the drift generation would
stop.

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
