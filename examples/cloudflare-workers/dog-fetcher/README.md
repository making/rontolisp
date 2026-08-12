# dog-fetcher — a Worker that calls out

[`../../net/dog-fetcher.lisp`](../../net/dog-fetcher.lisp)'s proxy shape on
Cloudflare: every request asks [dog.ceo](https://dog.ceo) for a picture and
answers with JSON. It is the first Worker here that does **outgoing** HTTP, and
the only thing that makes it interesting: a `--no-wasi` reactor imports no WASI,
so `rontolisp:fetch` — which is `wasi:http` — is not available. The client is
the Worker runtime's own `fetch`, imported.

Routes come from [tiny-routes](https://github.com/jeko2000/tiny-routes), loaded
as `tiny-routes/lite` exactly as in [`../hello-tiny-routes`](../hello-tiny-routes).

```bash
./build.sh          # worker.lisp -> src/worker.wasm
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

One import, declared in `worker.lisp`:

```lisp
(rontolisp:wasm-import 'host-fetch
                       :from "env" :as "fetch"
                       :params '(:string) :returns :string)
```

and provided in `src/index.js`:

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
  deployed edge, which is why the Lisp is ordinary synchronous code and not an
  `async-defun`. A suspending
  import may only be called on a stack entered through `promising`, so
  `_initialize` must never reach one; here it only defines routes.
- **The envelope is JSON, not a record.** A `wasm-import` carries flat values
  and strings, so the host answers `{"status":200,"body":"..."}` — or
  `{"status":0,"error":"..."}`, which is how a transport failure becomes this
  Worker's 502 instead of a trap. Widening the seam to a method, headers and a
  request body is the same directive with a JSON request string in place of the
  URL.
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
mark/reset bracket assumes it is alone. `index.js` therefore chains calls onto
one promise queue.

The cost is real and measured: eight concurrent `GET /` under `wrangler dev`
complete about 250 ms apart rather than together. On the deployed edge the same
eight finish within 0.27–0.98 s, because the queue is per isolate and Cloudflare
is free to use more than one. It buys correctness, and it costs the isolate
nothing else — everything that is not this module keeps running while a handler
waits.

## No `check.lisp`

The sibling examples each ship one, and drive their handler on the interpreter,
the JVM and wasmtime. This program's client is the host's, so there is nothing
to drive it with off Cloudflare: the module imports `env.fetch`, which a plain
`wasmtime run` cannot satisfy (it would need a `--preload`ed host module), and
the other two backends have no such import at all. `npx wrangler dev` is the
edit/run loop here.

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

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/dog-fetcher/build.sh
```
