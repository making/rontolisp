# dog-fetcher — a Worker that calls out

[`../../net/dog-fetcher.lisp`](../../net/dog-fetcher.lisp)'s proxy shape on
Cloudflare: every request asks [dog.ceo](https://dog.ceo) for a picture and
answers with JSON. It is the first Worker here that does **outgoing** HTTP, and
the client is `rontolisp:fetch` itself — the same `(rontolisp:await
(rontolisp:fetch ...))` that runs on the interpreter, the JVM and a `wasi:http`
component. A `--no-wasi` reactor imports no WASI, so `--host-fetch` lowers the
call onto the one import a Worker host can always provide: its own `fetch`.

Routes come from [tiny-routes](https://github.com/jeko2000/tiny-routes), loaded
as `tiny-routes/lite` exactly as in [`../hello-tiny-routes`](../hello-tiny-routes),
and the application is served with `:server :rontolisp` — the backend that
picks its transport **per target at read time** — so this one `worker.lisp`
runs on every backend, not only on Cloudflare (see
[the same worker.lisp on every backend](#the-same-workerlisp-on-every-backend)).

```bash
./build.sh          # worker.lisp -> src/worker.wasm (--no-wasi --host-fetch)
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
(build.sh) injects one import, `env.fetch(request-json) -> response-json`, and
lowers every `rontolisp:fetch` onto it — same options, same
`(:status <int> :headers <alist> :body <string>)` answer as every other
backend, with `:body` an eager string that `rontolisp:read-all` passes through.
The JSON keys are derived from the compiler's own `FetchResponseShape` record
and pinned by `HostFetchLibraryTest` against `src/index.js`, so the two sides
cannot drift. The host's half is small:

```js
fetch: new WebAssembly.Suspending((ptr, len) => hostFetch(exports, ptr, len)),
// ...
handleRequest: WebAssembly.promising(exports["handle-request"]),
```

Three things are worth reading twice:

- **A wasm import is a synchronous call and `fetch` is a promise.** JSPI
  (`WebAssembly.Suspending` on the import, `WebAssembly.promising` on the
  export) is what joins them: the whole wasm stack parks until the promise
  settles and resumes with the result. **workerd runs it with no flag and no
  compatibility date opt-in** — verified under `wrangler dev` and on the
  deployed edge. A suspending import may only be called on a stack entered
  through `promising`, so `_initialize` must never reach one; the build prints
  exactly this obligation, and would print a warning line naming any fetch its
  load path reaches.
- **Awaiting still reads the same.** On the reactor the future `fetch` returns
  is settled the moment the call returns (the stack was parked for the whole
  round trip), so `await` never suspends and two fetches never overlap —
  `dog-image` is an ordinary `async-defun`, and the route bodies (synchronous
  tiny-routes functions, where `await` is not legal) simply return its FUTURE:
  the reactor transport resolves a future-valued response at its boundary.
- **A `:string` result is host-written bytes.** The host allocates with the
  module's exported `__ronto_alloc` and returns `[ptr, len]`; the per-request
  arena reset frees it along with everything else. Nothing may be kept across
  the `await` — growing the module's memory detaches `memory.buffer`, so
  `index.js` reads its argument before awaiting and re-reads the buffer after.

## One Lisp call at a time

Everywhere else in these examples the Lisp call is synchronous and an isolate
cannot interleave requests inside it. Suspending changes that: a handler waiting
on dog.ceo returns control to the event loop, and a second request would enter
the same module — the same globals, and the same bump allocator whose
mark/reset bracket assumes it is alone. The module refuses that entry with a
trap (the compiled export carries a re-entry guard), so a host that forgets the
queue sees a failed request, not silently corrupted answers. `index.js`
therefore chains calls onto one promise queue.

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
| [`src/index.js`](src/index.js) | The Worker: `../hello-clack/src/index.js` plus the JSPI bridge and the queue. |
| `src/worker.wasm` | A build product — run `./build.sh` first. |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../hello-clack`](../hello-clack/README.md#limitations) apply unchanged, plus:

- **One in-flight request per isolate**, as above. Overlapping them needs an
  allocator scope per call, not just a second mark.
- **Started == settled.** The reactor's fetch future is settled at creation
  (the host call blocked the whole stack), so two fetches never overlap and a
  transport failure signals at the `fetch` call rather than at `await` — the
  documented degenerate-async shape of the Preview 1 backend.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/dog-fetcher/build.sh
```
