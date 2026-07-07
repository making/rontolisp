# rontolisp examples

Practical, self-contained rontolisp programs. Unless noted otherwise, each one
runs identically on all three backends (interpreter / JVM / WASM).

Assuming the executable JAR has been built
(`./mvnw clean spring-javaformat:apply package`), set:

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
```

| File | What it demonstrates |
| --- | --- |
| [`nqueens.lisp`](nqueens.lisp) | Backtracking search (N-Queens): recursion, list manipulation, functional accumulation, ASCII board output |
| [`life.lisp`](life.lisp) | Conway's Game of Life: a console front-end that `(load ...)`s the rendering-free core (`life-core.lisp`) -- a 30x24 toroidal grid backed by a 2-D `make-array`, neighbour counting with wraparound, and per-generation ASCII rendering |
| [`sorting.lisp`](sorting.lisp) | Quicksort and merge sort over number lists, parameterized by a first-class comparator; cross-checked against the built-in `sort` |
| [`calc.lisp`](calc.lisp) | A tiny prefix-arithmetic interpreter: a recursive evaluator over an alist environment, cross-checked against the built-in `eval` |
| [`mandelbrot.lisp`](mandelbrot.lisp) | ASCII Mandelbrot set: floating-point arithmetic and nested loops (no transcendental functions) |
| [`line-numbers.lisp`](line-numbers.lisp) | A `cat -n` style file tool: `with-open-file`, `read-line`, `write-line`, `format nil`, line/character counts |
| [`parse-numbers.lisp`](parse-numbers.lisp) | Numeric-column parsing and character classification: `parse-integer`, `char`, `alpha-char-p`, `digit-char-p` over file lines |
| [`nn.lisp`](nn.lisp) | Feed-forward neural network learning XOR via backpropagation: vectors as rank-1 arrays and weight matrices as rank-2 `make-array`s, with `aref`, `(setf (aref ...))` and `incf`/`decf` for in-place weight updates |
| [`mlp.lisp`](mlp.lisp) | Generalized multi-layer perceptron for 2-D circle classification, built on the same array-based vector/matrix representation as `nn.lisp` |
| [`maze-rl.lisp`](maze-rl.lisp) | Tabular Q-learning that solves a grid maze: a hash-table Q-table keyed by `(row col action)`, idiomatic `random`-based epsilon-greedy exploration, and an ASCII rendering of the learned path. **Non-deterministic:** because `random` is unseeded and per-backend, the exact path and value count differ on each run and backend (the algorithm always converges to a valid route) |
| [`sieve.lisp`](sieve.lisp) | Sieve of Eratosthenes: boolean `make-array` as the sieve, `aref`/`(setf (aref ...))`, list accumulation via `push`/`reverse`, and prime factorization with `sqrt`/`ceiling` |
| [`hanoi.lisp`](hanoi.lisp) | Tower of Hanoi: classic recursive puzzle solver with both a printing variant and a list-returning variant (`&optional` argument), plus `expt` for the move count formula |
| [`roman.lisp`](roman.lisp) | Roman numeral encoder/decoder: bidirectional conversion (1↔I, 4↔IV, …, 3999↔MMMCMXCIX) with association-list lookup tables, `concatenate`, and a full 3999-value round-trip correctness check |
| [`word-frequency.lisp`](word-frequency.lisp) | Word frequency counter: hash-table accumulation, `string-downcase`, `alpha-char-p`-based tokenization, `sort` with a custom comparator, and `maphash` iteration |
| [`contact-book.lisp`](contact-book.lisp) | Contact book using `defstruct`: tagged-list structs with `setf`-able accessors, `&key` lambda lists, hash-table storage, and `maphash`-based lookup |
| [`l-system.lisp`](l-system.lisp) | L-system (Lindenmayer system) fractal generator: string-rewriting via hash-table rule dispatch, `&rest` variadic arguments, and character-frequency analysis (Sierpinski triangle, Koch curve, Dragon curve) |
| [`linear-regression.lisp`](linear-regression.lisp) | Least-squares polynomial fitting with the `linalg` package: a Vandermonde matrix built with `make-array`/`expt`, the normal equations solved via `linalg:transpose`/`matmul`/`solve`, and exact rational coefficients and residuals (identical on every backend) verified with `linalg:array-equal` |
| [`deep-digits.lisp`](deep-digits.lisp) | A small deep neural network, numpy-style: a 15 -> 16 -> 16 -> 10 leaky-ReLU MLP classifying 5x3 pixel digit bitmaps, trained by full-batch matrix backpropagation (`linalg:matmul`/`transpose`/`emap`) with 1/t learning-rate decay -- fully deterministic (fixed-seed LCG init, no transcendental functions, integer-scaled loss output), so all backends print identical output |
| [`heat3d.lisp`](heat3d.lisp) | Heat diffusion in a 5x5x5 voxel grid with rank-3 arrays: 3-subscript `make-array`/`aref`, the `#nA` printed syntax, `row-major-aref` flat scans (with flat-index decoding and `array-row-major-index`), rank-generic `linalg` operations (`reshape`/`add`/`sum`/`amax`/`array-equal`) over rank-3 tensors, and exact rational arithmetic that conserves the total heat as *exactly* 1000 on every backend |
| [`echo-server.lisp`](echo-server.lisp) | TCP echo server on port 7777: `rontolisp:tcp-listen`/`tcp-accept` in an accept loop, echoing lines back through the standard stream functions (`read-line`/`write-line`/`close` work on socket handles) until the peer closes. On WASM it needs `--component` plus `wasmtime run ... -S tcp=y -S inherit-network=y`; server and client can each run on a *different* backend |
| [`echo-client.lisp`](echo-client.lisp) | TCP echo client for `echo-server.lisp`: `rontolisp:tcp-connect`, then pipes stdin lines to the server and prints each reply. Same backend notes as the server |
| [`http-hello.lisp`](http-hello.lisp) | Minimal HTTP/1.1 server on port 8080, `curl`/browser-compatible: reads the CRLF request line and headers with plain `read-line` (one trailing `\r` is stripped on every backend), answers with `Content-Length` and `Connection: close`, and keeps a running request counter. Same WASM notes as `echo-server.lisp` |
| [`https-hello.lisp`](https-hello.lisp) | The TLS version of `http-hello.lisp`: the same minimal HTTP/1.1 server, served over **HTTPS** on port 8443 via `rontolisp:tls-listen` (a PKCS12 keystore + password; the header comment shows the one-line `keytool` command that generates a self-signed one). Accepted sockets handshake on the first read, so everything after the listen call is the plain-TCP code unchanged. Interpreter/JVM only -- TLS is a compile error on WASM. Talk to it with `curl -k https://127.0.0.1:8443/` |
| [`http-handler.lisp`](http-handler.lisp) | The `rontolisp:http-handler` hello world: a handler function takes a request plist (`:method`/`:path`/`:headers`/`:body`) and returns a response plist (`:status`/`:headers`/`:body`) -- no hand-rolled HTTP parsing. Serves on the interpreter/JVM (blocking on port 8080) and, with `--component`, as a WASI HTTP component under `wasmtime serve -W gc=y` (jco and wasmCloud work too). See the [Serving HTTP guide](../doc/en/guides/http-handler.md) |
| [`http-handler-cl-who.lisp`](http-handler-cl-who.lisp) | The `http-handler.lisp` hello world rendering its response with **cl-who** (the real upstream (X)HTML library, loaded via `asdf:load-system` -- run with `--system-path src/test/resources/cl-who`): `with-html-output-to-string` expands the markup DSL at macro-expansion time and `esc` escapes the request path at run time. Same interpreter/JVM/`--component` backends and run commands as `http-handler.lisp` |
| [`httpbin.lisp`](httpbin.lisp) | A miniature **httpbin**-style echo service on `rontolisp:http-handler`: `/get`, `/post`, `/put`, `/patch` and `/delete` respond with a JSON document describing the request -- query args, headers, the raw body and (when the body is JSON) its parsed value -- built with `rontolisp:json-stringify`/`json-parse`, plus 405 on a wrong method and 404 on an unknown path. Same backends and run commands as `http-handler.lisp` |
| [`magic-8-ball.lisp`](magic-8-ball.lisp) | The classic Spin tutorial's **Magic 8 Ball JSON API** on `rontolisp:http-handler`: ask a yes/no question via `GET /?question=...` or a POSTed body (raw text or JSON `{"question": ...}`) and it answers with one of the twenty canonical replies drawn with `random`, as JSON built with `rontolisp:json-stringify`. Missing question 400, unknown path 404. Same backends and run commands as `http-handler.lisp` -- inside a serve component `random` works because the preview1 bridge maps it to `wasi:random` |
| [`dog-fetcher.lisp`](dog-fetcher.lisp) | wasmCloud's **dog-fetcher** example shape: `rontolisp:fetch` *inside* a `rontolisp:http-handler` handler (the classic proxy/aggregator). Every `GET /` asks the dog.ceo API for a random dog picture and answers `{"dog": "<image url>"}` built with `rontolisp:json-parse`/`json-stringify`; upstream failure 502, unknown path 404. Same backends and run commands as `http-handler.lisp`, except the serve component needs outbound HTTP granted: `wasmtime serve -W gc=y -S http=y` |
| [`linalg-api.lisp`](linalg-api.lisp) | A linear-algebra **JSON web service** on `rontolisp:http-handler`, combining the `linalg` package with `rontolisp:json-parse`/`json-stringify`: `POST /solve` solves a linear system a.x = b (exact Gaussian elimination, determinant included) and `POST /fit` least-squares-fits a polynomial through posted sample points via the normal equations, with input validation answering 400 (non-object body, non-square or singular matrix, too few points). Stateless, so it behaves identically on all backends -- integer inputs are solved exactly. Same backends and run commands as `http-handler.lisp` |
| [`kv-server.lisp`](kv-server.lisp) | A miniature **Redis-compatible** in-memory key-value server on port 6379: speaks enough RESP2 that the real `redis-cli` works (`PING`/`SET`/`GET`/`DEL`/`EXISTS`/`INCR`/`KEYS`/`DBSIZE`/`QUIT`), and accepts Redis-style inline commands so `telnet`/`nc 127.0.0.1 6379` work too. Hash-table state survives across connections; protocol framing, `cond` dispatch and reply encoding are all plain `read-line`/`write-line` over the socket handle. Same WASM notes as `echo-server.lisp` |
| [`kv-server-tls.lisp`](kv-server-tls.lisp) | The TLS version of `kv-server.lisp`: the same mini-Redis, serving TLS on port 6380 (like a real Redis with `--tls-port`) via `rontolisp:tls-listen`. Talk to it with `redis-cli --tls --insecure -p 6380`. Interpreter/JVM only -- TLS is a compile error on WASM |
| [`wasmcloud/`](wasmcloud) | Ports of the **wasmCloud Rust templates** to `rontolisp:http-handler`, one directory per template (hello world, routed handler, outgoing-HTTP proxy, in-memory key-value API, and the TCP service + HTTP API pair of `service-tcp`), each with a `.wash/config.yaml` so `wash dev` builds and serves it directly; the directory README has a template-by-backend support matrix |
| [`asdf/`](asdf) | Loading REAL third-party libraries through `asdf:load-system`: unmodified [split-sequence v2.0.1](https://github.com/sharplispers/split-sequence), [parse-number v1.8](https://github.com/sharplispers/parse-number), [cl-utilities v1.2.4](https://common-lisp.net/project/cl-utilities/) and [cl-who v1.1.5](https://github.com/edicl/cl-who) sources, exercised on all four backends (interpreter / JVM / WASM Preview 1 / `--component`); the directory README has the per-backend run commands and expected output |

## Java interop / GUI (JVM only)

These drive real Java APIs through the `java:` interop package, so they run on
the **JVM only** — interpret them (`java -jar $JAR ...`) or compile them to a
`.class` (`-o Prog.class && java Prog`); not the WASM backend (which cannot
lower a java object) and not interpreted in the GraalVM native binary (which
has no reflection metadata for the interop classes) — and need a machine with a
display. See the
[Java interop guide](../doc/en/guides/java-interop.md).

| File | What it demonstrates |
| --- | --- |
| [`java-interop.lisp`](java-interop.lisp) | A minimal Swing window built directly through `java:new`/`java:call`/`java:field`/`java:proxy` -- a button whose `ActionListener` is a rontolisp lambda wrapped in a dynamic proxy |
| [`swing.lisp`](swing.lisp) | A small reusable Swing grid-window helper library, written entirely on top of `java:` (no bespoke Java class) and wrapped in its own `swing` package; the GUI demos splice it in with `(require :swing "swing.lisp")` and call the qualified names (`swing:grid-window`, `swing:paint`, ...) |
| [`life-gui.lisp`](life-gui.lisp) | Conway's Game of Life animated in a Swing window: loads the same `life-core.lisp` as `life.lisp` plus the `swing` package, and steps the world on a `javax.swing.Timer` |
| [`minesweeper/minesweeper-swing.lisp`](minesweeper/minesweeper-swing.lisp) | Minesweeper on the desktop: loads the same `minesweeper-core.lisp` as the browser build (only the drawing differs) and paints a clickable Swing label grid via the `swing` package |

## Browser demos (compile to WASM, run in a page)

These are directories rather than single files: a Lisp program is compiled to
`.wasm` and driven from plain HTML/JavaScript via a tiny WASI shim.

| Directory | What it demonstrates |
| --- | --- |
| [`wasm-browser/`](wasm-browser) | Running a rontolisp-compiled `.wasm` in the browser from plain HTML + JavaScript, including feeding stdin from the page |
| [`minesweeper/`](minesweeper) | A playable Minesweeper: the rules (flood fill, win/lose) live in a shared `minesweeper-core.lisp` that both a browser build (compiled to a `--no-wasi` WebAssembly reactor, HTML rendering) and a [Swing build](minesweeper/minesweeper-swing.lisp) load -- only the drawing differs |
| [`hiragana/`](hiragana) | A 46-class handwritten-hiragana recognizer (the full gojuon): a small MLP trained offline in Lisp, baked into an inference `.wasm`, and driven from a `<canvas>` you draw on |
| [`webgl-triangle/`](webgl-triangle) | The WebGL hello world and the smallest `rontolisp:wasm-import` program: Lisp compiles two shaders and draws one colored triangle through ten imported host functions -- no exports, no frame loop; the page just calls `_initialize()` |
| [`webgl-cube/`](webgl-cube) | Hello 3D: a rotating cube whose perspective projection and rotation matrices are computed in Lisp every frame (4x4 matrix math on `make-array`s); bulk floats (geometry, the mat4 uniform) cross the boundary through a small staging array |
| [`webgl-galaxy/`](webgl-galaxy) | A spiral galaxy whose WebGL pipeline is driven entirely from Lisp: the GLSL shaders live in the Lisp source, and Lisp compiles, links, buffers and issues every draw call through 34 `rontolisp:wasm-import` host functions (even `Math.sin`/`Math.cos`) — JavaScript is one-line bindings and the HUD |
| [`webgl-heat3d/`](webgl-heat3d) | The rank-3 array showcase in the browser: the page's whole state is one `(n n n)` `make-array` — every frame Lisp deposits heat at two orbiting sources with three-subscript `(setf (aref ...))`, diffuses it across the voxel lattice, normalizes the colors with the rank-generic `linalg:amax`, and projects every voxel itself (same one-line WebGL host boundary as `webgl-galaxy`) |
| [`webgl-robot-arm/`](webgl-robot-arm) | A 3-D robot arm that reaches for wherever you click and closes its three-finger gripper on arrival (drag orbits the camera, scroll zooms): damped-least-squares Jacobian IK solves the joint chain every frame (`linalg:matmul`/`transpose`/`solve` on the damped normal equations; a HUD toggle switches to the matrix-free FABRIK, or to the analytic closed form whose forward kinematics walks a chain of 4x4 homogeneous transforms) -- a rigid tool link included, so the grasp point between the fingertips is what lands on the click -- the hand glides along a minimum-jerk trajectory (`10u^3 - 15u^4 + 6u^5`), and Lisp tessellates the lit cylinders/spheres and the RGB = XYZ axis arrows itself -- the camera's view-projection is a rank-2 `linalg:matmul` product, click-ray unprojection included; gestures arrive through exported functions, and JavaScript is one-line bindings and the HUD |
| [`webgl-platformer/`](webgl-platformer) | A one-stage 3D platformer played with W/A/S/D + Space: the whole game -- gravity, jump buffering and coyote time, per-axis AABB collision against the level blocks, enemy patrols with the stomp-or-die rule, coin pickups, the goal flag and the follow camera -- lives in the Lisp source, which also tessellates every rotated box of the world, the robot explorer and the walkers each frame; JavaScript is one-line bindings, keyboard forwarding and the HUD |
| [`webgl-common/`](webgl-common) | Not a demo but the shared `gl` package the WebGL demos above (except the deliberately self-contained `webgl-triangle`) splice in with a compile-time `(require :gl "../webgl-common/gl.lisp")`: the WebGL2 `rontolisp:wasm-import` boundary, the enum constants and the `gl:make-shader`/`gl:build-program` helpers in one `defpackage` -- `--optimize` tree-shakes the entries a demo never calls, so each page still only provides the bindings its own demo reaches |

## Embedding a rontolisp Wasm module in a host

Exporting a Lisp function to a host runtime and sharing data across the boundary
through linear memory.

| Directory | What it demonstrates |
| --- | --- |
| [`count-vowels/`](count-vowels) | The rontolisp counterpart of the classic *"share a string through Wasm memory"* host tutorial: `count-vowels` is exported with `(rontolisp:wasm-export 'count-vowels :as "count_vowels" :params '(:string) :returns :int)` and compiled with `--no-gc` to a plain MVP module (no wasm-GC, no WASI imports) that **any** engine runs. A string crosses the boundary as a `(pointer, length)` pair of raw UTF-8 bytes, so the module also exports its `memory` and a bump allocator `__ronto_alloc(size)` -- the host reserves space, writes the bytes, then calls `count_vowels(ptr, len)`, exactly the alloc / writeString / call flow of the tutorial. Driven from a pure-Java [Endive](https://endive.run) host (a Maven project, [`src/main/java/CountVowels.java`](count-vowels/src/main/java/CountVowels.java)) and, equivalently, a three-line Node script |

## Running

Each example can be run by the interpreter, compiled to a JVM `.class`, or
compiled to a `.wasm` module. Using `nqueens.lisp` as the example:

```bash
# 1. Interpreter
java -jar $JAR examples/nqueens.lisp

# 2. JVM (the class is named after the output file, so keep it path-free)
java -jar $JAR examples/nqueens.lisp -o Prog.class && java Prog

# 3. WASM (requires wasmtime 14+)
java -jar $JAR examples/nqueens.lisp -o nqueens.wasm && wasmtime run -W gc nqueens.wasm
```

`line-numbers.lisp` reads and writes files, so the WASM run needs a preopened
directory:

```bash
java -jar $JAR examples/line-numbers.lisp -o ln.wasm
wasmtime run -W gc --dir . ln.wasm
```
