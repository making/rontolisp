# Clack (`(ql:quickload "clack")` + `clack:clackup` on the rontolisp backend)

Clack loads VERBATIM from the Quicklisp dist (clack-20250622-git + lack-20260101-git); `clack:clackup :server :rontolisp` runs on the interpreter, the JVM, WASM `--component`, a reactor host, and a Servlet 6 war. Preview 1 has no incoming TCP (`.kb/tcp-sockets.md`): the program compiles and `clackup` signals the `http-handler` message at CALL time. Out of scope: WebSocket (`clack.socket`), `:swank-port`.

Pins: `ClackE2eTest` (opt-in `RONTOLISP_CLACK_E2E=1`; Docker for pinned wasmtime, embedded Tomcat for the war, network for the first quickload), run twice — a bare handler lambda and a real ROUTING application (tiny-routes + its cookie middleware, unpatched).

## Two routing layers, and why both

**tiny-routes** (`ClackE2eTest` group 2) and **ningle** v0.3.0 + myway + map-set (`NingleE2eTest`, `RONTOLISP_NINGLE_E2E=1`) both load unpatched and serve on all four transports. ningle exercises what tiny-routes does not:

- The application is a CLOS object (`lack-component`): `defmethod call :around` + `call-next-method` + `(setf (find-class '<app>) (find-class 'app))`, not a composed closure.
- Dispatch runs REQUIREMENT closures compiled at route-DEFINITION time (`ningle/route::compile-requirements` closes over a `loop for (name val) on ... by #'cddr` pair) — what `.kb/loop-iteration-heads.md` exists for; before it every requirement answered "unsatisfied".
- It reads every request through `lack-request`: the http-body / fast-http / smart-buffer / circular-streams / quri / yason / trivial-mimes chain, ~2 MB of a ~2.7 MB module. A ningle Worker needed the `--no-wasi` stubs to answer `random` and `getenv` (`.kb/wasm-export-no-wasi.md`). No size opt-in (myway compiles every rule to a cl-ppcre scanner), unlike `tiny-routes/lite`.
- Only ningle parses a request BODY on the normal path (`request-parameters` -> yason, which makes its own stream), so until `make-string-input-stream` existed every JSON body answered 400. `.kb/read-load-streams.md`.
- `ningle:not-found` sets the status and returns nil, so lack's `finalize-response` answers a body LIST holding NIL — the response-contract arm in `.kb/http-server.md`.

Preview 1 cannot serve either, but ningle's ROUTING runs there via `examples/cloudflare-workers/{hello,httpbin}-ningle/check.lisp`. The httpbin one shares no code with the other httpbin Workers: routes assigned in a loop, an `:ANY` fallback per path (405), a `:regexp t` rule whose `:captures` bind, a controller mutating `ningle:*response*`, JSON/form bodies as `request-body-parameters`.

## The handler backend is a built-in shim, found LATE-BOUND by name

`clack-handler-rontolisp.lisp` (`ShimLibraries`/`BuiltinSystems` + `resource-config.json`) defines package `clack.handler.rontolisp` exporting `run`/`stop`. Clack's discovery is `find-handler` -> lack's `find-package-or-load` -> `find-package`, and only on a MISS `asdf:find-system` + `asdf:load-system`, then `(apply (intern "RUN" pkg) ...)`. Consequences:

- **The package is NOT seeded in `PackageRegistry`** (unlike every other shim): seeding satisfies the probe before the system loads and leaves `run` undefined at the apply. The shim carries its own `defpackage` (leaf-module pattern), and the interpreter's builtin-system branch of `loadSystem` uses the RESOLVING `eval(form)`, not `eval(form, globalEnv)`, so it registers.
- **Two system names**: `clack-handler-rontolisp` and `clack.handler.rontolisp` — `find-package-or-load` hyphenates `/` but leaves `.` alone, so the dotted one is what clack asks ASDF for. Both keys map to one resource.
- The interpreter's runtime `asdf:find-system` answers built-in systems (an `asdf:system` metaobject) before load — that hit routes `find-package-or-load` onto `load-system`.
- **The compile paths splice the shim EAGERLY with clack** (`LoadInliner.spliceSystem`, right after system `"clack"`): the probe then reads the baked package table and `CLACK.HANDLER.RONTOLISP:RUN` resolves through the `_lookup` registry (`.kb/symbol-runtime-api.md`).

## run / stop and the stoppable server seam

`run` blocks until the server stops and cleans up in an unwind. Interpreter/JVM ride `rontolisp::%http-server-*` (`RontoHttpServer.startServer/joinServer/stopServer/serverPort`; opaque integer handle, INTERNAL symbols):

```lisp
(let ((server (rontolisp::%http-server-start app port address :raw-body :buffered)))
  (unwind-protect (progn (rontolisp::%http-server-join server) server)
    (rontolisp::%http-server-stop server)))
```

- `%http-server-start` takes the handler as a FUNCTION VALUE (unlike the directive's quoted name), port (0 = ephemeral, `%http-server-port` reads it back) and bind address.
- Default `:use-thread t`: `run` is on a `bt2:make-thread` thread and `clack:stop` DESTROYS it, never calling our `stop` — `destroy-thread` is `Thread.interrupt`, it lands in `joinServer`'s latch await, join returns NORMALLY, the unwind stops that server. `stopServer` is idempotent.
- `:use-thread nil`: `run` blocks forever serving (hunchentoot parity).
- JVM: `JvmHttpServerSeamCompiler` stores the handler in the same `_httpHandlerFn` static slot the directive uses and reuses the injected-`handle(Request)` runtime (`usesHttpHandler` also fires on `%http-server-start`). **ONE Clack server per process.**
- `:use-thread t` works only because `Features` INTERPRETER/JVM include `:thread-support` (`.kb/threads.md`), declared statically like `:unicode`. WASM lacks it, so the default is nil there.

## No env / response bridge

rontolisp's server protocol IS Clack's (`.kb/http-server.md`): env in, Clack response out, built and normalized once in `http-server.lisp` for every backend. The shim hands the app to `%http-server-start` DIRECTLY and asks only for `:raw-body :buffered` — a synchronous bivalent body stream instead of the native asynchronous one, which is what lets lack-request / circular-streams / http-body read a served body. Shared contract: `:remote-addr`/`:remote-port` carry the real peer on interpreter/JVM (nil on the component); duplicate request headers join with `", "`; a bare-string response body and a PATHNAME body (lack-app-file) are REFUSED; of the function-response protocol the DELAYED form works, the streaming writer is refused.

## Transport selection (the one-clackup-source rule)

`:server :rontolisp` = "serve on THIS target's native inbound transport", chosen at COMPILE time by reader features, so ONE source runs on five transports (`examples/net/httpbin-clack.lisp`; `hello-clack.lisp` is the four-form minimum). `:port` reads the `PORT` env var, which only the socket transports look at. The manifest pins four of five legs per file (`war-compile`, `wasm-reactor` are `ExamplesE2eTest` tokens; the interpreter blocks). The FETCH-capable shape rides the same rule (`examples/cloudflare-workers/dog-fetcher/worker.lisp`).

- `#-rontolisp-wasm` — the socket server above.
- `#+(and rontolisp-wasm (not rontolisp-reactor))` — the `rontolisp:http-handler` directive leg: `wasmtime serve` under `--component`, call-time error on Preview 1.
- `#+rontolisp-reactor` (`--no-wasi`, Preview 1 only — the compiler ignores it under `--component` so the feature does too — and `--no-gc`; `Features.WASM_REACTOR`, selected in `RontoLispCli.compileRecorded`) — run stores the app in the SHARED reactor store and leaves the `rontolisp::%http-reactor` marker; the compiler synthesizes the `handle-request` export. `:port`/`:address` ignored, run returns at once. Pinned by `RontoLispCliTest.clackRontolispBackendUnderNoWasi*` + its without-flag twin.
- `#+rontolisp-servlet` (`-o app.war`; `Features.JVM_SERVLET`, `.kb/http-server.md` "The fifth transport") — the container owns the port and the war's top level must RETURN, so run hands the app to the SAME `%http-server-start` seam (in war mode it registers the handler and answers a dead handle) and returns. No bind, no join; `stop` is nil. `:script-name` makes a war under a context path route. Pinned by `RontoLispCliTest.clackRontolispBackendOnAWarRegistersWithoutBindingOrJoining` and the war legs of `ClackE2eTest`/`NingleE2eTest`.
  - **Trap: `:use-thread` is not merely ignored here.** Default `t` spawns a thread for `run`, and the JVM holds it at the class-initialization lock until the war's `<clinit>` returns, so registration landed just AFTER the container looked (3 successes in 10) until `RontoHttpServletInitializer` got a bounded wait and the handler slot was made VOLATILE (`JvmLispCompiler`). The war legs leave `:use-thread` at its default deliberately.

Why a reader feature and not a front-end rewrite: the shim already branches per target, builtin-shim sources are read with the target's features (`ShimLibraries.forms`), and the directive then does not EXIST in a reactor compile. **The feature reflects the TARGET, not the vendor** — re-evaluate if a host type cannot be told apart by compile flags.

## WASM component / Preview 1

The shim's WASI-wasm `run` stores the app and calls the `rontolisp:http-handler` DIRECTIVE with literal quoted `'%app` (a one-line `(funcall *app* env)` indirection — the directive needs a literal quoted name) plus `:raw-body :buffered`, inside a defun body. `HttpLibrary.process` / `HttpHandlerInliner.usesHttpHandler` therefore detect the directive NESTED in a form (quoted data excluded), extract the static handler name for `%serve-handle` export wiring, and lower the call site to nil. The `%serve-dispatch` bridge + `wasm-export` are appended AFTER the program so the handler NAME resolves where the directive was written.

**Trap: the three synthesized names — `%serve-dispatch`, `%serve-request-body`, `%serve-handle` — carry an explicit `cl-user::` qualifier.** The program's last `in-package` is still in effect at the append point, so unqualified they came out as `MY-APP::%SERVE-DISPATCH` while http.lisp (spliced at the HEAD, under `cl-user`) calls the unqualified ones; every `--component` compile ending inside its own package died with `Cannot compile: %SERVE-DISPATCH`. The handler reference stays unqualified (qualifying breaks the user-directive case). Pinned by `ClackE2eTest.tinyRoutesServesOnWasmComponentUnderWasmtimeServe`.

Run flags: `wasmtime serve -S cli=y -S tcp=y -S inherit-network=y` (socket flags because the spliced usocket shim wit-imports wasi:sockets). `:use-thread` is effectively nil; `clack:stop` is meaningless under `wasmtime serve`.

**Preview-1 call-time-error policy**: `rontolisp:http-handler` on Preview 1 is a CALL-time error stub (was a compile error) with the "requires --component" message, as are `stream-read`/`stream-close`/`streamp` when no stream type exists. Both sit in the shim's wasm `run`/`%app`, dead on Preview 1 but compiled whenever clack loads. An uncaught error is a silent trap there, so the E2E pins the message through `handler-case`.

## The host-driven reactor: `http-reactor.lisp` + two designators

The reactor TRANSPORT — one application store, the JSON envelope over `%http-make-env`/`%http-normalize-response`, the handler-case that answers 500, the compile-time marker — is the SHARED internal library `http-reactor.lisp` (`rontolisp::%http-reactor-register`/`-handle`/`-dispatch`; `eval/HttpReactorLibrary`; envelope documented in its header). It serves hosts that call an EXPORTED FUNCTION instead of handing over a socket.

Splicing: the compile path splices it when the program references a `%http-reactor-*` name (`RontoLispCli`, after `HttpReactorInliner`, BEFORE `HttpServerLibrary`). The interpreter lazy-loads through the `RONTOLISP::%HTTP-REACTOR-` function-lookup hook, which must sit BEFORE the broader `%HTTP-` hook. NOT excluded from `LibraryDefunPruner`.

Two designators, both storing into the ONE shared `%http-reactor-app`:

- **`:server :rontolisp` under `#+rontolisp-reactor`** — reactor when the TARGET is.
- **`:server :reactor`** (`clack-handler-reactor.lisp`, package `clack.handler.reactor`, both system spellings in `ShimLibraries.RESOURCES`/`BuiltinSystems`/`resource-config.json`, again NOT seeded) — host-driven on EVERY backend, so a Worker can be developed through `dispatch` on the interpreter.

Two public functions below `clackup`: `handle` `(app request-json &optional body) -> response-json` and `dispatch` `(request-json &optional body) -> response-json`, thin names over `%http-reactor-handle`/`-dispatch` so the designators cannot drift. A program wanting no clack calls `handle` with its own `wasm-export`.

### Body SOURCE

A BODY SOURCE is `nil`, a STRING (buffered), or a PULL THUNK — arity 0, answering the next chunk, `nil`/`""` for end of stream, possibly a FUTURE of one (`%http-reactor-pull` resolves it; this transport is synchronous code where `await` is not legal). The envelope's `"body"` key is the STRING case and the fallback when no source is passed.

- **Normalized at every entry.** A host's "no body" is JSON `null`, which `json-parse` answers as the symbol `NULL` — non-nil and not a string, it used to be FUNCALLED, taking the instance down. `%http-reactor-source` is the one guard (`nil` / string / `functionp`, else no body); both `%http-reactor-raw-body` and `%http-reactor-body-stream` take it.
- **An EMPTY already-buffered source is end of stream at the FIRST read.** `%http-reactor-text-source`'s rule is "never answer `""` before the end"; its string arm broke it, so the two boundaries gave a chunk-counting consumer different counts for the same 204. `sent` starts true for an empty source.
- **An EMPTY source is no body, and costs one look-ahead**: `%http-reactor-raw-body` pulls once before deciding and pushes the chunk back (`%http-reactor-pushback`), preserving upstream's rule that `:raw-body` is nil for a bodiless request in BOTH modes. The same look-ahead lets an empty source FALL BACK to the envelope's `"body"` key (`%http-reactor-request-body`).

### Body SINK

An optional SINK is a function of one chunk, possibly answering a FUTURE (`%http-reactor-write` resolves through `%http-reactor-force`). Given one, `%http-reactor-body-out` writes every chunk to it and the head's `"body"` key is **dropped**; given none, the body rides the head.

- **The key is ABSENT, not empty**, with a sink: "crossed out of band" must be distinguishable from "the empty string".
- **Chunks cross BEFORE the head**, so a head that HAS a `"body"` key WINS over anything already written — that is what makes a handler error mid-body recoverable. The error arm passes no sink.
- **A STREAM body is forwarded, not collected**: with a sink the transport pulls it chunk at a time (`%http-reactor-stream-chunk`), each chunk an OCTET vector, so a relayed fetch reply crosses byte-exact. Without a sink it is DRAINED (`%http-reactor-body-drain`, the synchronous twin of `%http-drain`) into the envelope.
- **Octets stay octets.** `%http-body-string` returns an `(unsigned-byte 8)` body unchanged, like a stream, because only the transport knows whether it can write bytes; flattening lets every UTF-8-encoding transport double the high bytes (`ff fe 41` -> `c3 bf c3 be 41`). The ONE arm that cannot take octets is `%http-reactor-body-out`'s no-sink arm (a JSON string key): it renders them as the lenient UTF-8 TEXT they spell (`%http-reactor-body-envelope-text`).

Pinned by ci-spec `http-reactor-body-sink` (four backends: string body with and without a sink, stream body both ways, the error arm in band, an octet body reaching the sink unflattened while the head gets the text rendering), ci-spec `http-response-normalizer`, `LispEvaluatorAsdfTest.aReactorSinkTakesTheResponseBodyOutOfTheHead`.

Two pre-existing backend bugs surfaced here, both pinned by ci-spec `stream-new-builds-a-pull-stream-on-every-backend`:

- **The JVM answered `(consp a-stream)` = T** (a stream is an `Object[3]`), so `%http-body-string`'s `consp` arm caught a stream body before its `streamp` arm. Fixed by `JvmEmitHelper.emitAsyncValueExclusion`, gated on `Ctx.mayUseAsyncValues` so an async-free program is byte-identical.
- **`%future-force` trapped in an asyncMode module with no scheduler** (`OFF_SCHED_LOOP` was an unreachable stub). Nothing there can suspend, so forcing is POLLING, as on Preview 1 where it compiles to `_p1_future_await` (`WasmFutureRuntimeBuilder.buildSyncForce`).

### The WASM boundary: a head export and two body imports

A host across a wasm boundary can pass neither closure nor sink, so `HttpReactorInliner` synthesizes, on the Preview 1 core-module backend, beside the `handle-request` export:

```lisp
(rontolisp:wasm-import '%reactor-read-body :from "env" :as "readRequestBody"
                       :params '() :returns :bytes :async t)
(rontolisp:wasm-import '%reactor-write-body :from "env" :as "writeResponseBody"
                       :params '(:bytes) :returns :void :async t)
```

plus `%reactor-read-chunk` (over `%http-reactor-buffer 65536` + `%http-reactor-chunk`), `%reactor-write-chunk` (over `%http-reactor-octets`) and `%reactor-dispatch` passing both as `#'` values. Boundary: `handle-request(headPtr, headLen) -> (ptr, len)`, `env.readRequestBody(ptr, cap) -> n`, `env.writeResponseBody(ptr, len)`. Five load-bearing decisions:

- **`:bytes`, not `:string`** (`.kb/wasm-import.md`): the string decoder is non-validating so a binary body cannot cross, and a `:string` RESULT is host-allocated per call. `:bytes` is caller-buffered and the wrapper pops its staging, so a body costs NO linear memory (256 KiB dropped leaves `memory.buffer.byteLength` where it was; the envelope held it ~17 times over). Same outbound.
- **The DIRECTION flips, same rule**: in is a `:bytes` RESULT into a module-supplied buffer, out a `:bytes` PARAMETER the wrapper stages and pops — the CALLER owns the memory either way, so no host holds a pointer across the call. The write import has no result at all.
- **The imports are CALLED, never taken as `#'value`**: the suspending-import report follows calls, and an escaped import widens to "any export may suspend". Hence `%reactor-write-chunk` is a defun.
- **`:async t`**, so the HOST picks the strategy over one module: synchronous answers and suspending inside the call (`WebAssembly.Suspending` entered through `promising`, calls serialised) are both legal. It also puts the re-entry guard on the exports.
- **Preview 1 core modules, only a REACTOR, and only under `--host-boundary=streaming`** — three separate questions:
  - CAN: `--component` keeps both bodies in the envelope (a `wasm-import` is refused there, as is the packed array behind `:bytes`); re-evaluate when the component path grows a `list<u8>` lift. `--no-gc` keeps them and cannot serve anyway.
  - IS: the marker is not a reactor's alone (`clack-handler-reactor` is host-driven everywhere — every `check.lisp` drives `dispatch` in-process), and there the host is `wasmtime run`, which satisfies no `env.*` import; declaring one made those modules refuse to INSTANTIATE. `HttpReactorInliner.process` takes a `reactor` flag (`--no-wasi`) beside the backend.
  - ASKED FOR: the DEFAULT is `envelope`; the split costs not bytes (~1% either way) but host-side STATE (four host functions and three cursors against none). `compiler/HostBoundary` is the vocabulary; the flag joins the other two in `bodyOutOfBand`. **Trap: a rebuild without the flag moves bodies IN BAND, destroying a binary one.**

**Under `--reentrant` both imports lead with an `:int` CALL ID** — `env.readRequestBody(id, ptr, cap)`, `env.writeResponseBody(id, ptr, len)`. The host mints an id per request, the envelope carries it as `"call-id"` (`ReactorEnvelope.CALL_ID_KEY`), and the transport closes it over the body thunks at the ONE place it parses the envelope (`%http-reactor-bind-source`/`-bind-sink` in `%http-reactor-handle`), so consumers stay on the id-less 0-arity source and the id-less shape is byte-identical with the flag off (`.kb/wasm-import.md`). The shared receive buffer (`%http-reactor-chunk-buffer`) survives the overlap because `%http-reactor-chunk` copies out before anything can suspend.

A HAND-WRITTEN reactor writes those forms itself (`examples/cloudflare-workers/httpbin/worker.lisp`), guarded by `#+rontolisp-body-imports` (`reader/Features.BODY_IMPORTS`), with the envelope fallback under the negation. That feature REPLACED `#+(and rontolisp-reactor (not rontolisp-component))`, which named targets rather than the imports and so got `--no-gc` wrong and could not follow a flag.

Pinned by `WasmReactorBodyE2eTest` (node sharing the module's memory: a character straddling every chunk boundary, a reader answering 0, the envelope fallback, a binary body, flat linear memory), `WasmReactorResponseBodyE2eTest` (outbound: absent `"body"` key, a binary body crossing exactly, a stream body forwarded as its own chunks, a handler failing MID-BODY whose 500 wins over chunks already taken, 256 KiB out flat), `HttpReactorInlinerTest`'s bridge-shape tests incl. the WASI-command one, `RontoLispCliTest.aComponentBuildReadsTheSourceWithTheComponentFeature`, and `WasmReactorStreamingHostE2eTest` (a `WebAssembly.Suspending` over a `ReadableStream` reader entered via `promising`: the module PARKS inside `handle-request` and resumes). No checked-in glue streams yet — a suspending body import forces promising/queue serialisation per request, so the generated `worker()` answers those imports SYNCHRONOUSLY; `.todo/348` is the trigger.

**Memory**: the boundary is flat both ways, and DRAINING is flat too — `%http-utf8-decode-octets` decodes per character with `write-char` and a WASM string output stream appends into one GC byte buffer, so a chunk-at-a-time drain of a 4 MiB body costs ~850 BYTES of arena and no linear growth (it used to cost ~15 bytes per character). A drain that KEEPS the body costs the body: `read-all` builds one string through the reused scratch. `.kb/read-load-streams.md`.

**A chunk is a string OR OCTETS.** The TEXT drain reads through ONE adapter, `%http-reactor-text-source`; `:stream` mode's `%http-reactor-body-stream` reads through the byte-shaped mirror `%http-reactor-octet-source` (octet chunk untouched, string chunk UTF-8 encoded) and decodes nothing, since every HTTP body stream answers octets (`.kb/fetch-http.md`). Two rules the text adapter owns:

- **An open UTF-8 sequence is carried into the next chunk** (`%http-reactor-decode-chunk` over `http-server.lisp`'s `%http-utf8-complete-end`/`%http-utf8-decode-octets`): a straddling multi-byte character is the NORMAL case, and per-chunk decoding answers two malformed characters per split. A body ENDING mid-sequence keeps the lenient rule.
- **It never answers `""` before the end** — an empty answer IS end of stream to both consumers, and a chunk whose bytes were all carried over decodes to nothing. It pulls again.

**A host READER is a source in two lines** (`%http-reactor-buffer` / `%http-reactor-chunk`) — the `read(2)` shape: the caller owns one buffer, the reader fills up to its length and answers the octet count (possibly a FUTURE), 0 is end of stream. Both halves live in the transport because both are load-bearing: the buffer is allocated once and REUSED for every chunk of every request, and the count-to-chunk step copies out before the next read overwrites. Reuse is sound because a reactor answers one request at a time and the re-entry guard refuses the overlap. **Second consumer**: `--host-fetch`'s reply body is a pull thunk over these same two calls plus `%http-reactor-body-stream` (`.kb/fetch-http.md`), sharing the one buffer.

### `:raw-body` mode is REGISTERED with the app

`%http-reactor-register app [:buffered]` (the `%http-reactor-buffered` flag), because a reactor has no `http-handler` call at run time:

- **`:buffered`** — what both handler shims' `run` registers and what `clack.handler.reactor:handle` always passes: the source is drained (`%http-reactor-body-octets`) into `http-server.lisp`'s bivalent Gray stream so `read-byte`/`file-position` work. The drain answers OCTETS, not text: the Gray stream IS a byte stream, so a binary upload is byte-exact; decoding to text and letting `%http-body-stream` re-encode doubled every high byte. `%http-body-stream` takes EITHER spelling; `%http-reactor-body-text` stays for a text-only caller.
- **`:stream` (default)** — `%http-reactor-body-stream` builds a first-class pull stream of OCTET chunks with `rontolisp::%stream-new`, so the portable `(await (read-all (getf env :raw-body)))` drain works and answering the stream as a response body relays byte-exact. Before it, `HttpReactorInliner.lowerHttpHandler` DROPPED the `:raw-body` argument and the reactor always buffered, so `examples/net/httpbin.lisp` answered 500 on `--no-wasi` for any request with a body.

An empty or absent body stays `nil` in BOTH modes. `--no-gc` has neither: it rejects the async surface by name and `%http-drain`/`%http-serve-request` are `async-defun`s, so it cannot carry the HTTP transport at all; its reactors are the `wasm-export`-only ones (`examples/cloudflare-workers/hello`).

Pinned by ci-spec `http-reactor-body-source` (all four backends: a pull thunk, an in-band string and no body in the default mode; the same pull source through `:buffered`; an OCTET pull source through both modes with every character straddling a boundary; both modes over a HOST READER filling a four-octet buffer; an empty reader answering both nil and the envelope fallback; a BINARY body read back with `read-byte` through `:buffered`), six `LispEvaluatorAsdfTest` reactor-body tests, `HttpReactorInlinerTest.theLoweredHttpHandlerDirectiveKeepsItsRawBodyMode`.

### The transport's contract, arm by arm

- `%http-reactor-handle` is `(app request-json &optional body buffered sink) -> response-head-json`. It converts nothing: it builds the raw tuple and calls `%http-make-env` / `%http-normalize-response` like every other transport. Only the JSON envelope is its own.
- **A FUTURE-valued application answer is resolved at the boundary**: `futurep` + `rontolisp::%future-force`, the FUNCTION spelling of await's resolve, legal in this synchronous transport where the `await` special form is not (interpreter `awaitValue`, JVM `_await`, non-asyncMode WASM `_p1_future_await`, asyncMode the `_sched_loop` force; `LispNames` documents the table). That lets an `async-defun` handler, or a tiny-routes route returning its future, run on a reactor.
- **The bare `rontolisp:http-handler` DIRECTIVE lowers to this transport under `--no-wasi`** (`HttpReactorInliner.lowerHttpHandler`): it becomes `(progn (%http-reactor-register (function name) [:buffered]) (%http-reactor '%http-reactor-dispatch "handle-request"))` in the CLI, before the serve-mode switch reads the program (so `--component --no-wasi` compiles a zero-import reactor component instead of the serve+no-wasi ctor error). The port is dropped unevaluated; the `:raw-body` pair rides the registration. `examples/net/dog-fetcher.lisp` unedited is the pin (`RontoLispCliTest`; the reactor-component invoke case in `WasmLispCompilerIntegrationTest`).
- **The envelope is an API**: `{method, target, headers, body, scheme, remote-addr}` in (plus optional `script-name`, the mount point as a RAW prefix of `target`; absent = root-mounted), `{status, headers, body}` out. Two load-bearing parts: `target` is the RAW request target (path and query joined and encoded — `%http-make-env` owns the split, and a pre-split path leaves `:query-string` nil), and response `headers` cross as an ARRAY of `[name, value]` pairs, not an object, so a repeated `Set-Cookie` survives. The pairs are built into a VECTOR (`%http-reactor-header-pairs`): a headerless response must cross as `[]`, and json-stringify renders an empty LIST as `false`, which the JS Headers constructor throws on.
- **`%http-reactor-handle` CATCHES and answers 500** — an uncaught Lisp error on a reactor is a trap that takes the instance down. Consequence: loading the reactor library puts `handler-case` in the module, so the program compiles in EH mode.
- **`run` stores the app; the EXPORT is synthesized.** `rontolisp:wasm-export` needs a LITERAL name at compile time, which a `clackup`-only program cannot supply, so `run` carries the marker `(rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch "handle-request")`. `eval/HttpReactorInliner` lowers it to nil on the WASM backends and APPENDS `(defun %reactor-dispatch (json) (rontolisp::%http-reactor-dispatch json))` plus its `wasm-export`. BOTH shims' runs carry that marker naming the SAME dispatcher, so first-wins is not a choice (`HttpReactorInlinerTest.twoIdenticalMarkersSynthesizeOneBridge`). It is a SEPARATE marker, not an overload of `http-handler`, because that directive means "bind a socket" on WASI targets.
- **The reactor shim's marker is `#+rontolisp-wasm` and nothing defines it.** On interpreter/JVM the shims never read the form (the host calls `dispatch` as an ordinary function), which is why `dispatch` is EXPORTED rather than compiler-only and why `run` does not `defun` a `handle-request`. **Trap (`HttpReactorInliner.declaresExport`)**: the marker fires for any WASM program that merely quickloads the shim, so for the PRE-clackup shape (the user writes the `wasm-export` and calls `handle`) it would append a SECOND `handle-request` export — V8: `CompileError: Duplicate export name 'handle-request'`. Synthesis is SKIPPED when the program already declares that export name (`:as` alias or defaulted); the marker is lowered either way. Pinned by `HttpReactorInlinerTest.doesNotSynthesizeWhenTheProgramAlreadyExportsThatName` and its `:as` twin.
- **`clackup`'s two `format t` calls are why `--no-wasi` stdout is a sink.** Upstream clackup prints a banner (`(and (not use-thread) (not silent))`, and `use-thread` defaults nil on WASM so it always fires) and `clack.handler:run` prints a debug NOTICE (`debug` defaults `t`) — third-party source, and binding `*standard-output*` inside `run` is impossible (the banner precedes the apply). Under `--no-wasi` the `fd_write` stub discards instead of trapping (`.kb/wasm-export-no-wasi.md`).
- **The one keyword the examples still pass**: `:use-thread nil`, because interpreter/JVM HAVE `:thread-support` and clackup would otherwise apply `run` on another thread and race the next form. `:use-default-middlewares nil` was dropped once the standard-stream `symbol-value`s were bound on the compile paths: lack's `backtrace` middleware prints via handler-bind even on a CAUGHT error, to `*error-output*` — a discarding sink under `--no-wasi`, real stderr elsewhere — and the response stays correct.

### Why the designator names the TRANSPORT, not a vendor

Renamed from `:server :cloudflare-workers` / `clack-handler-cloudflare-workers` with no compatibility alias. Nothing in the shim is Cloudflare-specific (four delegations to `%http-reactor-*`), and deployment to Workers is `:rontolisp` plus `--no-wasi`. What remains is "host-driven on EVERY backend", as every other name in the lineage says (`http-reactor.lisp`, `%http-reactor-*`, `#+rontolisp-reactor`, `Features.WASM_REACTOR`). **Why not to alias the old name**: it is free for a backend surfacing something only one host has (a Worker's `env`/`ctx`), layered on this one.

Pins: `LispEvaluatorAsdfTest` (`theReactorHandlerShim*`), `HttpReactorInlinerTest`, `RontoLispCliTest`, and the `check.lisp` of `examples/cloudflare-workers/{hello-clack,httpbin-clack,httpbin-tiny-routes}` driving `dispatch` on interpreter, JVM and wasm-GC.

## The clack-free twin

`examples/cloudflare-workers/httpbin` is a hand-written adapter: `handle` is thirty lines over `%http-make-env`/`%http-normalize-response` plus the envelope, answering the same five endpoints as `httpbin-clack` over the SAME envelope, so the pair is a controlled measurement of what clack costs. The clack one takes the generated `worker()`; the library-free one keeps a hand-written host because it exports `handle-request` by hand and only the SYNTHESIZED bridge is recognised as the envelope's entry point (`.kb/wasm-import.md`). Clack costs module size (roughly 1.5x) and a little `_initialize` startup, not per-request time.

The hand-written half still pays for being a PORTABLE Clack application: naming `%http-make-env` splices `http-server.lisp` (env builder, response normalizer, and the buffered `:raw-body` Gray stream — `HttpServerLibrary.referencesBufferedBody` keeps that half when the program mentions `%http-body-stream`).

**Directory layout**: `httpbin-clack` holds `worker.lisp` (`:server :reactor`) + a `check.lisp`. `httpbin-clack-one-source` holds NO Lisp: its `build.sh` compiles `net/httpbin-clack.lisp` itself (`:server :rontolisp`), and needs no `check.lisp` because on the interpreter that file binds a real socket.

## Compile-path enablers that are NOT clack-specific

- **Nested/computed `asdf:load-system` / `ql:quickload` / `asdf:find-system` compile** (were compile errors): lack's `find-package-or-load` has all three inside a defun. They resolve to the spliced asdf runtime's real defuns over the baked `%asdf-registry%`; the historical stub lowerings (`LispMacroExpander.expandRuntimeFindSystem`) survive only as the no-pipeline fallback in the expression compilers. `.kb/asdf.md`.
- **`with-open-file` with a non-native option VALUE (`:if-exists :append`) expands to a call-time stub** instead of throwing at expansion — lack-middleware-backtrace's file-output branch.
- **`FreeVarAnalyzer` walks `typecase`/`etypecase` clause HEADS as type specifiers** (keyform + bodies only) in both walks — the backtrace middleware's `(or pathname string)` head inside a capturing lambda was read as a variable named PATHNAME. Walked structurally, not expanded. Pinned by `JvmLispCompilerTest.compileAndRunEtypecaseInsideACapturingLambda`.
- **`PATHNAME` resolves as a CL name** so `(typecase app ((or pathname string) ...))` under `(in-package :clack)` resolves to CL's rather than `clack::pathname`. It moved from `PackageRegistry.CL_TYPES` to `CL_FUNCTIONS` once `pathname` became a FUNCTION too (`.kb/pathnames.md`).
