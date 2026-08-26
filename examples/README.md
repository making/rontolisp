# rontolisp examples

Practical, self-contained rontolisp programs. Unless noted otherwise each one
runs identically on the interpreter, the JVM and WASM.

| Directory | Programs |
| --- | --- |
| [`console/`](console) | Algorithms and console I/O — pure, cross-backend |
| [`ml/`](ml) | Numerical computing and machine learning (arrays, `linalg`, `--simd`) |
| [`deep-learning-from-scratch/`](deep-learning-from-scratch) | The book *Deep Learning from Scratch* (ゼロから作るDeep Learning) ch02-ch08, ported |
| [`llama2/`](llama2) | llama2.c's `run.c` ported whole: a Llama 2 inference engine over the real TinyStories checkpoints, and the example `--simd` is for |
| [`llm-from-scratch/`](llm-from-scratch) | 『作ってわかる大規模言語モデルの仕組み』 chapters 2 and 3, ported: attention, an encoder/decoder Transformer, then a GPT trained on 漱石 and sampled from — all on the `torch` package |
| [`net/`](net) | Sockets, HTTP servers and JSON web services |
| [`db/`](db) | PostgreSQL through the real cl-postgres driver and postmodern, up to a REST API on top |
| [`jvm/`](jvm) | A Java-callable library class, `java:` interop and Swing GUIs (JVM only) |
| [`macos/`](macos) | A native Cocoa window, and the Objective-C runtime under it, through the built-in `appkit` / `objc` packages (macOS only -- `java -jar`, the native binary, a compiled class) |
| [`browser/`](browser) | Browser demos: compile to WASM, run in a page |
| [`count-vowels/`](count-vowels), [`wit/`](wit) | Crossing the WASM boundary: exporting to a host, implementing a WIT world, calling one, composing with Rust |
| [`asdf/`](asdf) | Loading real third-party libraries with `asdf:load-system` / `ql:quickload` |
| [`wasmcloud/`](wasmcloud), [`cloudflare-workers/`](cloudflare-workers), [`gae/`](gae) | Platform templates |

How big the compiled artifacts are is measured, not documented here:
[`size-report/`](../size-report).

Assuming the executable JAR is built (`./mvnw clean package`):

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
```

## Console & algorithms — `console/`

| File | What it demonstrates |
| --- | --- |
| [`nqueens.lisp`](console/nqueens.lisp) | Backtracking search: recursion, list manipulation, ASCII board output |
| [`life.lisp`](console/life.lisp) | Conway's Game of Life on a toroidal 2-D `make-array`; `(load ...)`s the rendering-free `life-core.lisp` |
| [`sorting.lisp`](console/sorting.lisp) | Quicksort and merge sort parameterized by a first-class comparator |
| [`calc.lisp`](console/calc.lisp) | A prefix-arithmetic interpreter: recursive evaluation over an alist environment |
| [`mandelbrot.lisp`](console/mandelbrot.lisp) | ASCII Mandelbrot: floating-point arithmetic and nested loops |
| [`mandelbrot-nogc.lisp`](console/mandelbrot-nogc.lisp) | The same, as a `--no-gc` export typed by a checked-in WIT world. One directive, two builds: a plain MVP module whose host reads the string out of linear memory, and a `--component` one where `wasmtime --invoke` returns it |
| [`line-numbers.lisp`](console/line-numbers.lisp) | A `cat -n` clone: `with-open-file`, `read-line`, `write-line`, `format nil` |
| [`parse-numbers.lisp`](console/parse-numbers.lisp) | `parse-integer` and character classification over file lines |
| [`sieve.lisp`](console/sieve.lisp) | Sieve of Eratosthenes over a boolean array, plus prime factorization |
| [`hanoi.lisp`](console/hanoi.lisp) | Tower of Hanoi, in a printing and a list-returning variant |
| [`roman.lisp`](console/roman.lisp) | Roman numerals both ways, and an example that **checks itself**: the full 3999-value round-trip is a [rove](#an-example-that-checks-itself) assertion |
| [`word-frequency.lisp`](console/word-frequency.lisp) | Hash-table accumulation, custom-comparator `sort`, `maphash` |
| [`contact-book.lisp`](console/contact-book.lisp) | `defstruct` with `setf`-able accessors and `&key` lambda lists |
| [`error-handling.lisp`](console/error-handling.lisp) | Typed conditions on a bank account: `define-condition`, `handler-case` dispatch by class, `ignore-errors`, `unwind-protect`, non-fatal `signal`. **Interpreter/JVM only** |
| [`l-system.lisp`](console/l-system.lisp) | L-system fractals: string rewriting by hash-table rule dispatch, `&rest` args |

## Numerical & machine learning — `ml/`

| File | What it demonstrates |
| --- | --- |
| [`nn.lisp`](ml/nn.lisp) | XOR by backpropagation with hand-written loops over rank-1/rank-2 arrays |
| [`nn-vec.lisp`](ml/nn-vec.lisp) | The same net over the `vec`/`linalg` packages and single-float (`#f`) packed arrays |
| [`simd-dot.lisp`](ml/simd-dot.lisp) | The smallest thing `--simd` speeds up: one `vec:dot` over 1024 doubles, 4000 times. The answer is an exact integer, so only the elapsed time moves |
| [`simd-gemv.lisp`](ml/simd-gemv.lisp) | `vec:matvec` (GEMV) + `vec:dot` — the two kernels LLM inference lives in. Prints `argmax` indices, so acceleration cannot change the output. See the [SIMD guide](../doc/en/guides/simd-acceleration.md) |
| [`blas-matmul.lisp`](ml/blas-matmul.lisp) | One `linalg:matmul` at linalg's default `double-float` width — the example both acceleration flags reach. Its entries are small integers, so the printed numbers are exact and neither `--simd` nor `--blas` can move them |
| [`gpu-matmul.lisp`](ml/gpu-matmul.lisp) | The same product at **single-float** width — the width a Mac's GPU can take. One `linalg:matmul` and a timing loop; run it flagless, with `--simd` and with `--gpu --simd`. Deliberately small enough to read at a glance, and the one example not pinned by `ExamplesE2eTest`: it prints a wall-clock time and nothing else. See the [GPU guide](../doc/en/guides/gpu-acceleration.md) |
| [`simd-gemv-nogc.lisp`](ml/simd-gemv-nogc.lisp) | The same inner loop as a `--no-gc` reactor: the host calls the exported `fingerprint`. The `-into` kernels keep the never-freed bump heap at three blocks |
| [`tiny-llm.lisp`](ml/tiny-llm.lisp) | A 2-layer transformer decoder — llama2's `forward()` without the tokenizer or weight loader: RMSNorm, causal attention over a KV cache, SwiGLU, greedy decode. Thirteen GEMVs per pass. The KV cache stores **V transposed**, which keeps the attention sum one `vec:matvec`. The whole engine, over real checkpoints, is [`llama2/`](llama2) |
| [`mlp.lisp`](ml/mlp.lisp) | A generalized multi-layer perceptron for 2-D circle classification |
| [`maze-rl.lisp`](ml/maze-rl.lisp) | Tabular Q-learning on a grid maze. **Non-deterministic:** `random` is unseeded and per-backend |
| [`linear-regression.lisp`](ml/linear-regression.lisp) | Least-squares polynomial fitting through the normal equations, in exact rationals |
| [`deep-digits.lisp`](ml/deep-digits.lisp) | A 15-16-16-10 leaky-ReLU MLP over pixel bitmaps, trained by full-batch matrix backprop. Fully deterministic on every backend |
| [`numerical-calculus.lisp`](ml/numerical-calculus.lisp) | `linalg:diff` / `linalg:gradient` (numpy's `np.diff`/`np.gradient`), including non-uniform spacing |
| [`heat3d.lisp`](ml/heat3d.lisp) | Rank-3 arrays: 3-subscript `aref`, `#nA` syntax, `row-major-aref`, rank-generic `linalg`, and exact rational heat conservation |

## Deep Learning from Scratch — `deep-learning-from-scratch/`

A chapter-by-chapter port of the sample code of *Deep Learning from Scratch*
(ゼロから作るDeep Learning, O'Reilly Japan) by Koki Saitoh, with the book's
`common/` library rebuilt on `linalg:` and CLOS layer classes. MNIST scripts
need a one-time `./download-mnist.sh`. Per-program table in
[its README](deep-learning-from-scratch/README.md).

## llama2.c — `llama2/`

[`llama2.lisp`](llama2/llama2.lisp) is Andrej Karpathy's `run.c` in one Lisp
file -- checkpoint loader, tokenizer + BPE encoder, forward pass, sampler,
generate loop -- and tells the same stories as the C program, token for token,
from the checked-in 1 MB `stories260K.bin` or the downloadable `stories15M.bin`.
Its 15 million weights load through `read-sequence` over packed single-float
arrays; its decode is all `vec:matvec`, which is why `--simd` takes wasm-GC from
0.4 to 46 tokens/s. Setup, knobs and numbers in [its README](llama2/README.md).

## LLM from Scratch — `llm-from-scratch/`

The Transformer chapter of 『作ってわかる大規模言語モデルの仕組み』 (Elith
Inc., Nikkei BP), rewritten on the [`torch`
package](../doc/en/guides/neural-networks.md): scaled dot-product and
multi-head attention, sinusoidal positional encoding, LayerNorm, the
encoder/decoder Transformer with its padding and causal masks, and a
Japanese-English training loop with greedy decoding over a twelve-pair corpus
that lives in the file. `nn.Module` becomes `torch:module` plus a `forward`
defun, `nn.ModuleList` a plain list, `DataLoader` `torch:shuffled-batches`.

Chapter 3 continues into GPT: a character-level tokenizer, a decoder-only stack
with learned positions, pre-LayerNorm blocks and a causal mask, AdamW over two
parameter groups with gradient clipping and a warmup-then-cosine schedule, and
temperature / top-k sampling. It trains on the public-domain opening of
『吾輩は猫である』, inlined — nothing is downloaded — and because the sampler
draws from the same seeded generator, the generated passages are byte-identical
on every backend. Section 3.2's byte-pair encoder needs no `torch` at all, and
its hundred merges come out in the book's exact order. Mapping table and the
book-vs-tested shapes in [its README](llm-from-scratch/README.md).

## Networking, HTTP & services — `net/`

Servers on WASM need `--component` plus
`wasmtime run ... -S tcp=y -S inherit-network=y`; the
`http-handler` ones run under `wasmtime serve`.

| File | What it demonstrates |
| --- | --- |
| [`echo-server.lisp`](net/echo-server.lisp) | TCP echo server: `rontolisp:tcp-listen`/`tcp-accept`, then the ordinary stream functions over the socket handle |
| [`echo-client.lisp`](net/echo-client.lisp) | Its client, via `rontolisp:tcp-connect`. Either end can run on a different backend |
| [`http-hello.lisp`](net/http-hello.lisp) | Minimal HTTP/1.1 by hand: `read-line` over the request, `Content-Length`, `Connection: close` |
| [`https-hello.lisp`](net/https-hello.lisp) | The same over TLS via `rontolisp:tls-listen` (PKCS12 keystore). Everything after the listen call is unchanged. Interpreter/JVM only |
| [`hello-clack.lisp`](net/hello-clack.lisp) | **One source, five hosts.** The smallest **Clack** application, and the one `:server :rontolisp` serves everywhere: the interpreter and a compiled JVM class bind `PORT`, `-o app.war` deploys into any Servlet 6 container, `--component` runs under `wasmtime serve`, and `--no-wasi` is the Cloudflare Worker [`cloudflare-workers/hello-clack-one-source/`](cloudflare-workers/hello-clack-one-source) deploys — with no edit between them. See the [Clack guide](../doc/en/guides/clack.md) |
| [`hello-clack-accesslog.lisp`](net/hello-clack-accesslog.lisp) | The same application with **lack's accesslog middleware** composed around it by `lack:builder` — one Apache combined-format line per request on standard output, reporting the status and content length the application returned. The middleware is quickloaded by name, because `find-middleware` would otherwise try to load its system at run time |
| [`http-handler.lisp`](net/http-handler.lisp) | The `rontolisp:http-handler` hello world: Clack environment plist in, `(status headers body)` out. See the [Serving HTTP guide](../doc/en/guides/http-handler.md) |
| [`http-handler-cl-who.lisp`](net/http-handler-cl-who.lisp) | The same, rendering through the real **cl-who**: the markup DSL expands at macro-expansion time, `esc` escapes at run time |
| [`httpbin.lisp`](net/httpbin.lisp) | A mini **httpbin**: `/get`, `/post`, `/put`, `/patch`, `/delete` echo the request as JSON, plus 405 and 404 |
| [`httpbin-clos.lisp`](net/httpbin-clos.lisp) | The **CLOS** flavour: the envelope is a `defclass`, so `json-stringify` serializes slots in definition order — byte-identical output, and the same shape jzon produces |
| [`httpbin-jzon.lisp`](net/httpbin-jzon.lisp) | The **jzon** flavour: only the two JSON call sites change, since `rontolisp:json-*` is a subset of jzon |
| [`httpbin-clack.lisp`](net/httpbin-clack.lisp) | The **Clack** flavour: an application *function*, a `cond` over `:path-info` (clack has no router) and one middleware — a function from application to application. rontolisp's server protocol *is* Clack's, so this one file is also, unchanged, the war a Servlet container deploys and the Worker of [`cloudflare-workers/httpbin-clack-one-source/`](cloudflare-workers/httpbin-clack-one-source) — `hello-clack.lisp`'s five hosts, with endpoints. See the [Clack guide](../doc/en/guides/clack.md) |
| [`httpbin-tiny-routes.lisp`](net/httpbin-tiny-routes.lisp) | The **tiny-routes** flavour: routes composed with `define-routes`, threaded through the library's own middleware by `pipe`, and a wrong method *declining* into the catch-all that tells 405 from 404 |
| [`httpbin-ningle.lisp`](net/httpbin-ningle.lisp) | The **ningle** flavour, the other routing model: routes are assigned to an application *object*, a controller returns the body and mutates `*response*`, the request arrives already parsed, and the 404 is an overridden `ningle:not-found` method |
| [`magic-8-ball.lisp`](net/magic-8-ball.lisp) | The Spin tutorial's Magic 8 Ball JSON API. Inside a serve component `random` works via `wasi:random` |
| [`dog-fetcher.lisp`](net/dog-fetcher.lisp) | `rontolisp:fetch` *inside* a handler — the proxy/aggregator shape |
| [`linalg-api.lisp`](net/linalg-api.lisp) | A linear-algebra JSON service: `POST /solve` and `POST /fit`, with 400s for bad input. Integer inputs are solved exactly |
| [`kv-server.lisp`](net/kv-server.lisp) | A mini **Redis**: enough RESP2 that the real `redis-cli` works, plus inline commands for `nc` |
| [`kv-server-tls.lisp`](net/kv-server-tls.lisp) | The same over TLS on 6380 (`redis-cli --tls --insecure`). Interpreter/JVM only |

[`net/http-handler/`](net/http-handler) holds a `spin.toml` for the
`http-handler.lisp` component: `spin build && spin up` serves it on `:3000`. It
needs the [Spin canary](https://github.com/spinframework/spin/releases/tag/canary)
build (4.1.0-pre0+); 4.0.2 speaks an older `wasi:http` snapshot.

## A library the JVM ecosystem consumes — `jvm/`

A compiled `.class` that Java code calls, rather than a program that runs. See
the [JVM library guide](../doc/en/guides/jvm-library.md).

| File | What it demonstrates |
| --- | --- |
| [`kernels-library.lisp`](jvm/kernels-library.lisp) | `rontolisp:jvm-export` + `--no-main`: typed `public static` methods over scalars, strings and packed float arrays |
| [`bench/`](jvm/bench) | What the packed float-array boundary costs — the handle against a plain Java loop, the raw kernel, and a copying facade |

## Java interop / GUI (JVM only) — `jvm/`

These drive real Java APIs through the `java:` package, so they need the JVM (as
interpreter or via `-o Prog.class`) and a display — not the WASM backend, and
not the GraalVM native binary, which carries no reflection metadata for them.
See the [Java interop guide](../doc/en/guides/java-interop.md).

| File | What it demonstrates |
| --- | --- |
| [`java-interop.lisp`](jvm/java-interop.lisp) | A Swing window through `java:new`/`call`/`field`/`proxy`, with a Lisp lambda as the `ActionListener` |
| [`swing.lisp`](jvm/swing.lisp) | A reusable Swing grid-window helper written entirely on `java:`, in its own package; the demos splice it in with `(require :swing "swing.lisp")` |
| [`life-gui.lisp`](jvm/life-gui.lisp) | Game of Life animated on a `javax.swing.Timer`, loading the same `life-core.lisp` as `life.lisp` |
| [`minesweeper-swing.lisp`](browser/minesweeper/minesweeper-swing.lisp) | Minesweeper on the desktop, loading the same core as the browser build |

## Native macOS — `macos/`

A Cocoa window with no Swing, no `java:` and nothing installed: the built-in
`appkit` package is a widget layer written in rontolisp over `objc`, which binds
AppKit through the foreign function API. macOS, and every JVM-side
shape of the language: under `java -jar`, in the `rontolisp` native binary — which
is where `java:` cannot interpret at all — and compiled to a `.class` / `.jar`.
A program need not open a window at all: [`menubar.lisp`](macos/menubar.lisp) is a
menu bar item, its menu entries Lisp closures.
`objc` reaches further than AppKit: every framework on the machine speaks the
Objective-C runtime, and one that is not linked into the process is one `NSBundle`
message away, which is what [`system-frameworks.lisp`](macos/system-frameworks.lisp)
is about. Metal is one of them, and it is Objective-C nearly end to end, so the GPU
is reachable with nothing added: [`metal-triangle.lisp`](macos/metal-triangle.lisp)
and [`metal-cube.lisp`](macos/metal-cube.lisp) are the AppKit twins of the WebGL
examples under [`browser/`](browser).
The window examples are not in `examples.yaml`: they need a display. The two that
open nothing — [`objc-runtime.lisp`](macos/objc-runtime.lisp) and
[`system-frameworks.lisp`](macos/system-frameworks.lisp) — run in a terminal and are
listed there, with their output checked on macOS (`os: [mac]`) and their compile legs
everywhere.
See the [macOS GUI guide](../doc/en/guides/objc-appkit.md).

| File | What it demonstrates |
| --- | --- |
| [`counter.lisp`](macos/counter.lisp) | A window, a label and a button whose action is a Lisp closure; one raw `objc:send` for what the widget layer lacks; `appkit:wait` so the script outlives its last form |
| [`cocoa.lisp`](macos/cocoa.lisp) | A clickable grid of tiles over the built-in `appkit:` rungs (`panel`, `label`, `on-click`), in its own package -- the board-game policy the widget layer deliberately leaves out; the AppKit counterpart of [`swing.lisp`](jvm/swing.lisp)'s `label-grid-window` |
| [`minesweeper-macos.lisp`](browser/minesweeper/minesweeper-macos.lisp) | Minesweeper as a native Cocoa window, loading the same core as the browser and Swing builds |
| [`life-macos.lisp`](macos/life-macos.lisp) | Game of Life on an `appkit:timer`, loading the same `life-core.lisp` as [`life-gui.lisp`](jvm/life-gui.lisp) -- the same world, a Cocoa surface instead of a Swing one; a click edits the world under the simulation |
| [`listener.lisp`](macos/listener.lisp) | A Lisp listener in a Cocoa window, the way Clozure CL's IDE does it: an `NSTextView` transcript in an `NSScrollView`, an editable `NSTextField` whose Return key is a Lisp closure (`objc:define-class` again, this time for a target/action), and `eval` on what it reads -- printed output captured, an error shown as a line. The window and the evaluator are one image, so a form typed in opens the next window |
| [`menubar.lisp`](macos/menubar.lisp) | A Lisp that lives in the menu bar and opens no window of its own: `appkit:status-item` with `:dock nil` (no Dock icon, no app switcher entry), an `appkit:menu` whose entries are Lisp closures, an `appkit:timer` writing a clock into the title, and one entry that opens a window — the menu and the evaluator are one image. `appkit:quit` is the way out |
| [`system-frameworks.lisp`](macos/system-frameworks.lisp) | macOS itself as a Lisp library, with nothing installed: Vision, NaturalLanguage, Core Image and the speech synthesizer, each mapped in at run time by an `NSBundle` message. A string is drawn into an image by Core Image and read back out of it by Vision, and `equal` decides whether the round trip held; the speech is synthesized to an AIFF instead of the speakers, so the example is silent. Prints to a terminal |
| [`metal.lisp`](macos/metal.lisp) | A Metal drawing surface on an `appkit:window`, in its own package -- the layer, the drawable, the render pass and the command buffer, which every Metal program writes identically; the macOS counterpart of [`webgl-common/gl.lisp`](browser/webgl-common/gl.lisp). The device comes from `CAMetalLayer`'s `preferredDevice`, so no C entry point is needed and the whole binding is `objc:send` |
| [`metal-triangle.lisp`](macos/metal-triangle.lisp) | The WebGL hello world on a Mac GPU, the twin of [`webgl-triangle`](browser/webgl-triangle): one gradient triangle, no vertex buffer at all (the shader looks its corners up by `vertex_id`), Metal Shading Language compiled from a Lisp string at run time |
| [`metal-cube.lisp`](macos/metal-cube.lisp) | The full pipeline, the twin of [`webgl-cube`](browser/webgl-cube): a vertex buffer and a per-frame MVP matrix, both `objc:data` over packed single-float arrays -- the matrix comes straight out of the built-in [`linalg`](../doc/en/guides/linear-algebra.md) package, since a linalg result IS a packed array and `objc:data` takes one of any rank; back-face culling instead of a depth buffer (a cube is convex) and face normals from `dfdx`/`dfdy`; the frame loop is an `appkit:timer` |
| [`objc-runtime.lisp`](macos/objc-runtime.lisp) | The package with the windows left out: selectors as strings guarded by `respondsToSelector:`, class clusters found by walking the hierarchy, a method's own type encoding read through `NSMethodSignature`, key-value coding and a sort by a text key, a run-time class whose `isEqual:` is a Lisp closure that `containsObject:` calls, and an `NSNotificationCenter` observer. Prints to a terminal |

```bash
java -jar $JAR examples/macos/objc-runtime.lisp        # no window; prints and exits
java -jar $JAR examples/macos/system-frameworks.lisp  # no window either; Vision, speech, Core Image
java -jar $JAR examples/macos/counter.lisp
java -jar $JAR examples/macos/life-macos.lisp
java -jar $JAR examples/macos/listener.lisp
java -jar $JAR examples/macos/menubar.lisp             # no window; look at the menu bar
java -jar $JAR examples/macos/metal-triangle.lisp
java -jar $JAR examples/macos/metal-cube.lisp
./target/rontolisp examples/macos/counter.lisp        # the native binary, after ./mvnw -Pnative package
java -jar $JAR examples/browser/minesweeper/minesweeper-macos.lisp
java -jar $JAR examples/browser/minesweeper/minesweeper-macos.lisp \
  -o Minesweeper.class && java Minesweeper
```

## Browser demos

A Lisp program compiled to `.wasm` and driven from plain HTML/JavaScript —
except [`wit-component/`](browser/wit-component), which loads a *component* and
needs no glue at all. Each directory has its own README.

| Directory | What it demonstrates |
| --- | --- |
| [`wit-component/`](browser/wit-component) | The first rontolisp component in a browser: a Mandelbrot/Julia explorer whose page supplies *nothing* — no `instantiate`, no import object, no WASI shim, no `(ptr, len)` decoding. A WIT world types the exports and `jco transpile` produces one self-contained ES module |
| [`rainbow/`](browser/rainbow) | HSV↔RGB and shortest-arc hue interpolation in Lisp, behind one `rainbow-html(string) -> string` export |
| [`wasm-browser/`](browser/wasm-browser) | The plumbing: running a rontolisp `.wasm` from plain HTML + JavaScript, stdin, command-line arguments and env included |
| [`minesweeper/`](browser/minesweeper) | A playable Minesweeper whose rules live in a `minesweeper-core.lisp` shared with the Swing and native-macOS builds — and checked head-less by [`minesweeper-core-test.lisp`](browser/minesweeper/minesweeper-core-test.lisp) |
| [`hiragana/`](browser/hiragana) | A 46-class handwriting recognizer: the ch07 SimpleConvNet trained offline on Kuzushiji-49, its weights read back at startup, driven from a `<canvas>` |
| [`webgl-triangle/`](browser/webgl-triangle) | The WebGL hello world and the smallest `rontolisp:wasm-import` program: ten imported host functions, no exports, no frame loop |
| [`webgl-cube/`](browser/webgl-cube) | Hello 3D: perspective and rotation matrices computed in Lisp every frame; bulk floats cross through a staging array |
| [`webgl-galaxy/`](browser/webgl-galaxy) | A spiral galaxy driven entirely from Lisp, GLSL sources included, over 32 host functions declared by a WIT — the JavaScript is generated one-line bindings |
| [`webgl-heat3d/`](browser/webgl-heat3d) | The rank-3 array showcase: the page's whole state is one `(n n n)` array, diffused and projected every frame |
| [`webgl-robot-arm/`](browser/webgl-robot-arm) | A 3-D arm that reaches where you click: damped-least-squares Jacobian IK every frame (FABRIK and the analytic closed form on a HUD toggle), on a minimum-jerk trajectory |
| [`webgl-platformer/`](browser/webgl-platformer) | A one-stage 3D platformer: gravity, coyote time, per-axis AABB collision, enemy patrols and the follow camera, all in Lisp |
| [`webgl-battlefront/`](browser/webgl-battlefront) | A Pointer-Lock snow battle: third-person aim camera, blaster bolts, a lightsaber that hits *and* deflects, and stormtrooper/AT-AT/boss AI |
| [`webgl-common/`](browser/webgl-common) | Not a demo but the shared `gl` package the others splice in with `(require :gl ...)`; `--optimize` tree-shakes the entries a demo never calls |

## Crossing the WASM boundary — `count-vowels/`, `wit/`

| Directory | What it demonstrates |
| --- | --- |
| [`count-vowels/`](count-vowels) | *Share a string through Wasm memory.* A `--no-gc` MVP module any engine runs: the host allocates through `__ronto_alloc`, writes UTF-8 bytes, then calls `count-vowels(ptr, len)`. The export's type lives in a checked-in WIT world, so a drifted signature is a compile error naming the WIT line. Driven from a pure-Java host and a three-line Node script; the `--component` build lets the canonical ABI do the memory work instead |
| [`wit/world/`](wit/world) | *Someone handed me a `.wit`, now what.* `--scaffold-wit` turns a world nobody wrote for rontolisp into a compiling skeleton — one `defun` stub per export, the WIT's own parameter names and docs — which you fill in one export at a time. Renaming a `defun` fails the build with the WIT line number |
| [`wit/keyvalue/`](wit/keyvalue) | The other direction: **calling** a WIT interface. `wit-import` binds the real upstream `wasi:keyvalue/store` as ordinary `defun`s, and what those calls reach is bound separately — two Lisp providers here, or wasmtime's own store under `--component` |
| [`wit/lisp-calls-rust/`](wit/lisp-calls-rust) | **Lisp calls Rust**: a Lisp command imports an interface a Rust component exports, `wac plug`ged into one component. The app also runs standalone, where a bundled Lisp provider answers the same interface |
| [`wit/rust-calls-lisp/`](wit/rust-calls-lisp) | **Rust calls Lisp**: a Lisp component exports a plain function a Rust component imports and calls |
| [`wit/pipeline/`](wit/pipeline) | **Both directions chained**: Lisp → Rust → Lisp across three components. `wac plug` cannot wire a plug into a plug, so a `composition.wac` spells out each edge for one `wac compose` |

## Third-party libraries & platform templates

| Directory | What it demonstrates |
| --- | --- |
| [`asdf/`](asdf) | Loading unmodified upstream libraries — split-sequence, parse-number, cl-utilities, cl-who, cl-mustache, assoc-utils, cl-base64, md5, chipz, cl-ppcre, jzon, ironclad, jose, uax-15, tiny-routes, clack — on all four backends |
| [`wasmcloud/`](wasmcloud) | The wasmCloud Rust templates ported to `rontolisp:http-handler`, each with a `.wash/config.yaml` so `wash dev` builds and serves it |
| [`cloudflare-workers/`](cloudflare-workers) | Twelve independent Workers: two subjects written once with no library and then in the idiom of each web library, plus two that call out over HTTP on the two `--host-boundary` shapes — from a `--no-gc` module with zero imports to a routed application deployed by `npx wrangler deploy` |
| [`gae/`](gae) | Google App Engine standard, two ways: `-o app.jar` on the second-generation Java runtime, and `-o app.war` unpacked under its Jetty. Both compile [`net/httpbin-clack.lisp`](net/httpbin-clack.lisp) unchanged; the README measures why the jar wins |

## Running

Any example can be interpreted, compiled to a JVM `.class`, or compiled to
`.wasm`:

```bash
# 1. Interpreter
java -jar $JAR examples/console/nqueens.lisp

# 2. JVM (the class is named after the output file, so keep it path-free)
java -jar $JAR examples/console/nqueens.lisp -o Prog.class && java Prog

# 3. WASM (requires wasmtime 14+)
java -jar $JAR examples/console/nqueens.lisp -o nqueens.wasm && wasmtime run nqueens.wasm
```

Programs that touch files need a preopened directory on WASM:

```bash
java -jar $JAR examples/console/line-numbers.lisp -o ln.wasm
wasmtime run --dir . ln.wasm
```

## An example that checks itself

Most examples print a result and leave the checking to
[`examples.yaml`](examples.yaml). Three of them do it in the Lisp instead, with
[rove](../doc/en/guides/testing.md) — the shape to copy when what you are
writing has a right answer rather than only an output:

| File | What it asserts |
| --- | --- |
| [`console/roman.lisp`](console/roman.lisp) | The demo prints its tables, then asserts the encodings, the out-of-range errors and the whole 1..3999 round-trip |
| [`cloudflare-workers/httpbin/check.lisp`](cloudflare-workers/httpbin/check.lisp) | Drives the Worker's `handle-request` over six requests and asserts the **parsed** reply, field by field |
| [`browser/minesweeper/minesweeper-core-test.lisp`](browser/minesweeper/minesweeper-core-test.lisp) | A test file beside a GUI example: its rules live in a rendering-free core, so they can be checked head-less |

The recipe is four lines. Load rove, silence its ANSI colors, write `deftest`s,
and make the verdict the exit code:

```lisp
(asdf:load-system :rove)
(use-package :rove)
(setf *enable-colors* nil)

(deftest arithmetic
  (testing "adding two integers"
    (ok (= (add 1 2) 3))))

(uiop:quit (if (run-suite *package*) 0 1))
```

rove is vendored in this repository, so its three directories go on
`--system-path` and nothing is downloaded; outside it, `(ql:quickload "rove")`
fetches the same sources. The systems are spliced in at compile time, so the
compiled class / module is self-contained:

```bash
SP=src/test/resources/rove:src/test/resources/dissect:src/test/resources/cl-ppcre
java -jar $JAR examples/console/roman.lisp --system-path $SP
```

rove records a failing test through
`handler-bind`. The full story — the entry points, the exit code, and what does
not work — is the [Testing guide](../doc/en/guides/testing.md).

## Verifying every non-GUI example at once

[`examples.yaml`](examples.yaml) lists each non-GUI example and the backends it
can be verified on; [`ExamplesE2eTest`](../src/test/java/am/ik/rontolisp/e2e/ExamplesE2eTest.java)
turns every *(example × backend)* pair into one dynamic test. `interpreter` /
`jvm` / `wasm` run the program and check its output; `jvm-compile` /
`wasm-component` / `no-gc` only build it, because blocking servers and
host-invoked modules never return on their own.

Each entry declares its `args` / `stdin` and one `expect`: `equals` (stdout
matches this text), `file` (matches a file under `examples/`), `contains`
(every listed substring appears) or `skip: true`. Omitting `expect` means "exit
0 and non-empty output". `equals`/`file` are checked against **all** run
backends, so a per-backend divergence is a real failure. An example that loads
an ASDF system names its directory — or, like the rove ones above, the LIST of
directories — under `systemPath`. One that can only *run* on a given platform
names it under `os` (`os: [mac]`): that gates the run legs and leaves the
compile legs alone, so its lowering is still checked everywhere.

The suite is opt-in, so a plain `./mvnw test` skips it:

```bash
./mvnw clean package -DskipTests
./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true test
```

To run against the native binary instead, pass
`-Drontolisp.binary="$PWD/target/rontolisp"` and drop `-Drontolisp.examples`.
The full suite takes minutes; narrow it with a comma-separated
`-Drontolisp.examples.only=<substrings>` matched against the manifest path
(`console/,ml/`). A pattern matching nothing produces `Tests run: 0` rather than
the whole suite.

Adding an example means appending an entry to `examples.yaml` — no Java changes.
To regenerate an externalised expected file, run the example and save its
stdout. GUI examples (`jvm/`, `macos/` and the `browser/` demos) are excluded: they open a
window or run in a page and cannot be checked headless — though the part of one
that is not GUI can be, which is what
[`minesweeper-core-test.lisp`](browser/minesweeper/minesweeper-core-test.lisp)
is, and what [`objc-runtime.lisp`](macos/objc-runtime.lisp) and
[`system-frameworks.lisp`](macos/system-frameworks.lisp) are throughout — they
open nothing, so their output is checked like any other example, under
`os: [mac]` for the runtime they need.
