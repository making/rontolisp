# rontolisp examples

Practical, self-contained rontolisp programs. Unless noted otherwise, each one
runs identically on all three backends (interpreter / JVM / WASM).

The programs are grouped by theme, one directory per group:

| Directory | Programs |
| --- | --- |
| [`console/`](console) | Algorithms and console I/O — pure, cross-backend |
| [`ml/`](ml) | Numerical computing and machine learning (arrays, `linalg`) |
| [`deep-learning-from-scratch/`](deep-learning-from-scratch) | The book *Deep Learning from Scratch* (ゼロから作るDeep Learning) ch02-ch06, ported |
| [`net/`](net) | Sockets, HTTP servers and JSON web services |
| [`jvm/`](jvm) | `java:` interop and Swing GUIs (JVM only) |
| [`rainbow/`](browser/rainbow), [`wasm-browser/`](browser/wasm-browser), [`webgl-*/`](browser/webgl-common), [`minesweeper/`](browser/minesweeper), [`hiragana/`](browser/hiragana), [`wit-component/`](browser/wit-component) | Browser demos (compile to WASM, run in a page) |
| [`count-vowels/`](count-vowels), [`wit/world/`](wit/world) | Embedding a rontolisp Wasm module in a host; implementing a WIT world |
| [`wit/keyvalue/`](wit/keyvalue) | The other direction: *calling* a WIT interface, with a different implementation behind it per backend |
| [`wit/lisp-calls-rust/`](wit/lisp-calls-rust), [`wit/rust-calls-lisp/`](wit/rust-calls-lisp), [`wit/pipeline/`](wit/pipeline) | Across languages: Lisp and Rust components composed into one with `wac`, calling each other through WIT (a direction each, plus a three-component `wac compose` chain) |
| [`db/`](db) | PostgreSQL over the real cl-postgres driver and postmodern on top of it — queries, CRUD, S-SQL, and a cl-who web app |
| [`asdf/`](asdf), [`wasmcloud/`](wasmcloud), [`cloudflare-workers/`](cloudflare-workers) | Third-party libraries and platform templates |
| [`wasm-size/`](wasm-size) | How big the compiled `.wasm` actually is, next to the same programs in C / Rust / Zig / Moonbit / Wado |

Assuming the executable JAR has been built
(`./mvnw clean spring-javaformat:apply package`), set:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
```

## Console & algorithms — `console/`

| File | What it demonstrates |
| --- | --- |
| [`nqueens.lisp`](console/nqueens.lisp) | Backtracking search (N-Queens): recursion, list manipulation, functional accumulation, ASCII board output |
| [`life.lisp`](console/life.lisp) | Conway's Game of Life: a console front-end that `(load ...)`s the rendering-free core (`life-core.lisp`) -- a 30x24 toroidal grid backed by a 2-D `make-array`, neighbour counting with wraparound, and per-generation ASCII rendering |
| [`sorting.lisp`](console/sorting.lisp) | Quicksort and merge sort over number lists, parameterized by a first-class comparator; cross-checked against the built-in `sort` |
| [`calc.lisp`](console/calc.lisp) | A tiny prefix-arithmetic interpreter: a recursive evaluator over an alist environment, cross-checked against the built-in `eval` |
| [`mandelbrot.lisp`](console/mandelbrot.lisp) | ASCII Mandelbrot set: floating-point arithmetic and nested loops (no transcendental functions) |
| [`mandelbrot-nogc.lisp`](console/mandelbrot-nogc.lisp) | The `--no-gc` companion to `mandelbrot.lisp`: returns the rendered grid as a `:string`, its export typed by a checked-in world ([`mandelbrot_component.wit`](console/mandelbrot_component.wit)) via `rontolisp:wit-export`. One directive, two builds: a plain MVP module (no wasm-GC) whose host reads the string out of linear memory, and a `--component` one where `wasmtime --invoke` just returns it |
| [`line-numbers.lisp`](console/line-numbers.lisp) | A `cat -n` style file tool: `with-open-file`, `read-line`, `write-line`, `format nil`, line/character counts |
| [`parse-numbers.lisp`](console/parse-numbers.lisp) | Numeric-column parsing and character classification: `parse-integer`, `char`, `alpha-char-p`, `digit-char-p` over file lines |
| [`sieve.lisp`](console/sieve.lisp) | Sieve of Eratosthenes: boolean `make-array` as the sieve, `aref`/`(setf (aref ...))`, list accumulation via `push`/`reverse`, and prime factorization with `sqrt`/`ceiling` |
| [`hanoi.lisp`](console/hanoi.lisp) | Tower of Hanoi: classic recursive puzzle solver with both a printing variant and a list-returning variant (`&optional` argument), plus `expt` for the move count formula |
| [`roman.lisp`](console/roman.lisp) | Roman numeral encoder/decoder: bidirectional conversion (1↔I, 4↔IV, …, 3999↔MMMCMXCIX) with association-list lookup tables, `concatenate`, and a full 3999-value round-trip correctness check |
| [`word-frequency.lisp`](console/word-frequency.lisp) | Word frequency counter: hash-table accumulation, `string-downcase`, `alpha-char-p`-based tokenization, `sort` with a custom comparator, and `maphash` iteration |
| [`contact-book.lisp`](console/contact-book.lisp) | Contact book using `defstruct`: tagged-list structs with `setf`-able accessors, `&key` lambda lists, hash-table storage, and `maphash`-based lookup |
| [`error-handling.lisp`](console/error-handling.lisp) | Typed conditions on a tiny bank account: `define-condition` with slots/`:reader`s/`:report`, `handler-case` dispatch by class hierarchy (+ `:no-error`), `ignore-errors`, `unwind-protect` cleanup on the error path (the audit log records refused withdrawals too), non-fatal `signal`, and `typecase`/`with-slots` over condition objects. **Interpreter/JVM only** -- every WASM backend rejects `handler-case`/`unwind-protect` at compile time |
| [`l-system.lisp`](console/l-system.lisp) | L-system (Lindenmayer system) fractal generator: string-rewriting via hash-table rule dispatch, `&rest` variadic arguments, and character-frequency analysis (Sierpinski triangle, Koch curve, Dragon curve) |

## Numerical & machine learning — `ml/`

| File | What it demonstrates |
| --- | --- |
| [`nn.lisp`](ml/nn.lisp) | Feed-forward neural network learning XOR via backpropagation: vectors as rank-1 arrays and weight matrices as rank-2 `make-array`s, with `aref`, `(setf (aref ...))` and `incf`/`decf` for in-place weight updates |
| [`nn-vec.lisp`](ml/nn-vec.lisp) | `nn.lisp` rewritten over the `vec` and `linalg` packages instead of hand-written loops: `vec:matvec` for the forward pass, Hadamard products for the deltas, `linalg:transpose`/`outer`/`emap` for the weight updates, single-float (`#f`) packed arrays throughout |
| [`simd-dot.lisp`](ml/simd-dot.lisp) | The smallest program that shows what `--simd` does: one `vec:dot` over 1024 doubles, four thousand times, and nothing else. The vector is `0.0 .. 1023.0`, so the answer is an exact integer that lane-order folding cannot change -- only the elapsed time moves (interpreter 2.59 s -> 2.3 ms, wasm-GC 273 -> 2.4 ms) |
| [`simd-gemv.lisp`](ml/simd-gemv.lisp) | The two kernels `--simd` accelerates, and the two an LLM inference engine spends its time in: `vec:matvec` (GEMV) and `vec:dot`. A hundred steps of "project through a matrix, rescale to unit RMS" on a 256x256 single-float matrix. Deterministic (it prints `argmax` indices, not floats), so the same numbers come out with and without acceleration -- only the elapsed time changes: wasm-GC 467 -> 3.9 ms, interpreter 4.6 s -> 15 ms. See the [SIMD acceleration guide](https://making.github.io/rontolisp/docs/en/guides/simd-acceleration.html) |
| [`simd-gemv-nogc.lisp`](ml/simd-gemv-nogc.lisp) | `simd-gemv.lisp`'s inner loop as a `--no-gc` reactor module: no `_start`, no printing -- the host calls the exported `fingerprint` function (`wasmtime run --invoke fingerprint gemv.wasm 100` prints `85`, the same dominant direction as every other backend). One `vec:matvec-into` + one `vec:dot` per step over a rank-2 packed single-float matrix; the `-into` kernels keep the never-freed bump heap at exactly three blocks. Compile with `--no-gc` (a v128-free MVP module that runs even with the SIMD proposal disabled) or `--no-gc --simd` (the same loop as `f32x4` lanes -- 20000 steps: ~600 ms -> ~120 ms) |
| [`tiny-llm.lisp`](ml/tiny-llm.lisp) | A 2-layer transformer decoder -- llama2's `forward()` with the tokenizer and the weight loader taken away: RMSNorm, Q/K/V projections, causal self-attention over a KV cache, softmax, SwiGLU feed-forward network, residuals, classifier head, greedy `argmax` decode. Thirteen GEMVs per forward pass, so it is where `--simd` earns its keep (native interpreter 11.3 s -> 20 ms, wasm-GC 891 -> 7.8 ms). Deterministic: it prints token ids, and they must not change with acceleration. The KV cache stores **V transposed**, which is what keeps the attention-weighted sum a single `vec:matvec` |
| [`mlp.lisp`](ml/mlp.lisp) | Generalized multi-layer perceptron for 2-D circle classification, built on the same array-based vector/matrix representation as `nn.lisp` |
| [`maze-rl.lisp`](ml/maze-rl.lisp) | Tabular Q-learning that solves a grid maze: a hash-table Q-table keyed by `(row col action)`, idiomatic `random`-based epsilon-greedy exploration, and an ASCII rendering of the learned path. **Non-deterministic:** because `random` is unseeded and per-backend, the exact path and value count differ on each run and backend (the algorithm always converges to a valid route) |
| [`linear-regression.lisp`](ml/linear-regression.lisp) | Least-squares polynomial fitting with the `linalg` package: a Vandermonde matrix built with `make-array`/`expt`, the normal equations solved via `linalg:transpose`/`matmul`/`solve`, and exact rational coefficients and residuals (identical on every backend) verified with `linalg:array-equal` |
| [`deep-digits.lisp`](ml/deep-digits.lisp) | A small deep neural network, numpy-style: a 15 -> 16 -> 16 -> 10 leaky-ReLU MLP classifying 5x3 pixel digit bitmaps, trained by full-batch matrix backpropagation (`linalg:matmul`/`transpose`/`emap`) with 1/t learning-rate decay -- fully deterministic (fixed-seed LCG init, no transcendental functions, integer-scaled loss output), so all backends print identical output |
| [`numerical-calculus.lisp`](ml/numerical-calculus.lisp) | Discrete calculus with `linalg:diff` and `linalg:gradient` (numpy's `np.diff`/`np.gradient`): Fibonacci differences, the constant second difference of the squares, per-row matrix differences, projectile velocity/acceleration recovered from height samples, and derivative estimates at unit, scalar and non-uniform coordinate spacing -- every sample is a polynomial at integer coordinates, so the results are exact and byte-identical on every backend |
| [`heat3d.lisp`](ml/heat3d.lisp) | Heat diffusion in a 5x5x5 voxel grid with rank-3 arrays: 3-subscript `make-array`/`aref`, the `#nA` printed syntax, `row-major-aref` flat scans (with flat-index decoding and `array-row-major-index`), rank-generic `linalg` operations (`reshape`/`add`/`sum`/`amax`/`array-equal`) over rank-3 tensors, and exact rational arithmetic that conserves the total heat as *exactly* 1000 on every backend |

## Deep Learning from Scratch — `deep-learning-from-scratch/`

A chapter-by-chapter port of the MIT-licensed sample code of the book *Deep
Learning from Scratch* (ゼロから作るDeep Learning, O'Reilly Japan) by Koki
Saitoh — ch02 perceptrons through ch06 training techniques (optimizers,
weight initialization, Batch Normalization, Dropout, weight decay,
hyperparameter search), with the book's `common/` library rebuilt on the
`linalg:` package (axis reductions, the seeded `linalg:randn`/`choice` RNG,
`take-rows`/`gather`/`one-hot` indexing) and CLOS layer classes
(`forward`/`backward` generics). MNIST scripts need a one-time
`./download-mnist.sh`; every script runs on all four backends from that
directory (WASM needs `--dir .`), and `--simd` speeds the training runs up
without changing a byte of their output (everything is `#d` double-float).
See [`deep-learning-from-scratch/README.md`](deep-learning-from-scratch/README.md)
for the per-program table.

## Networking, HTTP & services — `net/`

| File | What it demonstrates |
| --- | --- |
| [`echo-server.lisp`](net/echo-server.lisp) | TCP echo server on port 7777: `rontolisp:tcp-listen`/`tcp-accept` in an accept loop, echoing lines back through the standard stream functions (`read-line`/`write-line`/`close` work on socket handles) until the peer closes. On WASM it needs `--component` plus `wasmtime run ... -W exceptions=y -S tcp=y -S inherit-network=y`; server and client can each run on a *different* backend |
| [`echo-client.lisp`](net/echo-client.lisp) | TCP echo client for `echo-server.lisp`: `rontolisp:tcp-connect`, then pipes stdin lines to the server and prints each reply. Same backend notes as the server |
| [`http-hello.lisp`](net/http-hello.lisp) | Minimal HTTP/1.1 server on port 8080, `curl`/browser-compatible: reads the CRLF request line and headers with plain `read-line` (one trailing `\r` is stripped on every backend), answers with `Content-Length` and `Connection: close`, and keeps a running request counter. Same WASM notes as `echo-server.lisp` |
| [`https-hello.lisp`](net/https-hello.lisp) | The TLS version of `http-hello.lisp`: the same minimal HTTP/1.1 server, served over **HTTPS** on port 8443 via `rontolisp:tls-listen` (a PKCS12 keystore + password; the header comment shows the one-line `keytool` command that generates a self-signed one). Accepted sockets handshake on the first read, so everything after the listen call is the plain-TCP code unchanged. Interpreter/JVM only -- TLS is a compile error on WASM. Talk to it with `curl -k https://127.0.0.1:8443/` |
| [`http-handler.lisp`](net/http-handler.lisp) | The `rontolisp:http-handler` hello world: a handler function takes the Clack environment plist (`:request-method`/`:path-info`/`:query-string`/`:headers`/`:raw-body`/...) and returns a Clack response list `(status headers body)`, the body a list of strings -- no hand-rolled HTTP parsing. Serves on the interpreter/JVM (blocking on port 8080) and, with `--component`, as a WASI HTTP component under `wasmtime serve -W gc=y -W exceptions=y` (wasmCloud and Spin canary host it too). See the [Serving HTTP guide](../doc/en/guides/http-handler.md) |
| [`http-handler-cl-who.lisp`](net/http-handler-cl-who.lisp) | The `http-handler.lisp` hello world rendering its response with **cl-who** (the real upstream (X)HTML library, loaded via `asdf:load-system` -- run with `--system-path src/test/resources/cl-who`): `with-html-output-to-string` expands the markup DSL at macro-expansion time and `esc` escapes the request path at run time. Same interpreter/JVM/`--component` backends and run commands as `http-handler.lisp` |
| [`httpbin.lisp`](net/httpbin.lisp) | A miniature **httpbin**-style echo service on `rontolisp:http-handler`: `/get`, `/post`, `/put`, `/patch` and `/delete` respond with a JSON document describing the request -- query args, headers, the raw body and (when the body is JSON) its parsed value -- built with `rontolisp:json-stringify`/`json-parse`, plus 405 on a wrong method and 404 on an unknown path. Same backends and run commands as `http-handler.lisp` |
| [`httpbin-clos.lisp`](net/httpbin-clos.lisp) | The **CLOS** flavour of `httpbin.lisp`: the echo envelope is a `defclass` (the POST variant extends it), so `rontolisp:json-stringify` serializes each instance as a JSON object with the slots in definition order, while the dynamic-key `args`/`headers` stay hash-table slots (nesting as objects). Byte-identical JSON output to `httpbin.lisp` -- and to jzon, which serializes a `standard-object` the same way. `GET /` additionally renders an HTML index page listing the routes, built with the real **cl-who** (`ql:quickload`) |
| [`httpbin-jzon.lisp`](net/httpbin-jzon.lisp) | The **jzon** flavour of `httpbin.lisp`: the same program with the JSON parsed and rendered by the real `com.inuoe.jzon` (loaded via `ql:quickload`) instead of `rontolisp:json-*`. Since `rontolisp:json-*` is a subset of jzon, only the two call sites change; `rontolisp:plist-hash-table` and the `null` sentinel work unchanged -- a worked example of outgrowing the built-in JSON into the full library |
| [`httpbin-clack.lisp`](net/httpbin-clack.lisp) | The **Clack** flavour of `httpbin.lisp`: the same five echo endpoints served through `clack:clackup` on the real clack (`ql:quickload`, the built-in `:server :rontolisp` handler backend) instead of the `rontolisp:http-handler` directive. Since rontolisp's server protocol *is* Clack's, only the edges change -- the application is a plain function value rather than a literally quoted defun name, and clack's `:raw-body` is a **synchronous** stream (`nil` when there is no body), so the body is drained with `read-char` and the handler is an ordinary `defun` instead of an `async-defun` awaiting `read-all`. See the [Clack guide](../doc/en/guides/clack.md) |
| [`magic-8-ball.lisp`](net/magic-8-ball.lisp) | The classic Spin tutorial's **Magic 8 Ball JSON API** on `rontolisp:http-handler`: ask a yes/no question via `GET /?question=...` or a POSTed body (raw text or JSON `{"question": ...}`) and it answers with one of the twenty canonical replies drawn with `random`, as JSON built with `rontolisp:json-stringify`. Missing question 400, unknown path 404. Same backends and run commands as `http-handler.lisp` -- inside a serve component `random` works because the preview1 bridge maps it to `wasi:random` |
| [`dog-fetcher.lisp`](net/dog-fetcher.lisp) | wasmCloud's **dog-fetcher** example shape: `rontolisp:fetch` *inside* a `rontolisp:http-handler` handler (the classic proxy/aggregator). Every `GET /` asks the dog.ceo API for a random dog picture and answers `{"dog": "<image url>"}` built with `rontolisp:json-parse`/`json-stringify`; upstream failure 502, unknown path 404. Same backends and run commands as `http-handler.lisp` (the serve host provides the `wasi:http/client` import that carries the outbound fetch by default) |
| [`linalg-api.lisp`](net/linalg-api.lisp) | A linear-algebra **JSON web service** on `rontolisp:http-handler`, combining the `linalg` package with `rontolisp:json-parse`/`json-stringify`: `POST /solve` solves a linear system a.x = b (exact Gaussian elimination, determinant included) and `POST /fit` least-squares-fits a polynomial through posted sample points via the normal equations, with input validation answering 400 (non-object body, non-square or singular matrix, too few points). Stateless, so it behaves identically on all backends -- integer inputs are solved exactly. Same backends and run commands as `http-handler.lisp` |
| [`kv-server.lisp`](net/kv-server.lisp) | A miniature **Redis-compatible** in-memory key-value server on port 6379: speaks enough RESP2 that the real `redis-cli` works (`PING`/`SET`/`GET`/`DEL`/`EXISTS`/`INCR`/`KEYS`/`DBSIZE`/`QUIT`), and accepts Redis-style inline commands so `telnet`/`nc 127.0.0.1 6379` work too. Hash-table state survives across connections; protocol framing, `cond` dispatch and reply encoding are all plain `read-line`/`write-line` over the socket handle. Same WASM notes as `echo-server.lisp` |
| [`kv-server-tls.lisp`](net/kv-server-tls.lisp) | The TLS version of `kv-server.lisp`: the same mini-Redis, serving TLS on port 6380 (like a real Redis with `--tls-port`) via `rontolisp:tls-listen`. Talk to it with `redis-cli --tls --insecure -p 6380`. Interpreter/JVM only -- TLS is a compile error on WASM |

The [`net/http-handler/`](net/http-handler) directory holds a `spin.toml` manifest
for the `http-handler.lisp` component: `spin build && spin up` compiles it with
the `rontolisp` binary and serves it on `:3000`. It needs the
**[Spin canary](https://github.com/spinframework/spin/releases/tag/canary)**
build (4.1.0-pre0+, wasmtime 47); released 4.0.2 speaks the older
`wasi:http@0.3.0-rc-2026-03-15` snapshot and cannot run it -- see the note in
the file.

## Third-party libraries & platform templates

| Directory | What it demonstrates |
| --- | --- |
| [`asdf/`](asdf) | Loading REAL third-party libraries through `asdf:load-system`: unmodified [split-sequence v2.0.1](https://github.com/sharplispers/split-sequence), [parse-number v1.8](https://github.com/sharplispers/parse-number), [cl-utilities v1.2.4](https://common-lisp.net/project/cl-utilities/), [cl-who v1.1.5](https://github.com/edicl/cl-who), [assoc-utils](https://github.com/fukamachi/assoc-utils), [cl-base64 v3.4](https://github.com/darabi/cl-base64), [md5 v2.0.4](https://github.com/pmai/md5), [cl-ppcre v2.1.2](https://github.com/edicl/cl-ppcre), [com.inuoe.jzon v1.1.4](https://github.com/Zulu-Inuoe/jzon), [ironclad v0.61](https://github.com/sharplispers/ironclad) (SHA-256/HMAC/PBKDF2/HKDF/SCRAM slice) and [uax-15 v0.1.3](https://github.com/sabracrolleton/uax-15) sources, exercised on all four backends (interpreter / JVM / WASM Preview 1 / `--component`); the directory README has the per-backend run commands and expected output |
| [`wasmcloud/`](wasmcloud) | Ports of the **wasmCloud Rust templates** to `rontolisp:http-handler`, one directory per template (hello world, routed handler, outgoing-HTTP proxy, in-memory key-value API, and the TCP service + HTTP API pair of `service-tcp`), each with a `.wash/config.yaml` so `wash dev` builds and serves it directly; the directory README has a template-by-backend support matrix |
| [`cloudflare-workers/`](cloudflare-workers) | Running on **Cloudflare Workers** (`npx wrangler deploy`), as five independent Worker projects that answer five different questions. [`hello/`](cloudflare-workers/hello) is the floor: three `rontolisp:wasm-export`ed functions a Worker calls like JavaScript ones, `--no-gc`, **563 bytes**, zero imports, no shim and no allocator code. [`hello-clack/`](cloudflare-workers/hello-clack) is the floor for the OTHER kind of Worker: a real Clack application in three forms -- `ql:quickload`, one `defun`, `(clack:clackup #'app :server :cloudflare-workers ...)` -- with no Worker-specific code in the Lisp at all, because the `:cloudflare-workers` handler backend stores the app and the compiler synthesizes the exported entry point from a marker its `run` leaves behind. [`httpbin/`](cloudflare-workers/httpbin) is the Cloudflare port of [`net/httpbin.lisp`](net/httpbin.lisp) -- the same five echo endpoints, but reached through one exported `handle-request` (JSON string in, JSON string out) instead of `rontolisp:http-handler`, which is what brings the `__ronto_alloc_mark`/`_reset` arena bracket that keeps a resident instance's linear memory flat (measured over 20 000 requests); `--no-wasi` still leaves the module importing *nothing*, and `handler-case` lets it return `"json": null` for an unparseable body the way the original could not. [`httpbin-clack/`](cloudflare-workers/httpbin-clack) answers the portability question the other two cannot: its handler is a real **Clack application**, and `worker.lisp` is [`net/httpbin-clack.lisp`](net/httpbin-clack.lisp) VERBATIM down to `app` with that file's `clackup` line carrying different arguments -- the whole Cloudflare port is `:server :cloudflare-workers`, so the environment plist in / Clack response list out means the same function also runs on hunchentoot, on woo and under `wasmtime serve`, and running the upstream file curls the identical application on a real server for comparison with `wrangler dev`; the Worker writes no adapter and declares no export at all -- the bridge is `clack-handler-cloudflare-workers`, a built-in Clack handler backend (the sibling of `clack-handler-rontolisp`) whose `handle` rides the same `%http-make-env` / `%http-normalize-response` entry points every rontolisp transport meets in, and whose `run` leaves the compiler a marker to synthesize the exported entry point from; and the price is the whole of clack and lack in the module (365 KB gzip, 4x `httpbin/`). [`httpbin-component/`](cloudflare-workers/httpbin-component) builds `httpbin/`'s `app.lisp` as a component and transpiles it with `jco`: the canonical ABI does delete all the memory code, at the price of 247 KB of generated glue, three hand-written WASI stubs, three non-obvious jco flags, and no way to run top-level forms. Settled along the way, all verified under `wrangler dev`: workerd runs wasm-GC *and* wasm exception handling with no flags, and forbids runtime WebAssembly compilation -- which is what rules out jco's default output |

## Java interop / GUI (JVM only) — `jvm/`

These drive real Java APIs through the `java:` interop package, so they run on
the **JVM only** — interpret them (`java -jar $JAR ...`) or compile them to a
`.class` (`-o Prog.class && java Prog`); not the WASM backend (which cannot
lower a java object) and not interpreted in the GraalVM native binary (which
has no reflection metadata for the interop classes) — and need a machine with a
display. See the
[Java interop guide](../doc/en/guides/java-interop.md).

| File | What it demonstrates |
| --- | --- |
| [`java-interop.lisp`](jvm/java-interop.lisp) | A minimal Swing window built directly through `java:new`/`java:call`/`java:field`/`java:proxy` -- a button whose `ActionListener` is a rontolisp lambda wrapped in a dynamic proxy |
| [`swing.lisp`](jvm/swing.lisp) | A small reusable Swing grid-window helper library, written entirely on top of `java:` (no bespoke Java class) and wrapped in its own `swing` package; the GUI demos splice it in with `(require :swing "swing.lisp")` and call the qualified names (`swing:grid-window`, `swing:paint`, ...) |
| [`life-gui.lisp`](jvm/life-gui.lisp) | Conway's Game of Life animated in a Swing window: loads the same `life-core.lisp` as `life.lisp` plus the `swing` package, and steps the world on a `javax.swing.Timer` |
| [`minesweeper/minesweeper-swing.lisp`](browser/minesweeper/minesweeper-swing.lisp) | Minesweeper on the desktop: loads the same `minesweeper-core.lisp` as the browser build (only the drawing differs) and paints a clickable Swing label grid via the `swing` package |

## Browser demos (compile to WASM, run in a page)

These are directories rather than single files: a Lisp program is compiled to
`.wasm` and driven from plain HTML/JavaScript via a tiny WASI shim — except
[`wit-component/`](browser/wit-component), which loads a WebAssembly *component*
and needs no glue at all.

| Directory | What it demonstrates |
| --- | --- |
| [`wit-component/`](browser/wit-component) | The first rontolisp **component** in a browser: an interactive Mandelbrot/Julia explorer whose page supplies *nothing* — no `WebAssembly.instantiate`, no import object, no WASI shim, no `__ronto_alloc`, no `(ptr, len)` decoding. A hand-written WIT world types the five exports, `rontolisp:wit-export` checks the program against it, `--no-gc --component --optimize` compiles a ~2.5 KB import-free component, and `jco transpile` turns it into one self-contained ES module the page just `import`s. The honest counterpoint (why the demo is `--no-gc`, and what a wasm-GC component still cannot do in a browser) is in its README |
| [`rainbow/`](browser/rainbow) | Per-character rainbow text: HSV↔RGB color-space conversion and shortest-arc hue interpolation done entirely in Lisp, compiled ahead of time to a `--no-wasi` WebAssembly-GC reactor (no imports) and driven from a page that colors your text as you type through a single `rainbow-html(string) -> string` export |
| [`wasm-browser/`](browser/wasm-browser) | Running a rontolisp-compiled `.wasm` in the browser from plain HTML + JavaScript, including feeding stdin from the page |
| [`minesweeper/`](browser/minesweeper) | A playable Minesweeper: the rules (flood fill, win/lose) live in a shared `minesweeper-core.lisp` that both a browser build (compiled to a `--no-wasi` WebAssembly reactor, HTML rendering) and a [Swing build](browser/minesweeper/minesweeper-swing.lisp) load -- only the drawing differs |
| [`hiragana/`](browser/hiragana) | A 46-class handwritten-hiragana recognizer (the full gojuon): the ch07 SimpleConvNet of [`deep-learning-from-scratch/`](deep-learning-from-scratch) trained offline in Lisp on real handwriting (Kuzushiji-49) plus synthetic multi-font glyphs, its weights read back at startup from an RLW1 `weights.bin`, and driven from a `<canvas>` you draw on through one `recognize(bitmap) -> string` export |
| [`webgl-triangle/`](browser/webgl-triangle) | The WebGL hello world and the smallest `rontolisp:wasm-import` program: Lisp compiles two shaders and draws one colored triangle through ten imported host functions -- no exports, no frame loop; the page just calls `_initialize()` |
| [`webgl-cube/`](browser/webgl-cube) | Hello 3D: a rotating cube whose perspective projection and rotation matrices are computed in Lisp every frame (4x4 matrix math on `make-array`s); bulk floats (geometry, the mat4 uniform) cross the boundary through a small staging array |
| [`webgl-galaxy/`](browser/webgl-galaxy) | A spiral galaxy whose WebGL pipeline is driven entirely from Lisp: the GLSL shaders live in the Lisp source, and Lisp compiles, links, buffers and issues every draw call through 32 host functions declared by a WIT (`webgl-common/gl.wit`) — JavaScript is one-line bindings generated from it, and the HUD |
| [`webgl-heat3d/`](browser/webgl-heat3d) | The rank-3 array showcase in the browser: the page's whole state is one `(n n n)` `make-array` — every frame Lisp deposits heat at two orbiting sources with three-subscript `(setf (aref ...))`, diffuses it across the voxel lattice, normalizes the colors with the rank-generic `linalg:amax`, and projects every voxel itself (same one-line WebGL host boundary as `webgl-galaxy`) |
| [`webgl-robot-arm/`](browser/webgl-robot-arm) | A 3-D robot arm that reaches for wherever you click and closes its three-finger gripper on arrival (drag orbits the camera, scroll zooms): damped-least-squares Jacobian IK solves the joint chain every frame (`linalg:matmul`/`transpose`/`solve` on the damped normal equations; a HUD toggle switches to the matrix-free FABRIK, or to the analytic closed form whose forward kinematics walks a chain of 4x4 homogeneous transforms) -- a rigid tool link included, so the grasp point between the fingertips is what lands on the click -- the hand glides along a minimum-jerk trajectory (`10u^3 - 15u^4 + 6u^5`), and Lisp tessellates the lit cylinders/spheres and the RGB = XYZ axis arrows itself -- the camera's view-projection is a rank-2 `linalg:matmul` product, click-ray unprojection included; gestures arrive through exported functions, and JavaScript is one-line bindings and the HUD |
| [`webgl-platformer/`](browser/webgl-platformer) | A one-stage 3D platformer played with W/A/S/D + Space: the whole game -- gravity, jump buffering and coyote time, per-axis AABB collision against the level blocks, enemy patrols with the stomp-or-die rule, coin pickups, the goal flag and the follow camera -- lives in the Lisp source, which also tessellates every rotated box of the world, the robot explorer and the walkers each frame; JavaScript is one-line bindings, keyboard forwarding and the HUD |
| [`webgl-battlefront/`](browser/webgl-battlefront) | A one-arena snow-battle played Minecraft-style (W/A/S/D + Pointer-Lock mouse aim, click to attack, F to swap weapon): every rule lives in the Lisp source -- the movement and third-person aim camera, the glowing blaster bolts, the lightsaber swing that both hits and *deflects* incoming fire, and the stormtrooper / AT-AT / Vader AI (bring the walkers down and the boss wakes; his blade deflects blaster bolts, so finish him with your own) -- which also tessellates you, every enemy and each rotated box each frame, the glowing sabers and bolts a second additive-blended pass; JavaScript is one-line bindings, the Pointer-Lock mouse and the HUD |
| [`webgl-common/`](browser/webgl-common) | Not a demo but the shared `gl` package the WebGL demos above (except the deliberately self-contained `webgl-triangle`) splice in with a compile-time `(require :gl "../webgl-common/gl.lisp")`: the WebGL2 `rontolisp:wasm-import` boundary, the enum constants and the `gl:make-shader`/`gl:build-program` helpers in one `defpackage` -- `--optimize` tree-shakes the entries a demo never calls, so each page still only provides the bindings its own demo reaches |

## Embedding a rontolisp Wasm module in a host

Exporting a Lisp function to a host runtime: sharing data across the boundary
through linear memory, and implementing a WIT world someone else wrote.

| Directory | What it demonstrates |
| --- | --- |
| [`wit/world/`](wit/world) | *Someone handed me a `.wit`, now what.* The whole workflow, starting from a world nobody wrote for rontolisp ([`wit/analyzer.wit`](wit/world/wit/analyzer.wit): `package example:analyzer`, four exports over `s32` / `string` / `bool` plus an `async func` that prints, each with `///` docs). `rontolisp --scaffold-wit wit/analyzer.wit -o analyzer.lisp` turns it into a runnable skeleton -- one `defun` stub per export, the WIT's own parameter names, its doc comments carried over as `;;;` comments, and the `rontolisp:wit-export` directive -- which **already compiles** (the stubs signal at run time), so the world is filled in one export at a time. Then `--component` builds it and `wasmtime run --invoke 'longest-word("...")'` calls it by name with no memory code at all. Rename a `defun` and the build stops with `wit/analyzer.wit:16: export 'is-palindrome' has no matching (defun is-palindrome ...)`; `--emit-wit` prints the component's own world back out, showing the two ways it must differ from the input (normalized to `package root:component`, plus the WASI imports the build really links) |
| [`count-vowels/`](count-vowels) | The rontolisp counterpart of the classic *"share a string through Wasm memory"* host tutorial, and the `rontolisp:wit-export` showcase: the export's type lives in a checked-in WIT world (`export count-vowels: func(s: string) -> s32;`), the Lisp only says `(rontolisp:wit-export "count_vowels_component.wit")`, and the compiler checks the `defun`s against it -- so a drifted signature is a compile error naming the WIT line. Because `--no-gc` imports nothing, `--emit-wit` prints the component's whole type back out byte-identical to the file it was handed (a wasm-GC build of the same source would add the ~10 `wasi:*` imports a hand-written world never states). Compiled with `--no-gc` to a plain MVP module (no wasm-GC, no WASI imports) that **any** engine runs. A string crosses that boundary as a `(pointer, length)` pair of raw UTF-8 bytes, so the module also exports its `memory` and a bump allocator `__ronto_alloc(size)` -- the host reserves space, writes the bytes, then calls `count-vowels(ptr, len)`, exactly the alloc / writeString / call flow of the tutorial; the `--no-gc --component` build of the same source lets the canonical ABI do all of it instead. Driven from a pure-Java [Endive](https://endive.run) host (a Maven project, [`src/main/java/CountVowels.java`](count-vowels/src/main/java/CountVowels.java)) and, equivalently, a three-line Node script |

## Calling a WIT interface — `wit/keyvalue/`

The mirror image of the two directories above: not the functions a program
exports, but the ones it **calls**.

| Directory | What it demonstrates |
| --- | --- |
| [`wit/keyvalue/`](wit/keyvalue) | A page-view counter written against [`wasi:keyvalue/store`](wit/keyvalue/wit/keyvalue.wit) -- the **real** upstream interface, vendored verbatim (a `variant error`, a `resource bucket`, `open: func(identifier: string) -> result<bucket, error>`). `(rontolisp:wit-import "wit/keyvalue.wit" :interface "wasi:keyvalue/store@0.2.0-draft" :package kv)` binds every function of it as an ordinary `defun` -- `bucket.get` becomes `(kv:bucket-get b key)`, the resource handle first -- and **what those calls reach is bound separately, so it changes without the program changing**. rontolisp ships no store (it knows how to bind a provider, not what `wasi:keyvalue` is), so the directory holds two, both ordinary user code: [`memory-store.lisp`](wit/keyvalue/memory-store.lisp), ~50 lines of portable Lisp over a hash table, and [`java-store.lisp`](wit/keyvalue/java-store.lisp), the same interface over a real `java.util.LinkedHashMap` through `java:` interop, whose `rontolisp:wit-provide` replaces the first on the JVM. Compiled with `--component` there is no provider in the program at all: **wasmtime's own `wasi:keyvalue` implementation** answers (`-S keyvalue=y`), and prints exactly what the two Lisp stores print. `result<T, E>`'s ok arm is the return value and its error arm signals `rontolisp:wit-error`, so a store's failures are caught with `handler-case` like anything else. [`page-hits-server.lisp`](wit/keyvalue/page-hits-server.lisp) then serves the counter over HTTP -- the pairing a served component needs, its instance being recreated per request. Not on Preview 1 (a core import carries flat values only, and every function here returns a `result`) nor `--no-gc` (its MVP module imports nothing) |

## Composing with another language — `wit/lisp-calls-rust/`, `wit/rust-calls-lisp/`

The two directories above each call one side of a WIT boundary against a
**host**. These two call **another guest language**: a Lisp component and a Rust
component (scaffolded with [`cargo-component`](https://github.com/bytecodealliance/cargo-component)),
[`wac`](https://github.com/bytecodealliance/wac)-composed into a single runnable
component. Each directory is one direction, and independent of the other. The
`.wit` type is the only thing the two languages share.

The shapes are drawn to match what each side supports: `rontolisp:wit-import`
binds an **interface**, and `rontolisp:wit-export` implements a **plain
function** — the Rust side takes whichever shape the Lisp end needs.

| Directory | What it demonstrates |
| --- | --- |
| [`wit/lisp-calls-rust/`](wit/lisp-calls-rust) | **Lisp calls Rust.** [`app.lisp`](wit/lisp-calls-rust/app.lisp) (a Lisp command) imports the interface `example:textkit/casing` with `rontolisp:wit-import` and calls `(tk:shout phrase)`; [`rust-shouter/`](wit/lisp-calls-rust/rust-shouter) (Rust) **exports** that interface, uppercasing the text and adding `!`. [`build.sh`](wit/lisp-calls-rust/build.sh) compiles both (`rontolisp app.lisp --component`; `cargo component build --target wasm32-unknown-unknown`), `wac plug`s the Rust component into the Lisp app, and `wasmtime run -W gc=y textkit.wasm` prints `hello world  ->  HELLO WORLD!`. The Rust component imports no WASI, so it needs no adapter. The app also runs **standalone** on the interpreter/JVM, where a bundled Lisp `rontolisp:wit-provide` answers the same interface |
| [`wit/rust-calls-lisp/`](wit/rust-calls-lisp) | **Rust calls Lisp.** [`counter.lisp`](wit/rust-calls-lisp/counter.lisp) (a Lisp component) **exports** the plain function `vowel-count` via `rontolisp:wit-export`; [`rust-describer/`](wit/rust-calls-lisp/rust-describer) (Rust) **imports** it and calls it from `describe`, building a sentence like `"hello world" has 3 vowels`. `wac plug`s the Lisp counter into the Rust describer, and `wasmtime run -W gc=y --invoke 'describe("hello world")' vowels.wasm` returns the sentence — the vowel count in it came from Lisp, the wording from Rust |
| [`wit/pipeline/`](wit/pipeline) | **Both directions, chained — with `wac compose`.** A three-component pipeline: [`app.lisp`](wit/pipeline/app.lisp) (Lisp) imports `example:pipeline/shout`, [`rust-shouter/`](wit/pipeline/rust-shouter) (Rust) exports it and in turn imports `vowel-count`, and [`stats.lisp`](wit/pipeline/stats.lisp) (Lisp) exports `vowel-count`. So one call is **Lisp → Rust → Lisp**. `wac plug` cannot wire a plug into another plug, so [`composition.wac`](wit/pipeline/composition.wac) writes out each edge and one `wac compose` builds the whole chain; `wasmtime run -W gc=y pipeline.wasm` prints `hello world  ->  HELLO WORLD!!!` (five `!` for the five vowels of `component model`, counted by Lisp) |

## How big is the `.wasm`? — `wasm-size/`

[`wasm-size/`](wasm-size) measures the compiled artifact rather than what it
prints. It follows
[wado-lang/wado `wasm-size/`](https://github.com/wado-lang/wado/tree/main/wasm-size),
a cross-language Wasm binary size comparison, and adds the rontolisp side of two
of its programs — `hello_world` and `pi_approx` (a million Leibniz terms printed
to 15 decimal places) — so the numbers can be read next to C, Rust, Zig, Moonbit
and Wado. `./build.sh` builds every flag combination, runs each module, checks
its output and prints the table.

The short version: `hello_world` is **518 bytes** with `--optimize=size` (one
import, `fd_write`) and **406 bytes** as a `--no-gc` MVP module; `pi_approx` is
**16,083 bytes**, of which the loop is only 3,778 — printing the answer to 15
decimal places is the rest, because `~,15F` expands inline into the caller
instead of calling a renderer (the same program printed with `princ` is 7,930). Without `--optimize` both are ~320 KB of un-tree-shaken prelude, which is
the one number on that page worth remembering.

## Running

Each example can be run by the interpreter, compiled to a JVM `.class`, or
compiled to a `.wasm` module. Using `console/nqueens.lisp` as the example:

```bash
# 1. Interpreter
java -jar $JAR examples/console/nqueens.lisp

# 2. JVM (the class is named after the output file, so keep it path-free)
java -jar $JAR examples/console/nqueens.lisp -o Prog.class && java Prog

# 3. WASM (requires wasmtime 14+)
java -jar $JAR examples/console/nqueens.lisp -o nqueens.wasm && wasmtime run -W gc nqueens.wasm
```

`console/line-numbers.lisp` reads and writes files, so the WASM run needs a
preopened directory:

```bash
java -jar $JAR examples/console/line-numbers.lisp -o ln.wasm
wasmtime run -W gc --dir . ln.wasm
```

## Verifying every non-GUI example at once

[`examples.yaml`](examples.yaml) is a manifest of the non-GUI examples and the
backends each one can be verified on, driven by the JUnit test
[`ExamplesE2eTest`](../src/test/java/am/ik/rontolisp/e2e/ExamplesE2eTest.java).
It turns every *(example x backend)* pair into one dynamic test:

- **RUN** (`interpreter` / `jvm` / `wasm`) -- runs the program to completion and
  checks it exits 0 and that its output matches the declared `expect`.
- **COMPILE** (`jvm-compile` / `wasm-component` / `no-gc`) -- the blocking
  servers and the host-invoked `--no-gc` module never return on their own, so
  they are only built; a successful compile still catches broken `(load ...)`
  paths, missing symbols and package errors.

Each entry also declares its inputs and expected result:

- `args` / `stdin` / `stdinFile` -- command-line arguments and standard input
  fed to the program when it is run.
- `expect` -- how to check RUN output, exactly one of: `equals` (stdout equals
  this hard-coded text), `file` (stdout equals a file under `examples/`, e.g.
  [`.expected/nqueens.txt`](.expected/nqueens.txt)), `contains` (every listed
  substring appears -- a partial match for output that only partly stabilises,
  like hash-table iteration order), or `skip: true` (do not check output, for
  random / non-repeatable results). Omit `expect` for the baseline "exit 0 and
  non-empty output". `equals`/`file` are checked against **all** run backends, so
  a per-backend divergence is a real failure.

Build the executable jar once, then run the suite (it is opt-in, so a plain
`./mvnw test` skips it):

```bash
./mvnw clean package -DskipTests
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
```

Every WASM build (`wasm` / `wasm-component` / `no-gc`) is compiled with
`--optimize`, the dead-code tree-shaker (a no-op under `--component`). The
`wasm` run backend is skipped when `wasmtime` is not on the `PATH`; the
compile-only backends need no runtime. To run against the GraalVM native binary
instead of the jar, pass `-Drontolisp.binary="$PWD/target/rontolisp"` (and drop
`-Drontolisp.examples`).

The full suite takes minutes. While iterating on one example, narrow it with
`-Drontolisp.examples.only=<substrings>` -- a comma-separated list matched
against the manifest path:

```bash
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true \
       -Drontolisp.examples.only=cloudflare test        # one directory
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true \
       -Drontolisp.examples.only=console/,ml/ test      # two
```

A pattern that matches nothing produces no tests at all rather than the whole
suite, so a typo shows up as `Tests run: 0`.

Adding an example? Append an entry to `examples.yaml` with its backends and an
`expect` -- no Java changes needed. To regenerate an externalised expected file
after an intentional output change, run the example and save its stdout, e.g.
`java -jar $JAR examples/console/roman.lisp > examples/.expected/roman.txt`. GUI
examples (`jvm/` and the `browser/` demos -- `browser/minesweeper/`,
`browser/rainbow/`, the `browser/webgl-*/` and the rest) are intentionally
excluded: they open a window or run in a page and cannot be checked headless.
