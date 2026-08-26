# dog-relay — a Worker that relays, many at a time

Every request is forwarded to [dog.ceo](https://dog.ceo) and the reply —
status, content type and body — is streamed back to the client **as it
arrives**, a chunk at a time. Where [`../dog-fetcher`](../dog-fetcher) parses
the upstream's answer and builds its own JSON, this one hands the answer
through; and where dog-fetcher serves **one request at a time**, this one is
compiled `--reentrant` and serves them **overlapped on one instance**.

Routes come from [tiny-routes](https://github.com/jeko2000/tiny-routes)
(`tiny-routes/lite`), the application is served with `:server :rontolisp`, and
this one `worker.lisp` runs on every backend (see
[the same worker.lisp on every backend](#the-same-workerlisp-on-every-backend)).

```bash
./build.sh          # worker.lisp -> src/worker.wasm + src/worker.js
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/                 # dog.ceo's /breeds/list/all, relayed
{"message":{"affenpinscher":[],"african":["wild"],"airedale":[], ...

$ curl http://localhost:8787/breed/hound      # /breed/hound/images -- ~50 KB, relayed as it streams
{"message":["https://images.dog.ceo/breeds/hound-basset/n02088238_10005.jpg", ...

$ curl -i http://localhost:8787/breed/unicorn # the upstream's own 404, relayed
HTTP/1.1 404 Not Found
Content-Type: application/json

{"status":"error","message":"Breed not found (main breed does not exist)","code":404}

$ curl -i http://localhost:8787/anything      # ours
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error":"no route for /anything"}
```

## Two flags, and why this Worker asks for both

`build.sh` passes `--host-boundary=streaming --reentrant` on top of
dog-fetcher's `--no-wasi --host-fetch`.

**`--host-boundary=streaming`, because the reply is relayed.** The handler
answers the fetch reply's `:body` STREAM as its own response body and never
reads it. On the streaming boundary the transport pulls each chunk through
`env.readResponseBody` and pushes it out through `env.writeResponseBody` the
moment it has it, so a 50 KB breed listing is on its way to the client while
the rest of it is still on the wire and never exists whole in linear memory.
The default `envelope` boundary would drain the reply into the response head
first.

**`--reentrant`, because a relay is parked time.** A request here is one
upstream round trip and almost no CPU. Serialised — what every module that can
suspend does by default, and what dog-fetcher does — N concurrent clients each
wait for the N-1 relays ahead of them. `--reentrant` makes the module own its
per-call state and drops the re-entry guard, so the Worker runtime may start a
call while another is parked on dog.ceo. Measured with plain node against the
real upstream: six concurrent relays complete in about one round trip
(≈ 400 ms) against ≈ 1.6 s summed.

The two compose only because the streaming body protocol carries a **call
identity** under `--reentrant`: every body import leads with an `:int` id —
`env.readRequestBody(id, ptr, cap)`, `env.writeResponseBody(id, ptr, len)`,
`env.readResponseBody(id, ptr, cap)` — so each pull and push names the relay it
belongs to instead of sharing one host-side cursor. The request's id rides the
envelope's `"call-id"` key and is minted per request by the generated
`worker()`; a fetch reply's id is its own (`"body-id"` in the reply head),
minted per fetch by the generated `defaultHost()`, so replies drain
independently and nothing is superseded.

**And still nothing is hand-written.** `--emit-js-glue` writes
[`src/worker.js`](src/worker.js) from the same declarations: no queue, per-call
body state keyed by id, per-reply readers, and the same
`Request -> envelope -> Response` mapping. It is generated, checked in and
pinned by `HostGlueEmitterTest`. [`src/index.js`](src/index.js) is:

```js
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

## What overlaps, and what does not

`--reentrant` buys **I/O overlap, never CPU parallelism**: one wasm stack runs
at a time, and what overlaps is the time calls spend parked on the upstream.
Inside one call nothing changes either — a fetch future is settled the moment
`fetch` returns, exactly as in dog-fetcher — so `relay` is an ordinary
`async-defun` and the route bodies return its FUTURE for the transport to
resolve. Only the host's view is different: it may enter `handle-request` again
while a previous call is suspended, and the module keeps every call's dynamic
bindings and every call's body cursors apart.

What the relay forwards is the reply's TEXT: `rontolisp:fetch`'s `:body` is a
character stream on every backend, so a binary upstream reply (an image) does
not survive the round trip byte for byte. A binary body a Worker *reads*
(`:raw-body`) or *answers itself* (an `(unsigned-byte 8)` vector) does cross
exactly on this boundary; a fetched one relayed as-is does not, yet.

## The same worker.lisp on every backend

`:server :rontolisp` resolves the transport when the source is read for a
target — a real socket on the interpreter/JVM, `wasi:http` under `--component`,
the host-driven `handle-request` export on a `--no-wasi` reactor — and
`rontolisp:fetch` follows along. From the repo root:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
W=examples/cloudflare-workers/dog-relay/worker.lisp

# 1. interpreter -- a blocking server on :8080
java -jar $JAR $W

# 2. JVM class (keep the jar on the classpath)
java -jar $JAR $W -o DogRelay.class && java -cp $JAR:. DogRelay

# 3. WASI component under wasmtime serve
java -jar $JAR $W -o dog-relay.wasm --component && \
  wasmtime serve -S cli=y -S tcp=y -S inherit-network=y dog-relay.wasm

# 4. the Worker (this directory): build.sh + wrangler dev, as above
```

The reactor build is equally drivable from plain node 24 with
`--experimental-wasm-jspi`: `worker(module)` from `src/worker.js` is the whole
host, and `Promise.all` over its `fetch` is the overlap.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | The whole program. This is what `build.sh` compiles. |
| [`src/index.js`](src/index.js) | Three lines over the generated `worker()`. |
| [`src/worker.js`](src/worker.js) | The boundary, GENERATED by `--emit-js-glue` from worker.lisp's declarations. Do not edit; `./build.sh` rewrites it. |
| `src/worker.wasm` | A build product — run `./build.sh` first. |

## Limitations

The Worker sandbox and `--no-wasi` limitations of
[`../hello-clack`](../hello-clack/README.md#limitations) apply unchanged, plus:

- **A relayed fetch reply is text**, as above; a binary upstream body does not
  cross byte-exact.
- **Overlap is per isolate and per parked time.** Two calls never run Lisp at
  the same moment; a CPU-bound handler gains nothing from `--reentrant`.
