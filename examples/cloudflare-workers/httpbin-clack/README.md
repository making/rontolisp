# httpbin-clack — a Clack application on Cloudflare Workers

The same five echo endpoints as [`../httpbin`](../httpbin), and the same
[Clack](https://github.com/fukamachi/clack) application: `read-body` down to
`*app*` is that directory's text verbatim. What differs is the last form.
`clack:clackup` installs the application on the Worker here; there, thirty
hand-written lines do it and clack never loads at all.

Nothing in `worker.lisp` mentions Cloudflare, or a Worker, or an export: it is
`ql:quickload`, an ordinary Clack application and a `clackup` line.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl 'http://localhost:8787/get?a=1&b=two'
{"method":"GET","headers":{"host":"localhost:8787",...},"path":"/get","args":{"b":"two","a":"1"}}

$ curl -X POST -d '{"name":"rontolisp"}' http://localhost:8787/post
{"data":"{\"name\":\"rontolisp\"}","args":{},"json":{"name":"rontolisp"},"method":"POST",...}
```

A wrong method answers 405 with the one it wanted, an unknown path 404, and a
body that does not parse leaves `"json": null`.

## What's in here

| File | Purpose |
| --- | --- |
| [`worker.lisp`](worker.lisp) | **The whole program**: quickload, the five endpoints, `clackup` |
| [`check.lisp`](check.lisp) | The same handler driven without Cloudflare — on the interpreter, the JVM and the WASM backends |
| [`build.sh`](build.sh) | `--no-wasi --optimize=size` over `worker.lisp` |
| [`src/index.js`](src/index.js) | The whole Worker. **Byte-identical** to `../httpbin/src/index.js` |
| `src/worker.wasm` | A build product — run `./build.sh` first |

Module sizes are measured rather than quoted here:
[size report](../../../size-report/results/cloudflare-workers.md).

## What this answers that `../httpbin` does not

| | `../httpbin` | this |
| --- | --- | --- |
| The application | a Clack application | **the same text**, verbatim |
| How it reaches the Worker | thirty hand-written lines and an explicit `wasm-export` | `clackup`, with the export synthesized |
| Reads as | a Worker program that happens to speak Clack | **every other Clack program** |
| clack in the module | none | what the tree-shaker keeps of clack and lack |

The trade is honest: you pay for clack and lack to be in the module so that the
file reads like an ordinary Clack program rather than like a Worker. What that
is worth in bytes is in the size report; what it is worth per request is
[nothing](#what-it-costs).

## The backend is a handler backend, not example code

Nothing here writes an adapter. `clack-handler-reactor` is a built-in Clack
handler backend, and `:server :reactor` means **host-driven on every backend**:
its `run` stores the application where a socket backend would bind a listener.
That is what lets [`check.lisp`](check.lisp) drive this Worker — the same
`dispatch` the Worker's export calls — on the interpreter and the JVM as well.

[`../httpbin-clack-one-source`](../httpbin-clack-one-source) is the other
designator: `:server :rontolisp` serves on whatever the compile *target*'s
native transport is, which is what lets one file be a socket server locally and
a Worker here without an edit. Both store into the same reactor machinery, so
the two cannot drift.

### Where the export comes from

`clackup` applies the backend's `run`, and a reactor owns no socket, so `run`
stores the application and returns. What replaces the socket is a WASM export —
and `rontolisp:wasm-export` needs a **literal** name at compile time, which a
program whose whole Worker half is a `clackup` call cannot give. So `run`
carries a marker and the compiler answers it, appending the equivalent of

```lisp
(defun %reactor-dispatch (json) (rontolisp::%http-reactor-dispatch json))
(rontolisp:wasm-export '%reactor-dispatch :as "handle-request"
                       :params '(:string) :returns :string)
```

after the program. `%http-reactor-dispatch` runs the stored application over the
JSON envelope; it is the **shared** reactor machinery
([`http-reactor.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-reactor.lisp)),
and `clack.handler.reactor:dispatch` is a thin public name over the same
functions — which is why `check.lisp` exercises what the Worker exercises.

One upstream keyword matters beyond sockets: **`:use-thread nil`**. The WASM
backends are single-threaded, so `clackup` already defaults to `nil` there, but
the interpreter and the JVM have threads and a script wants the foreground.
`clackup`'s default middlewares stay **on**: lack's `backtrace` middleware
prints to `*error-output*`, which under `--no-wasi` is a sink.

### It converts nothing

The backend is thin because everything a transport does is already factored out
of the server, in
[`http-server.lisp`](../../../src/main/resources/am/ik/rontolisp/eval/http-server.lisp).
rontolisp's server protocol *is* Clack's, so every backend goes through the same
two functions:

```lisp
(rontolisp::%http-make-env raw)          ; positional raw tuple -> the Clack environment
(rontolisp::%http-normalize-response r)  ; whatever app returned -> (status header-alist body-string)
```

The JDK server, the WASI component, the socket legs and the reactor machinery
all meet there, so the percent-decoding, the `?` split, the header lowercasing,
the `Host` split, the content-length parsing, the `:raw-body` stream and the
response normalizer come for free — and cannot drift from what a *served*
request sees.

The JSON envelope `src/index.js` speaks — and the two fields it has to get
right, both of which fail quietly — is
[documented in `../httpbin`](../httpbin/README.md#the-envelope-and-two-fields-the-javascript-side-must-get-right).
It is the same envelope here.

## Developing it without Cloudflare

`dispatch` is an ordinary function, so the whole Worker runs locally on every
backend the compiler has:

```bash
rontolisp check.lisp                                  # interpreter
rontolisp check.lisp -o Check.class && java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. Check
rontolisp check.lisp -o check.wasm && wasmtime run -W gc -W exceptions=y check.wasm
```

[`examples.yaml`](../../examples.yaml) pins all three, so a divergence between
this directory and `../httpbin` — the same application, a different installer —
shows up as two manifest cases disagreeing.

The report that appears on standard error for the unparseable-body probe is
lack's `backtrace` middleware, which prints even for an error the application
catches. On the Worker that report goes to a discarding sink and the `200` with
`"json": null` still comes back.

## What it costs

Measured on node 24 (V8, workerd's engine family) driving the byte-identical
boundary code of [`src/index.js`](src/index.js) against each directory's
`src/worker.wasm`, over the same requests:

| | `../httpbin` | this |
| --- | --- | --- |
| imports | zero | **zero** — the Worker instantiates with `{}`, no WASI shim |
| exports | `memory`, `_initialize`, `__ronto_alloc`, `__ronto_alloc_mark`, `__ronto_alloc_reset`, `handle-request` | **identical**, which is why one `src/index.js` serves both |
| `WebAssembly.Module` compile | 0.3 ms | 0.8 ms — and on Cloudflare *no request pays it* |
| `_initialize`, cold | 4.5 ms | **5.0 ms** — clack's entire load time, `clackup` included |
| warm `GET /get` | 0.039 ms | **0.038 ms** |
| warm `POST /post` | 0.060 ms | **0.058 ms** |
| linear memory after 44,000 requests | 262,144 B | 262,144 B |

**The per-request rows are the same to the noise floor.** Everything clack costs
on a reactor is module size and startup — and startup is paid once per isolate.
When this directory switched from a hand-written export to `clackup`, the module
grew 9% and `_initialize` roughly doubled, while warm `GET` and `POST` did not
move at all.

Those per-request figures are the Lisp call plus the string boundary with V8
warm; the first call of a fresh isolate is ~40 ms while V8 tiers the module up.
On the real edge all five endpoints answer correctly, verified after deploying;
`wrangler deploy` reported a Worker Startup Time of 26 ms (14 ms before
`clackup`). End-to-end `curl` from this side of the Pacific settles at 50-85 ms,
which is network time — the Lisp share is the 0.05 ms above.

The linear-memory row is flat because of the
`__ronto_alloc_mark`/`__ronto_alloc_reset` bracket in `src/index.js`;
[`../httpbin`](../httpbin/README.md#two-heaps-wasm-gc-collects-one-of-them-you-collect-the-other)
explains why it is needed.

## Middleware, `lack:builder`, request bodies

`clackup`'s default `backtrace` middleware is in this module and active, so
"middleware works" is not hypothetical. Neither is the body/params stack:
`(ql:quickload "lack-request")` loads on a `--no-wasi` build, and a
`lack:builder` around this application reads a query string and a urlencoded
POST body the way it does anywhere else.

That took a fix rather than a discovery, and it is the shape every heavy library
has. The chain `lack-request -> http-body -> fast-http -> smart-buffer` ends at
one upstream form — smart-buffer names its temporary directory with a top-level
`(random ...)` over `uiop:default-temporary-directory` — and on a `--no-wasi`
module both halves used to be `unreachable` stubs, firing at *load* time inside
`_initialize` before any export existed. Both are answered now: the module
carries its own `random` generator (seeded from `crypto` by `src/index.js`), and
`getenv` reports the empty environment a reactor really has.

`lack-middleware-session` followed the same rule: it reads the clock while it
loads, so `src/index.js` hands the time over through `__ronto_set_time`. Add
`--host-random` — the session id is `rontolisp:random-bytes`, which a fixed-seed
generator must not stand in for — and a `(:session)` builder serves a real
session cookie and recognises it on the next request.

**One caveat if you write your own `lack:builder` stack here**: a `--no-wasi`
build then prints a standing `WITH-OPEN-FILE is reachable` warning. It is a
false alarm — `builder` returns its composed application through `reduce`, so
the compiler can no longer see that what reaches `clackup` is a function rather
than a pathname, and `clackup`'s "the app is a file to load" branch stops being
provably dead. The module is correct and the branch is never taken.

## Why `clackup` prints, and why that is fine here

`clackup` writes two lines unconditionally — its startup banner and
`clack.handler:run`'s debug NOTICE — and they are upstream clack's, not ours. On
a `--no-wasi` module there is no stdout to write them to. They used to **trap**
the instance at `_initialize`, which is why this example could not call
`clackup` at all; now standard output and error are a **sink** under
`--no-wasi`, so the bytes are simply discarded.

That is a deliberate policy rather than a patch for clack: a reactor host hands
the module no file descriptors, so discarding loses only the bytes, while the
alternative was killing the instance for a log line. It generalized into the
rule the whole `--no-wasi` surface follows: a stub answers when the answer is
true of the module, and refuses when answering would only invent data.

Locally the two lines are visible on real stdout. Pass `:silent t :debug nil` to
suppress them.

## Limitations

Everything below is the Worker sandbox or the `--no-wasi` build, and each
applies to [`../httpbin`](../httpbin/README.md#limitations) identically: no
standard input (it traps), no filesystem, no `rontolisp:fetch`. `print` and
`format t` do not trap but their output is discarded; `random` works on a
module-local generator seeded from `crypto`; and the clock is whatever
`src/index.js` writes through `__ronto_set_time` — it advances between requests,
holds still inside one, and `(sleep n)` signals.

One more is specific to the clack directories: a runtime `(ql:quickload ...)`
cannot work either. The `ql:quickload` form in the source is resolved at
**compile** time and inlined into the module, which is why the first
`./build.sh` needs network and later ones do not.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/httpbin-clack/build.sh
```
