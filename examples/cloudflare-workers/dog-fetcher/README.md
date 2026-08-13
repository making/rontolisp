# dog-fetcher — a Worker that calls out

[`../../net/dog-fetcher.lisp`](../../net/dog-fetcher.lisp)'s proxy shape on
Cloudflare: every request asks [dog.ceo](https://dog.ceo) for a picture and
answers with JSON. It is the first Worker here that does **outgoing** HTTP, and
the client is `rontolisp:fetch` itself — the same `(rontolisp:await
(rontolisp:fetch ...))` that runs on the interpreter, the JVM and a `wasi:http`
component. A `--no-wasi` reactor imports no WASI, so `--host-fetch` lowers the
call onto what a Worker host can always provide: its own `fetch`.

Routes come from [tiny-routes](https://github.com/jeko2000/tiny-routes), loaded
as `tiny-routes/lite` exactly as in [`../hello-tiny-routes`](../hello-tiny-routes),
and the application is served with `:server :rontolisp` — the backend that
picks its transport **per target at read time** — so this one `worker.lisp`
runs on every backend, not only on Cloudflare (see
[the same worker.lisp on every backend](#the-same-workerlisp-on-every-backend)).

```bash
./build.sh          # worker.lisp -> src/worker.wasm + src/worker.js
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/
{"dog":"https://images.dog.ceo/breeds/mix/annabelle0.jpg"}

$ curl http://localhost:8787/breed/husky
{"dog":"https://images.dog.ceo/breeds/husky/n02110185_7246.jpg","breed":"husky"}

$ curl -i http://localhost:8787/breed/unicorn
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error":"no such breed"}

$ curl -i http://localhost:8787/anything
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error":"no route for /anything"}
```

`/breed/123` is answered by a different mechanism: the route DECLINES a breed
that is not letters before it can reach a URL, so the catch-all answers it
rather than the upstream. A request that never completes is a 502 — the only
case that is not the upstream's own answer.

## The boundary

No hand-written import and no bespoke envelope any more: `--host-fetch`
(build.sh) injects two imports and lowers every `rontolisp:fetch` onto them —
`env.fetch(request-json) -> response-head-json` for the request and the reply's
head, and `env.readResponseBody(ptr, cap) -> i32` for the reply's body, pulled
a chunk at a time. Same options, same
`(:status <int> :headers <alist> :body <stream>)` answer as every other
backend. The JSON keys are derived from the compiler's own `FetchResponseShape`
record and pinned by `HostFetchLibraryTest` against `src/index.js`, so the two
sides cannot drift.

**And the JavaScript half is not hand-written either.** `--emit-js-glue`
(build.sh) writes [`src/worker.js`](src/worker.js) from the same declarations
the module was built from: the import object, the `(ptr, len)` staging both
ways, the `__ronto_alloc` bracket, the `WebAssembly.Suspending` wrappers, the
`WebAssembly.promising` entry and the one-call-at-a-time queue. It is generated
and checked in, and pinned by `HostGlueEmitterTest`. What is left in
`src/index.js` is what a declaration cannot state — what each host function
*does*, and which of them suspend:

```js
const lisp = instantiate(module, {
  env: {
    fetch: suspending(hostFetch),
    readResponseBody: suspending(readResponseBody),
    readRequestBody: () => { ... },
    writeResponseBody: (chunk) => responseChunks.push(chunk),
  },
});
```

Four things are worth reading twice:

- **A wasm import is a synchronous call and `fetch` is a promise.** JSPI
  (`WebAssembly.Suspending` on the import, `WebAssembly.promising` on the
  export) is what joins them: the whole wasm stack parks until the promise
  settles and resumes with the result. **workerd runs it with no flag and no
  compatibility date opt-in** — verified under `wrangler dev` and on the
  deployed edge. A suspending import may only be called on a stack entered
  through `promising`, so `_initialize` must never reach one; the build prints
  exactly this obligation (and writes it, into `src/worker.js`), and would print
  a warning line naming any fetch its load path reaches. Which entries actually
  suspend is this file's choice, not the module's: `suspending()` marks the two
  that answer promises, and the other two stay plain calls, because the wrapper
  is not free — an import that answers *synchronously* through one still parks
  the stack and returns to the event loop.
- **Awaiting still reads the same.** On the reactor the future `fetch` returns
  is settled the moment the call returns (the stack was parked for the round
  trip to the headers), so `await` never suspends and two fetches never
  overlap — `dog-image` is an ordinary `async-defun`, and the route bodies
  (synchronous tiny-routes functions, where `await` is not legal) simply return
  its FUTURE: the reactor transport resolves a future-valued response at its
  boundary.
- **The body is not in the head.** `env.fetch` answers status and headers; the
  octets come through `env.readResponseBody` as `read-all` asks for them, so a
  large reply never becomes a JSON string and a binary one crosses as the
  octets it is. What that costs in exchange is stated below: a failure *during*
  the body surfaces at the drain, and only one reply body is live at a time.
- **A `:string` result is host-written bytes.** The host allocates with the
  module's exported `__ronto_alloc` and returns `[ptr, len]`; the per-request
  arena reset frees it along with everything else. Nothing may be kept across
  the `await` — growing the module's memory detaches `memory.buffer`. All of
  that is `src/worker.js`'s job now: `hostFetch` in `index.js` takes and answers
  a plain string and never sees a pointer.

## One Lisp call at a time

Everywhere else in these examples the Lisp call is synchronous and an isolate
cannot interleave requests inside it. Suspending changes that: a handler waiting
on dog.ceo returns control to the event loop, and a second request would enter
the same module — the same globals, and the same bump allocator whose
mark/reset bracket assumes it is alone. The module refuses that entry with a
trap (the compiled export carries a re-entry guard), so a host that forgets the
queue sees a failed request, not silently corrupted answers. The generated glue
owns that queue; `index.js` joins it with `lisp.serially(...)`, because the
request body and the response chunks belong to the one call that is running and
a suspended handler would otherwise let the next request move them.

The cost is real and measured: eight concurrent `GET /` under `wrangler dev`
complete about 250 ms apart rather than together. On the deployed edge the same
eight finish within 0.27–0.98 s, because the queue is per isolate and Cloudflare
is free to use more than one. It buys correctness, and it costs the isolate
nothing else — everything that is not this module keeps running while a handler
waits.

## The same worker.lisp on every backend

`:server :rontolisp` resolves the transport when the source is read for a
target: a real socket on the interpreter/JVM, `wasi:http` under `--component`,
and the host-driven `handle-request` export on a `--no-wasi` reactor (the
`#+rontolisp-reactor` leg). `rontolisp:fetch` follows along — the JDK client,
`wasi:http/client`, `env.fetch`. All four, from the repo root:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
W=examples/cloudflare-workers/dog-fetcher/worker.lisp

# 1. interpreter — a blocking server on :8080
java -jar $JAR $W

# 2. JVM class (keep the jar on the classpath)
java -jar $JAR $W -o DogFetcher.class && java -cp $JAR:. DogFetcher

# 3. WASI component under wasmtime serve (the socket flags: clack's own
#    socket leg keeps wasi:sockets in the import surface)
java -jar $JAR $W -o dog-fetcher.wasm --component && \
  wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y dog-fetcher.wasm

# 4. the Worker (this directory): build.sh + wrangler dev, as above
```

Off Cloudflare the reactor build is equally drivable from plain node: the
module's client is `env.fetch`, and node has no JSPI, so a node host answers
*synchronously* — which the boundary equally allows. The sibling
`../../net/dog-fetcher.lisp` (the `rontolisp:http-handler` spelling of the same
program) compiles to the same reactor shape with no edit at all.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program. This is what `build.sh` compiles. |
| [`src/index.js`](src/index.js) | The Worker's own half: what each host function does, and the Request/Response mapping. |
| [`src/worker.js`](src/worker.js) | The boundary, GENERATED by `--emit-js-glue` from worker.lisp's declarations. Do not edit; `./build.sh` rewrites it. |
| `src/worker.wasm` | A build product — run `./build.sh` first. |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../hello-clack`](../hello-clack/README.md#limitations) apply unchanged, plus:

- **One in-flight request per isolate**, as above. Overlapping them needs an
  allocator scope per call, not just a second mark.
- **Started == settled, and settled means the HEAD.** The reactor's fetch
  future is settled at creation (the host call blocked the stack until the
  headers), so two fetches never overlap and a transport failure *before* the
  head signals at the `fetch` call rather than at `await` — the documented
  degenerate-async shape of the Preview 1 backend. A failure *during* the body
  signals at the drain instead, as on every other backend.
- **One live reply body.** The host has one read cursor, moved by each
  `env.fetch`, so starting the next fetch before draining the previous reply
  makes that drain signal rather than answer the new reply's octets.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/dog-fetcher/build.sh
```
