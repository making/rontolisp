# Compile to WASM

Give `rontolisp` an output path ending in `.wasm` with `-o`, and it compiles the
source to a WebAssembly binary instead of interpreting it. As with the JVM
backend, the output extension selects the target, and the binary is emitted by
hand without a third-party assembler:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
```

```
3
```

The WASM backend can produce several different **output shapes**, selected by
compile flags. The next section is the map: pick your shape there, then read
only that shape's section — each one is self-contained.

## Choosing an Output

Two independent choices determine the shape of the output:

- **Value model.** By default, values live on the WebAssembly **GC heap**
  (integers as `i31ref`, floats boxed in a struct), which supports the **full
  language** but requires a wasm-GC capable runtime (wasmtime 14+, Node 22+,
  current browsers). `--no-gc` instead lowers a **pure-compute subset** of the
  language onto unboxed `i64`/`f64` scalars and linear-memory strings — the
  result is a plain MVP module that runs on **any** WebAssembly engine and is
  orders of magnitude smaller.
- **Packaging.** By default the output is a **WASI Preview 1 core module**.
  `--component` wraps it as a **component**: on the GC path a WASI 0.3
  component with full I/O over the async canonical ABI, on the `--no-gc` path a
  compact typed reactor component that runs with no host flags at all. On the
  Preview 1 GC path, `--no-wasi` instead drops the WASI imports, turning the
  module into a pure-compute library ("reactor") a host can instantiate with no
  import object.

Crossing the two axes gives the five shapes:

| Output shape | Flags | Language | Runs on | Pick it for |
| --- | --- | --- | --- | --- |
| [WASI command module](#the-default-output-a-wasm-gc-core-module) | (none) | full | wasm-GC engine with WASI Preview 1 (`wasmtime run -W gc`) | running a whole program from the command line |
| [Library (reactor) module](#no-wasi-reactor-mode) | `--no-wasi` | full (pure-compute exports) | any wasm-GC engine, no imports needed (Node 22+, current browsers) | calling Lisp functions from JavaScript |
| [WASI 0.3 component](#wasi-03-component---component) | `--component` | full, plus component-only I/O (`rontolisp:fetch`, TCP sockets) | wasmtime 46+ (with the flags below) or another component host with wasm-GC | typed component exports plus real I/O |
| [Plain core module](#non-gc-output---no-gc) | `--no-gc` | numeric/string [subset](#eligible-subset) | **any** WebAssembly engine, even without wasm-GC or SIMD | tiny, dependency-free compute kernels |
| [Compact typed component](#compact-component-output---no-gc---component) | `--no-gc --component` | numeric/string [subset](#eligible-subset) | any component host, **zero flags** | tiny typed components |

Rule of thumb: pick the **value model** by what the code needs — the full
language means the GC heap; a numeric/string kernel that fits the subset gains
universal portability and a hundreds-of-bytes binary from `--no-gc` — then pick
the **packaging** by the host: a component host gets `--component`, a plain
engine or JavaScript embedder gets a core module.

Two further flags are orthogonal to the shape, and covered in
[Cross-Cutting Flags](#cross-cutting-flags) at the end:

- [`--optimize`](#optimize-tree-shaking) tree-shakes the module (a no-op on the
  GC `--component` path);
- [`--simd`](#simd-acceleration---simd) accelerates the numeric vector kernels
  with native `v128` instructions, on both value models.

## Exporting Lisp Functions

By default a compiled module only exposes its entry point (`_start`). To make an
individual Lisp function callable directly from a host (`wasmtime --invoke`,
JavaScript, or another module), mark it with the `rontolisp:wasm-export`
directive, declaring the WASM-boundary types of its parameters and result:

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
```

```bash
rontolisp fact.lisp -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5
```

```
120
```

The directive itself is the same in every output shape; what changes per shape
is the **host contract** of the export — a raw core function on the core-module
shapes, a typed component-model export under `--component`. On the interpreter
and JVM backends the directive is a no-op (it just returns the named symbol), so
the same source runs on every backend.

The type designators and their boundary representations are:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:long` | `i64` | `--no-gc` only; full 64-bit signed range, matching the non-GC backend's internal `i64` |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory; a component-model `string` under `--component` |
| `:s-expr` | `(ptr, len)` | s-expression text (any value except a function); GC value model only |

A side-effecting function can declare a **void** result by omitting `:returns`
(or giving it as `nil`, `'()` or `:void`); the wrapper then discards the Lisp
return value and has no WASM result. Likewise an omitted, `nil` or `'()`
`:params` means no arguments.

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

`:as` renames the export — useful when the host-facing API wants a name that is
not an idiomatic Lisp symbol, e.g. camelCase for JavaScript:

```lisp
(defun draw-board (w h) (* w h))
(rontolisp:wasm-export 'draw-board :as "drawBoard" :params '(:int :int) :returns :int)
```

Limitations shared by every shape:

- Only a top-level `defun` can be exported, the declared parameter count must
  match its arity, and functions that take or return function values are out of
  scope.
- The exported name defaults to the bare Lisp name (`fact`) and can be renamed
  with `:as`; how arguments are written depends on the host
  (`wasmtime --invoke fact module.wasm 5`, `instance.exports.fact(5)`, ...).

### Export Modes at a Glance

The same directive compiles into four different host contracts depending on the
`--no-gc` / `--component` flags:

| | GC core module (default / `--no-wasi`) | GC `--component` | `--no-gc` core module | `--no-gc --component` |
| --- | --- | --- | --- | --- |
| Host requirements | wasm-GC engine (`wasmtime -W gc`, Node 22+, current browsers) | wasmtime 46+ (`-W gc=y`) or a component host with wasm-GC + JSPI (a [browser via jco](#running-a-component-in-a-browser-jco) loads and computes, but cannot print yet) | **any** WebAssembly engine | any component-model host, **no flags** — including a [browser via jco](#running-a-component-in-a-browser-jco), with no dependencies at all |
| Export shape | raw core function | typed component-model export (WAVE `--invoke`, jco) | raw core function | typed component-model export (WAVE `--invoke`, jco) |
| Scalars | `:int`/`:float`/`:bool`/void | `:int`/`:float`/`:bool`/void | + `:long` (`i64`) | + `:long` (`s64`) |
| `:string` | manual `(ptr,len)` + `__ronto_alloc` | component-model `string` (canonical ABI) | manual `(ptr,len)` + `__ronto_alloc` | component-model `string` (canonical ABI) |
| `:s-expr` | manual `(ptr,len)` | component-model `string` (printed text) | not supported | not supported |
| Function body may use | the full language | the full language | the [non-GC subset](#eligible-subset) | the [non-GC subset](#eligible-subset) |
| I/O inside the export | works (real WASI imports; traps under `--no-wasi`) | usually works even in a sync export; [`:async t`](#component-model-function-exports-wasm-export) removes the residual trap risk | `print` only (one `fd_write` import) | `print` only (built-in WASI 0.3 stdout bridge; the exports become async lifts) |
| Program top level | runs as `_start` | co-exists as `wasi:cli/run` | `defun` + directives only | `defun` + directives only |
| Per-call string memory | host-managed (`__ronto_alloc` + the [arena API](#reclaiming-the-hosts-buffer-the-arena-api); the Lisp side is the engine's) | freed by the canonical post-return | host-managed (`__ronto_alloc` + the [arena API](#reclaiming-memory-the-arena-api); automatic for scalar returns) | freed by the canonical post-return |
| Typical size | ~100 KB (~2 KB with [`--optimize`](#optimize-tree-shaking)) | ~110 KB | tens of bytes to a few KB | hundreds of bytes to a few KB |

The rest of this page details each shape: how its exports are called, what runs
inside them, and what each host must provide.

## The Default Output: a wasm-GC Core Module

The default output — no flags beyond `-o file.wasm` — is a **WASI Preview 1
core module** over the wasm-GC value model:

- **wasm-GC** — Integers are represented as `i31ref`. Floating-point numbers
  are boxed in a `float_struct { f64 }`. All values on the stack are typed as
  `(ref eq)`. This is what supports the full language (cons cells, symbols,
  closures, hash tables, `eval`, ...), and why the module needs a wasm-GC
  capable runtime such as wasmtime 14+ (`-W gc`), Node 22+, or a current
  browser.
- **WASI Preview 1** — the module imports the eight `wasi_snapshot_preview1`
  functions (`fd_write` for stdout, `random_get`, clocks, environment, ...) and
  exposes the `_start` entry point, so `wasmtime run` executes the program's
  top level like a command.

An exported function is a **raw core function**: scalars
(`:int`/`:float`/`:bool`) cross as plain numbers, so `wasmtime --invoke` and
`instance.exports.fact(5)` work directly. The memory-backed `:string` and
`:s-expr` designators pass a `(ptr, len)` pair through the module's exported
`memory`, together with a `__ronto_alloc(size)` bump allocator the host uses to
stage argument bytes — that protocol needs a host that can read and write
memory (JavaScript, not `wasmtime --invoke`), and is walked through end to end
in the [appendix](#appendix-calling-a-module-from-javascript). Instantiating
the module still needs the eight WASI imports satisfied; `wasmtime run`
provides them automatically, a browser host can supply no-op stubs for a
pure-compute function, or add [`--no-wasi`](#no-wasi-reactor-mode) to drop them
entirely.

Two behavioral notes on this value model:

- **Parameter limit.** A function (`defun` or `lambda`) may take at most
  **seven parameters** (the interpreter and JVM backends have no such limit). A
  fixed-arity `defun` past the limit is bundled automatically: the compiler
  keeps the first six parameters, packs the rest into a list, and rewrites
  every direct call site to match — so wide library signatures compile
  unchanged. Taking such a function's value with `#'name`/`symbol-function` is
  a compile error (only direct calls know the bundled shape), and a `lambda` or
  variadic function past the limit still errors — bundle those arguments into a
  list yourself. The rest list of a variadic function counts as one parameter,
  so a `&rest` function may declare at most six required parameters while
  accepting any number of arguments at a direct call site.
- **Float printing shape.** Floats of every magnitude print on WASM: the
  integer part is exact up to 2⁶³, larger values fall back to an approximate
  exponent form (`1.0E19`), and `Infinity`, `-Infinity` and `NaN` print as
  those words, like the other backends. One shape difference remains: from 10⁷
  up to 2⁶³ WASM prints all the digits (`1500000000000.0`) where the
  interpreter and the JVM use exponent notation (`1.5E12`);
  `rontolisp:json-stringify` inherits that shape difference.

### Reclaiming the Host's Buffer (the Arena API)

The engine collects everything the Lisp side allocates — cons cells, closures,
strings — so a wasm-GC module needs no memory discipline *inside*. The one thing
the engine cannot see is the buffer the **host** wrote the argument bytes into:
that is linear memory, an opaque byte array it never traces, handed out by the
`__ronto_alloc` bump allocator, which never frees. A resident host that
allocates a fresh input buffer per call therefore grows linear memory without
bound.

So a module that exports its `memory` also exports a matched pair over the same
heap pointer:

| export | signature | meaning |
| --- | --- | --- |
| `__ronto_alloc_mark` | `() -> i32` | snapshot the current bump-heap top |
| `__ronto_alloc_reset` | `(i32 mark) -> ()` | restore the top to a saved mark |

Snapshot **before** allocating the input, restore **after** reading the result,
and a resident instance stays flat no matter how many times it is called or how
long each input is:

```js
const countVowels = (s) => {
  const b = enc.encode(s);
  const mark = ex.__ronto_alloc_mark();          // snapshot BEFORE allocating
  const ptr = ex.__ronto_alloc(b.length);        // a fresh buffer, any length
  new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
  const n = ex['count-vowels'](ptr, b.length);   // scalar result, read out here
  ex.__ronto_alloc_reset(mark);                  // pop the input buffer
  return n;
};
```

Two rules, as for any arena:

- Only reset to a mark taken **before** everything still live.
- A `:string`-**returning** export leaves its result bytes in memory: **decode
  them before resetting**, or the next allocation overwrites them.

One backend-specific guard: on the GC backend the same heap pointer also holds
the interned-symbol byte pool (a symbol's identity *is* its offset there), so
`__ronto_alloc_reset` never pops below that pool's high-water mark. A call that
interns a new symbol (`read`, `intern`, `gensym`) therefore keeps its input
buffer; every other call pops all the way back. Nothing to do host-side.

The bracket is the same one the
[`count-vowels` example](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)
walks through on `--no-gc` (from Node and from [Endive](https://endive.run)) —
the boundary protocol does not change with the backend, only what you may write
inside the function does. Under [`--component`](#wasi-03-component---component)
there is no arena API and nothing to bracket: the canonical ABI's `post-return`
frees the argument strings for you.

### Importing Host Functions

`rontolisp:wasm-import` is the reverse of `wasm-export`: it declares a function
the **host** provides and makes it callable from Lisp under the given name
exactly like a top-level `defun` — including `#'name`, `funcall`, `mapcar` and
`eval`. `:from` names the import module (default `"env"`), `:as` names the
field inside it (default: the Lisp name), and the type designators are the same
table as above:

```lisp
; main.lisp
(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
(defun add10 (n) (add n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

In wasmtime, satisfy the imports by preloading another module that exports them
— here a host module that is itself written in Lisp, exporting its function
under the `:as` alias `add`:

```console
$ cat host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
$ rontolisp host.lisp -o host.wasm --no-wasi
$ rontolisp main.lisp -o main.wasm --no-wasi
$ wasmtime run -W gc --preload host=host.wasm --invoke add10 main.wasm 32
42
```

In a browser (or Node) the import object *is* the module table — one key per
`:from` name, one property per `:as` name. This is also the escape hatch for
anything the WASM backend does not provide; for example it has no trigonometric
built-ins, so borrow JavaScript's:

```lisp
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

```js
const imports = { math: { sin: Math.sin, cos: Math.cos } };
const { instance } = await WebAssembly.instantiate(bytes, imports);
```

The [WebGL triangle example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-triangle)
is the hello world of this pattern: ten imported functions, no exports, and a
colored triangle drawn entirely from Lisp. The
[WebGL cube example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-cube)
adds 3D: the perspective and rotation matrices are computed in Lisp every
frame. The
[WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)
is the same idea grown into a complete browser program: the entire WebGL
pipeline is driven from Lisp — the GLSL shaders live in the Lisp source, and
Lisp compiles, links, buffers and issues every draw call through 34 imported
host functions, while JavaScript supplies only one-line bindings over a handle
table.

Boundary details beyond the scalar types:

- A `:string`/`:s-expr` **argument** reaches the host as a `(ptr, len)` pair
  into the module's exported `memory` (an `:s-expr` argument is printed to
  readable text first).
- A `:string` **result** must be written into linear memory by the host —
  reserve the buffer with the exported `__ronto_alloc`, then return the
  `(ptr, len)` pair (a two-element array in JavaScript).
- An `:s-expr` **result** is parsed with the embedded reader, so the host can
  hand back a whole list structure as text.

Limitations:

- Default (wasm-GC) Preview 1 output only: `--component` and `--no-gc` reject
  the directive with an error.
- On the interpreter and JVM backends the directive defines a stub that signals
  an error when called, so a shared source still loads everywhere, but actually
  calling an import needs the WASM host.
- Imported functions have the same 7-parameter arity limit as other functions.
- Instantiating the module requires every declared import to be provided:
  `wasmtime run` needs a `--preload <module>=<file>.wasm` per import module
  name, and a JavaScript host passes an import object.

### No-WASI (Reactor) Mode

Add `--no-wasi` to emit a Preview 1 module that imports **no** WASI functions,
so a host can instantiate it with no import object at all — a
"reactor"/library module whose only surface is the exported Lisp functions:

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

A reactor is just as easy to drive from JavaScript: there is **no import
object**, so the host side is just "instantiate, then call the exports"
(`WebAssembly.instantiate(bytes).then(({ instance }) => instance.exports.fact(5))`).
A complete, copy-paste runnable Node + browser example is in the
[appendix](#appendix-calling-a-module-from-javascript) at the end of this page.

The eight WASI import slots are filled with internal trap stubs so every
function index stays fixed (no other codegen changes). This mode is for
**pure-compute** exports only: any I/O (`print`/`read`/`open`/`getenv`/time/
`random`, including a top-level form that prints) hits a stub and **traps**. It
is Preview 1 only — `--no-wasi` is ignored under `--component`.

Because the module is a reactor (not a WASI command), its top-level initializer
is exported as **`_initialize`** rather than `_start`. A host should call
`_initialize` once after instantiation to run top-level forms
(`defvar`/`defparameter`/`setq` globals that an exported function reads);
pure-compute reactors that hold no top-level state can skip it.

## WASI 0.3 Component (`--component`)

Add `--component` to emit a WASI 0.3 (Preview 3) **component** instead of a
Preview 1 core module. The component prints through `wasi:cli/stdout@0.3.0`:

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y hello.wasm
```

```
3
```

In WASI 0.3 all byte I/O flows through the built-in component-model
`stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp keeps
the same Preview 1 core module unchanged — it still imports the eight
`wasi_snapshot_preview1` functions — and an **adapter** core module implements
them over WASI 0.3 (`wasi:cli`, `wasi:filesystem`, `wasi:clocks`,
`wasi:random`) using `stream.new`/`stream.read`/`stream.write` and
`future.read`. Those built-ins are the **asynchronous** (non-blocking)
variants: when one reports BLOCKED, the task parks on a blocking
`waitable-set.wait` until the completion event arrives, so the adapter stays
straight-line code. The component's `wasi:cli/run@0.3.0` export (an
`async func`) is lifted as an async-typed export, from which that blocking
wait is legal. All of this sits on the base component-model async ABI, enabled
by default in wasmtime 46+ — no gated feature flags remain; only `-W gc=y`
(for the wasm-GC core) is needed.

The wasmtime invocation does **not** select the output kind. `wasmtime run` is
wasmtime's default subcommand and auto-detects a core module vs a component, so
`wasmtime run -W gc` runs a Preview 1 `hello.wasm` just as well. Only the
`--component` compile flag decides whether a Preview 1 core module or a WASI
0.3 component is produced. (The practical difference shows up on a
component-only runtime, which runs the component but not the Preview 1 core
module.)

What works inside a component, and what each feature needs at run time:

- `print`/stdout, stdin (`read`, 0-argument `read-line`, over
  `wasi:cli/stdin@0.3.0`), and file I/O (`open`, `close`, `write-line`, stream
  `read-line`, `load`, `with-open-file`) all work. File access requires `--dir`
  (paths resolve against the first preopened directory):

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y --dir . fileio.wasm
# "hello"
```

- `random` draws real entropy from `wasi:random@0.3.0` (Preview 1 uses the
  host's `random_get`), so `(random N)` differs each run.
  `get-universal-time` / `get-internal-real-time` / `get-internal-run-time`
  read `wasi:clocks@0.3.0` (`system-clock`/`monotonic-clock`), and `getenv`
  reads `wasi:cli/environment@0.3.0`.
- Outgoing HTTP (`rontolisp:fetch` with the `rontolisp:await` /
  `rontolisp:futurep` future operations) works in
  component mode, including true asynchrony: `fetch` sends the request and
  returns a future (wrapping the in-flight `wasi:http` response handle)
  immediately, so several requests can overlap before `await` suspends on
  each. The future operations themselves compile in every mode; only `fetch`
  is component-only. fetch imports the async `wasi:http@0.3.0`
  (`wasi:http/types` + `wasi:http/client`) — uniformly WASI 0.3, like the rest
  of the component. Run a fetch component with `-S http=y` (which makes the
  host provide `wasi:http`) in addition to the usual flags. Non-fetch
  components do not import `wasi:http`, so they do not need `-S http`. A
  transport failure (refused connection, unresolvable host) signals
  `rontolisp:wit-error` at `await` time on every backend; `nil` comes back
  only for a request that cannot be started.
- TCP sockets (`rontolisp:tcp-connect` / `tcp-listen` / `tcp-accept` /
  `tcp-local-port`) work in component mode over `wasi:sockets@0.3.0` (natively
  WASI 0.3 — no 0.2 hybrid). A socket is a bidirectional stream handle, so
  `read-line` / `write-line` / `write-string` / `read-byte` / `write-byte` /
  `close` work on it directly. Run a socket component with `-W exceptions=y
  -S tcp=y -S inherit-network=y` in addition to the usual flags (a tcp
  component always compiles in exception-handling mode); without the `-S`
  flags the component still starts but every socket operation fails and
  yields `nil`. Hosts must be IPv4 literals (no hostname resolution yet).
  `rontolisp:fetch` and the tcp functions can be combined in one component,
  and tcp works inside a `rontolisp:http-handler` (serve) component. In an
  async body a pending `tcp-accept` or socket read suspends only its own
  task — other tasks (a `rontolisp:wait-for` timer, another request) keep
  running.
- The compiled Lisp otherwise behaves identically to the Preview 1 output for
  the supported features. Serving incoming HTTP (`rontolisp:http-handler`) also
  compiles to a component, but a different kind (exporting
  `wasi:http/handler@0.3.0`) run under `wasmtime serve` — see the
  [HTTP handler guide](../guides/http-handler.md).

### Component-model Function Exports (wasm-export)

Under `--component`, a [`rontolisp:wasm-export`](#exporting-lisp-functions)
becomes a **typed component-model export**, callable through the canonical ABI
with WAVE syntax (`wasmtime run --invoke 'name(args)'`, no experimental
warning) — and it co-exists with the `wasi:cli/run` command entry, so the same
component still runs as a command:

```lisp
(defun sumsquared (a b) (* (+ a b) (+ a b)))
(rontolisp:wasm-export 'sumsquared :params '(:int :int) :returns :int)
(print (sumsquared 10 10))
```

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm
wasmtime run -W gc=y --invoke 'sumsquared(2, 3)' sumsq.wasm
# 25    (the export's return value, rendered by wasmtime)
wasmtime run -W gc=y sumsq.wasm
# 400    (the ordinary run entry executes the top-level program)
```

The two commands print different things: `--invoke` calls **only** the named
export — the top-level program (the `wasi:cli/run` entry) does not run — and
the `25` is wasmtime rendering the export's return value in WAVE syntax, not
output from `print`. The plain `run` executes the top-level program instead,
so the `400` is the output of `(print (sumsquared 10 10))`.

The typed signature (`:int` → `s32`, `:float` → `f64`, `:bool` → `bool`,
`:string` → `string`, `:s-expr` → `string` carrying the printed s-expression
text, omitted `:returns` → no result) is visible to any component host, and
`:as` renames the component export just like the core one.

A `:string` boundary crosses as a real component-model `string` — no manual
pointer handling on either side. The host lowers the argument bytes into linear
memory and reads the result back out through the canonical ABI, and the module
frees the per-call allocations afterwards (a canonical *post-return* function
pops the bump allocator), so a resident instance stays flat across repeated
calls:

```lisp
;; greet.lisp
(defun greet (s) (concatenate 'string "Hello, " s))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y --invoke 'greet("世界")' greet.wasm
# "Hello, 世界"
```

By default an export is lifted **synchronously**. Even so, I/O inside it
usually works: the asynchronous built-ins complete without blocking whenever
the host accepts immediately (stdout does), and only a host that reports
BLOCKED forces the blocking wait, which traps in a synchronous task with
"cannot block a synchronous task". Declare the export async with
**`:async t`** to lift it against an async function type instead — the same
async-typed lift as the `run` entry — and remove that residual risk.
`wasmtime --invoke` calls an async export exactly the same way:

```lisp
;; status.lisp
(rontolisp:async-defun fetch-status (url)
  (print "fetching")
  (getf (rontolisp:await (rontolisp:fetch url)) :status))
(rontolisp:wasm-export 'fetch-status :params '(:string) :returns :int :async t)
```

```bash
rontolisp status.lisp --component -o status.wasm
wasmtime run -W gc=y -W exceptions=y -S http=y \
  --invoke 'fetch-status("https://httpbin.org/status/204")' status.wasm
# "fetching"
# 204
```

In the component's WIT-level contract an `:async t` export is an `async func`
(for example, jco types it as a Promise-returning function, while a sync export
stays a plain function). Sync and async exports mix freely in one component,
`:async` composes with every boundary type including `:string`/`:s-expr`, and a
program without `:async` exports produces byte-identical output.

Current limitations of component exports:

- A **sync** (default) export can usually do I/O anyway (the asynchronous
  built-ins complete without blocking when the host accepts immediately); only
  a host that reports BLOCKED makes the blocking wait trap with "cannot block
  a synchronous task". Opt into `:async t` when the export prints, fetches, or
  otherwise does I/O to remove that residual risk; keep pure-compute exports
  sync.
- `:async` is meaningful only here: Preview 1 / `--no-wasi` core exports ignore
  it (the host provides I/O directly there), and `--no-gc --component` rejects
  it (the compact reactor component has no async adapter).
- jco (1.25.2) transpiles an `:async t` export and types it as async, but
  cannot call it yet — its support for the 0.3 async ABI is not implemented
  upstream (the same gap as calling the transpiled `run`). `wasmtime run
  --invoke` is the verified path for async exports; sync exports work on both.
- The export name must be a lower-kebab-case component-model name
  (`sum-squared`); for a Lisp name outside that grammar the compiler asks you
  to rename it with `:as`.
- Invoking an export does not run the program's top level first, so an export
  that reads a `defvar`/`defparameter` global would see it uninitialized (this
  matches the Preview 1 `--invoke` behavior).

For a pure-compute export kit, the compact
[`--no-gc --component`](#compact-component-output---no-gc---component) variant
emits the same typed exports (plus `:long` → `s64`, minus `:s-expr`) in a
component of a few hundred bytes that needs no wasmtime flags at all.

### Emitting the WIT World (`--emit-wit`)

Add `--emit-wit` to any `--component` build to also write the component's WIT
description next to the `.wasm` output — `-o sumsq.wasm --emit-wit` writes
`sumsq.wit`:

```bash
rontolisp sumsq.lisp --component -o sumsq.wasm --emit-wit
```

```text
// sumsq.wit (the world; the file also carries the referenced package
// definitions, so it is self-contained and parseable on its own)
package root:component;

world root {
  import wasi:cli/types@0.3.0;
  import wasi:cli/stdout@0.3.0;
  // ... the WASI imports of the build's blob variant ...

  export wasi:cli/run@0.3.0;
  export sumsquared: func(p0: s32, p1: s32) -> s32;
}
```

The text matches what `wasm-tools component wit sumsq.wasm` prints for the same
bytes, so it is exactly the component's real surface — but nothing needs to
introspect the binary anymore: hand the `.wit` straight to a binding generator.
For example, jco generates TypeScript typings from it without touching the
`.wasm`:

```bash
npx @bytecodealliance/jco types sumsq.wit -o types/
# types/sumsq.d.ts: export function sumsquared(p0: number, p1: number): number;
```

The world's imports follow the build variant (plain, `rontolisp:fetch`,
`rontolisp:tcp-*`, or `rontolisp:http-handler`; with
[`--no-gc --component`](#compact-component-output---no-gc---component) the
world is import-free, or carries the `wasi:cli/stdout@0.3.0` import — and
`async func` exports — when the program prints), an `:async t` export is
rendered as `async func`, and a
`rontolisp:http-handler` build exports `wasi:http/handler@0.3.0` instead of
`run`. `--emit-wit` without `--component` is a compile error — a core module has no
WIT-level surface to describe.

### What `--emit-wit` Is For

It answers different questions depending on where the export list came from.

**A program without a world** — exports written by hand with
`rontolisp:wasm-export`, or an `:s-expr` export, which has no WIT spelling at
all — has no `.wit` anywhere. `--emit-wit` is the only way to get one, exactly
as above.

**A program with a world** ([`wit-export`](#implementing-a-wit-world-wit-export))
has already written its exports down. What it has not written down is the
component's **imports**, and that is the larger half: `wit-export` reads only the
world's `export` items, because a component's WASI surface comes from the fixed
adapter blob the build links, not from the world. The 6-line `wit/greeter.wit` of
the [next section](#implementing-a-wit-world-wit-export) compiles to a component
whose real type is **149 lines** — ten `wasi:*` imports and
`export wasi:cli/run@0.3.0` wrapped around the one `greet` you declared. Let that
same `greet` call `rontolisp:fetch` and the build silently adds two more imports
(`wasi:http/types`, `wasi:http/client`), for **216 lines**; `rontolisp:tcp-*`
pulls in `wasi:sockets` the same way. Short of installing `wasm-tools` and introspecting
the binary, `--emit-wit` is the only way to see what you actually built — and it
is precisely what a host, or `jco`, needs in order to *supply* those imports.

What `--emit-wit` is **not** — for a program that has a world — is a drift check
on that program. The export lines are a fixpoint by construction: the world
produces the `rontolisp:wasm-export` directives, those produce the component's
function types, and those are what is printed back out, over a boundary type set
(`s32`, `s64`, `f64`, `bool`, `string`) that maps one-to-one in both directions.
They cannot come out disagreeing with the world you handed in. Re-emitting the
`.wit` and diffing it in CI is therefore a regression check on *rontolisp's* type
mapping — cheap, and worth keeping — not a check on your source. The thing that
catches a drifted program is `wit-export` itself, and it already runs on every
backend, including a plain interpreter run. This is transitional: once a world
can also declare the imports a program binds, the emitted WIT becomes a genuinely
two-sided contract.

### Implementing a WIT World (`wit-export`)

Everything above starts from Lisp and *emits* a `.wit`.
**`rontolisp:wit-export`** turns that around: hand the compiler a world someone
else wrote, and the program **implements** it.

```console
// wit/greeter.wit
package example:greeter;

world greeter {
  /// Greet someone by name.
  export greet: func(who: string) -> string;
}
```

```console
;;; greet.lisp -- the directive comes last: on the interpreter it sees only the
;;; functions defined so far.
(defun greet (who)
  (concatenate 'string "Hello, " who "!"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

```bash
rontolisp greet.lisp --component -o greet.wasm
wasmtime run -W gc=y --invoke 'greet("world")' greet.wasm
# "Hello, world!"
```

There is no `:params '(:string) :returns :string` anywhere — the types come from
the world. That is the whole point: hand-written boundary types sit next to a
`.wit` that is generated separately, and the two drift until
`wasmtime --invoke` fails at run time. With `wit-export` **the WIT is the single
source of truth**:

- The world is the program's export list, so a hand-written
  `rontolisp:wasm-export` in the same program is a compile error.
- Every export must have a matching `defun` of the right arity, every WIT type
  must be one the boundary carries (`s32`, `s64`, `f64`, `bool`, `string`), and
  an `async func` in the world lifts that export with `:async t` (so an export
  that does I/O is declared async by the WIT instead of being guessed at). Each
  mismatch is a compile error naming the WIT file and line:
  `wit/greeter.wit:5: export 'greet' declares 1 parameter(s), but (defun greet ...) takes 2`.
- The contract is checked on **every** backend: a plain `rontolisp greet.lisp`
  run (or a `-o Greet.class` build) verifies the world and exports nothing, so a
  drift is caught long before a WASM build.

The directive is a front-end for the machinery of the previous sections, not a
second export path: it lowers into exactly the `rontolisp:wasm-export`
directives a hand-written implementation would carry, so **the emitted component
is byte-identical** to that one — on the GC path and under
[`--no-gc --component`](#compact-component-output---no-gc---component) alike
(the latter is the backend to pick when the world uses `s64`, which the wasm-GC
`i31ref` integers cannot hold).

Adding [`--emit-wit`](#emitting-the-wit-world---emit-wit) to the build writes out
the component's real type, and its export lines come back the way you wrote them,
parameter names included — the WIT's names ride through into the component's
function type. (A hand-written export names its parameters `p0`, `p1`, ... unless
it declares them itself with `:param-names '(who)`.)

```bash
rontolisp greet.lisp --component -o greet.wasm --emit-wit   # writes greet.wit
```

```text
export greet: func(who: string) -> string;
```

That line is a fixpoint, though, not a verdict: it is derived *from* the world, so
it cannot contradict it. The reason to emit anyway is the rest of the file — the
`wasi:*` imports and the `wasi:cli/run` export that the world says nothing about,
and that a host has to supply. `greet.wit` is 149 lines around that one export.
Two differences from the input are deliberate: the `///` doc comments are gone,
because a component's type does not store them (`wasm-tools` cannot recover them
either), and the emitted world is always `package root:component; world root`.
That is what a component's type *is*.

Current limitations:

- Only the world's **export** side is bound. `import` items are ignored (a
  component's WASI imports come from the fixed adapter surface it is built on —
  [`--emit-wit`](#emitting-the-wit-world---emit-wit) is how you see them), and an
  inline `import name: func(...)` is rejected rather than silently dropped; the
  functions a program calls are bound from an interface with
  [`wit-import`](#importing-a-wit-interface-wit-import) (or declared by hand with
  `rontolisp:wasm-import`).
- Only plain function exports are implemented; a world exporting an interface is
  an error, and a `rontolisp:http-handler` program cannot use a world at all
  (a serve-mode component's only export is `wasi:http/handler@0.3.0`).
- `:s-expr` has no WIT spelling, so an export passing an arbitrary s-expression
  across the boundary still needs a hand-written `rontolisp:wasm-export`.
- On the interpreter the directive is evaluated in order and sees only the
  functions defined so far, so put it at the end of the file.

### Scaffolding an Implementation (`--scaffold-wit`)

`--scaffold-wit` is the answer to "someone handed me a `.wit`, now what": it
generates the skeleton of an implementation instead of compiling one.

```bash
rontolisp --scaffold-wit wit/greeter.wit -o greet.lisp   # no -o: print to stdout
```

```console
;;;; Implementation of the WIT world 'greeter' (wit/greeter.wit).
;;;;
;;;; The world is the contract: the compiler checks every defun below against
;;;; it, so a renamed export, a changed arity or a changed type is a compile
;;;; error rather than a runtime surprise. Fill in the bodies; each one signals
;;;; until you do.

;;; Greet someone by name.
;;; WIT: greet: func(who: string) -> string
(defun greet (who)
  (error "greet is not implemented yet"))

(rontolisp:wit-export "wit/greeter.wit" :world greeter)
```

The parameters are named as the WIT names them, each export's WIT signature is
carried above its stub as the contract it must satisfy, and the `///` doc
comments become `;;;` comments. The stubs signal at **run** time, not compile
time, so the generated file compiles unchanged and the exports can be filled in
one at a time. Add `--world NAME` when the `.wit` declares several worlds.

## Importing a WIT Interface (`wit-import`)

`wit-export` is the export side of a WIT contract.
**`rontolisp:wit-import`** is the import side: it declares that the program
**calls** a WIT interface, and binds every function that interface declares as an
ordinary Lisp function — its name, its lambda list and its types all taken from
the `.wit`. It is a compile-time directive that lowers into forms that already
exist, and *what* it lowers to depends on the backend. That is the whole point:
**one WIT, a different implementation per backend, zero source changes.**

```console
// wit/host.wit
package example:host@0.1.0;

interface math {
  /// Add two integers on the host.
  add-ints: func(a: s32, b: s32) -> s32;
}
```

```console
;;; main.lisp -- the directive comes FIRST: it defines the functions the rest of
;;; the file calls.
(rontolisp:wit-import "wit/host.wit" :interface "example:host/math@0.1.0")

(defun add10 (n) (add-ints n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

On Preview 1 WASM each WIT function becomes a
[`rontolisp:wasm-import`](#importing-host-functions): the import **module** is
the interface's bare name (`math`, overridable with `:from`) and the import
**field** is the WIT label in camelCase (`addInts` — the JavaScript convention,
and what `jco` produces; `:field-style :kebab` keeps the label verbatim). So the
host is satisfied exactly as before — here by another Lisp module that exports
the function under that field name:

```console
;;; host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "addInts" :params '(:int :int) :returns :int)
```

```bash
rontolisp host.lisp -o host.wasm --no-wasi
rontolisp main.lisp -o main.wasm --no-wasi
wasmtime run -W gc --preload math=host.wasm --invoke add10 main.wasm 32
# 42
```

The module is **byte-identical** to the one the hand-written
`(rontolisp:wasm-import 'add-ints :from "math" :as "addInts" :params '(:int :int) :returns :int)`
produces — the directive is a typed front-end for that machinery, not a second
import path — and [`--optimize`](#optimize-tree-shaking) still shakes out the
imports the program never calls, so binding a 34-function interface and using
three of them costs nothing.

### Providers: the same source on the interpreter and the JVM

There is no WASM host on the interpreter or the JVM, so there each WIT function
becomes an ordinary `defun` that dispatches through the interface's **provider**:
a Lisp callable taking the bound function's Lisp member name (a string) followed
by that function's arguments. [`rontolisp:wit-provide`](../reference/functions/rontolisp-wit-provide.md)
binds one — and rontolisp ships **no provider for any interface**. It knows the
provider mechanism; it does not know what `wasi:keyvalue` is. Implementing a WIT
interface is ordinary Lisp code:

```console
;;; counter.lisp -- wasi:keyvalue, against a store written in Lisp.
(rontolisp:wit-import "wit/store.wit" :interface "wasi:keyvalue/store@0.2.0" :package kv)

(defvar *rows* (make-hash-table :test #'equal))

(defun my-store (member &rest args)
  (cond ((string= member "open") 1)              ; the bucket handle: any integer
        ((string= member "bucket-set")
         (setf (gethash (nth 1 args) *rows*) (nth 2 args))
         nil)
        ((string= member "bucket-get") (gethash (nth 1 args) *rows*))
        (t (error 'rontolisp:wit-error :payload (list :other member)))))

(rontolisp:wit-provide "wasi:keyvalue/store@0.2.0" #'my-store)

(defvar *bucket* (kv:open "counts"))

(kv:bucket-set *bucket* "visits" "41")
(print (kv:bucket-get *bucket* "visits"))   ; "41"
```

`:package kv` synthesizes the `defpackage` that exports the bindings, a WIT
`resource` method takes its handle as the first argument (`bucket.get` becomes
`(kv:bucket-get b "visits")`), and each binding is an ordinary function, so
`#'kv:bucket-get`, `funcall` and `mapcar` work on it. Calling one with no
provider bound signals `rontolisp:wit-error` — `No provider is bound for the WIT
interface wasi:keyvalue/store@0.2.0 -- bind one with rontolisp:wit-provide` —
rather than reaching some default.

The payoff is that a provider is *just a function*: swap the hash table above for
a real store — Redis, a file, a JDBC connection — and the code calling
`(kv:bucket-set b "visits" "41")` does not change. The
[`wit/keyvalue` example](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
runs one page-view counter over three of them (a portable Lisp store, a
`java.util.LinkedHashMap` one on the JVM, and wasmtime's own `wasi:keyvalue`
implementation as a component) with identical output. Compile the same
source to WASM instead and the **host** implements the interface: a top-level
`rontolisp:wit-provide` is then **dropped** (the host is the provider), rather
than being an error, precisely so that one source runs everywhere.

A WIT `result<T, E>` is not a value: the ok arm is the return value, and the
error arm signals the `rontolisp:wit-error` condition carrying the mapped `E`,
which `handler-case` catches and `rontolisp:wit-error-payload` unpacks.

### Components: the host is the provider (`--component`)

Compile the very same source with `--component` and the interface becomes a real
component-model **import**: the component declares it in its type, and every bound
function is `canon lower`ed into the core module, so the calls go out through the
canonical ABI. There is no provider inside the component at all — **the host is the
provider**, and any host (or any other component) that exports the interface satisfies
it. wasmtime implements `wasi:keyvalue`, so a program written against it runs with no
adapter and no rewriting:

```bash
rontolisp counter.lisp -o counter.wasm --component
wasmtime run -W gc=y -W exceptions=y \
    -S keyvalue=y counter.wasm
```

The canonical ABI is what marshals the rich types, so the component boundary carries
much more than the Preview 1 one: a `result` (whose error arm arrives as a
`rontolisp:wit-error` condition, caught with `handler-case`), an `option`, a `record`
(a keyword plist), a `variant`, an `enum`, a `tuple`, a `list<T>`, a `list<u8>`, a
`string`, a `bool`, and `resource` handles.

Everything but `list<T>` crosses **in both directions**, and an argument takes exactly
the shape the same type takes as a return value — so a value one call hands you goes
straight into the next:

```console
;;; wasi:http/types, imported and called: a variant argument, whose `other` case
;;; carries a string
(http:outgoing-request-set-method req :post)
(http:outgoing-request-set-method req '(:other . "PATCH"))
(http:outgoing-request-method req)                 ; => (:other . "PATCH")

;;; wasi:sockets/types: an enum argument, then a variant whose case payload is a
;;; record (a keyword plist) carrying a tuple (a positional list)
(let ((s (sock:tcp-socket-create :ipv4)))
  (sock:tcp-socket-bind s '(:ipv4 :port 0 :address (127 0 0 1))))
```

The one shape that still does not lower is a **`list<T>` argument** (`list<u8>` does,
as a byte string): an argument is flattened, and a list would have to be written into
linear memory as a canonical array instead. It is a compile error naming the WIT line,
and `flags` does not cross in either direction yet.

One interface a component **cannot** bind is one it already imports for its own WASI
surface — and that surface grows with what the program uses (`rontolisp:fetch` pulls in
`wasi:http/types` and `wasi:http/client`, the `rontolisp:tcp-*` built-ins pull in
`wasi:sockets/types`). A component cannot import the same interface twice, so that is a
compile error too: drive the interface through the WIT binding *instead of* the
built-in, not alongside it.

A component imports **only the functions the program actually calls** (there is no core
tree shaker on this path, so unused interface members are dropped from the import
itself; `--no-prune` keeps them all), and [`--emit-wit`](#emit-wit) writes that pruned
interface into the component's world — where `wasm-tools component wit` agrees with it,
byte for byte. A component that imports nothing is byte-identical to one built before
any of this existed.

That is also how components **compose**: a component that imports
`wasi:keyvalue/store` plugs into any component that exports it, in any language, with
[`wac`](https://github.com/bytecodealliance/wac). The host does not have to be a
runtime built-in.

#### A served handler with a real store

A **served** component ([`rontolisp:http-handler`](../guides/http-handler.md) +
`--component`) imports user interfaces the same way: its imports are not only the
fixed `wasi:http` surface it exports through. That is what lets a handler keep
state at all — a `wasi:http` host instantiates the component **afresh for every
request**, so a global hash table reads back empty every time, while a store lives
outside it:

```bash
rontolisp page-hits-server.lisp -o server.wasm --component
wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
curl http://127.0.0.1:8080/index
```

Whether the counts then *survive* is the host's business, not the component's:
wasmtime's built-in key-value provider is an in-memory store it rebuilds per
instance (so, under `wasmtime serve`, per request), while a host that links an
out-of-process provider keeps them — on wasmCloud (`wash dev`) the same component
counts 1, 2, 3. The interfaces a served component may *not* bind are the ones its
own surface already imports: `wasi:http/types`, `wasi:http/client`,
`wasi:cli/types`, `wasi:cli/stdout`, `wasi:cli/stderr`, `wasi:clocks/*` and
`wasi:random/random`.

The full example is [`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/main/examples/wit/keyvalue).

### Releasing a resource (`<resource>-drop`)

A handle has to be given back, and **WIT declares no function for giving it
back**: releasing a resource is a canonical built-in of the component model, not
a member of the interface. So rontolisp names it — **`<resource>-drop`**, one
argument, the handle — symmetric with the `<resource>-new` a constructor binds:

```console
(let ((bucket (kv:open "")))
  (kv:bucket-set bucket "visits" "41")
  (print (kv:bucket-get bucket "visits"))
  (kv:bucket-drop bucket))
```

It is bound **only when the program names it** (`--no-prune` and `--dynamic` bind
every resource's drop instead), which is why a component compiled before drops
existed comes out byte-identical — a WIT *function*, by contrast, is bound
whether the program calls it or not. On the interpreter and
the JVM the drop reaches the interface's provider as the member `"bucket-drop"`,
so what it *means* is the provider's decision: forget the handle, close the
connection, or answer `nil` because there is nothing to release. On Preview 1 it
is a **no-op** — a handle there is an opaque integer the host handed over, and
rontolisp will not invent an import for a function the WIT never declared. Under
`--component` it becomes `canon resource.drop`, handing the handle back to the
host's own table.

This is not only about leaks. An interface may make dropping an **obligation**:
`wasi:http` requires an `outgoing-body`'s child `output-stream` to be dropped
before the body is finished, and traps if it is not. And a drop releases the
*reference*, never the thing behind it — the store stays, and the next `kv:open`
sees every key still in it.

Current limitations:

- `--no-gc` rejects the directive with a clear error: its contract is a plain MVP
  module that imports nothing at all.
- On the Preview 1 boundary only the types `rontolisp:wasm-import` can carry
  cross — the integer scalars up to 32 bits, the float scalars, `bool`,
  `string`, `list<u8>` and resource handles. A `record`, `option`, `result` or
  `s64` is a compile error naming the WIT file and line, even though
  `--component`, the interpreter and the JVM all bind it (the `wasi:keyvalue`
  program above is therefore a component or an interpreter/JVM program, not a
  Preview 1 one: its `result` arms keep it off that boundary). A core import is a
  bare host function, with no component type to describe a richer shape with.
  `stream` and `future` are rejected on every backend.
- Under `--component` a **`list<T>` argument** (other than `list<u8>`), and
  `flags` anywhere, is a compile error; a `list<T>` still crosses as a result.
- The directive binds an **interface**. A world's `import` items are still not
  read.
- It must appear at top level **before** the code that calls the interface — it
  is what defines the package and the bindings — which is the opposite of
  `wit-export`.

The [wit-import](../reference/functions/rontolisp-wit-import.md) and
[wit-provide](../reference/functions/rontolisp-wit-provide.md) reference pages
carry the full option list, the name-mapping rules and the WIT type table.

## Non-GC Output (`--no-gc`)

Every GC-value-model output above — even an optimized reactor — still needs a
**wasm-GC capable** runtime, because every value is a GC heap type (`i31ref`,
the float struct, `(ref eq)`). Add `--no-gc` to emit a plain **MVP** module
instead: no rec group, no `struct`/`array`/`i31` type, no `eqref` and no import
(a plain linear memory is added only when the program uses strings — see
[below](#strings) — and the single `fd_write` import only when it
[prints](#printing-print--princ--terpri)). A print-free module instantiates
with no import object and runs on any MVP-class runtime with **no `-W gc`**:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, no -W gc needed
```

It achieves this by lowering each value directly onto an unboxed wasm scalar,
plus a small linear-memory representation for strings — so the eligible subset
is a restriction of the language, not a different one. The program shape is
also restricted: the top level may contain **only** `defun`s and
`rontolisp:wasm-export` directives (a pure-compute reactor — there is no
`_start`), and the boundary designators are `:int`, `:long`, `:float`, `:bool`,
`:string` (and `:void`/omitted); `:s-expr` is **not** supported — it would need
the cons/reader/printer runtime this backend deliberately omits.

Numeric vector kernels (the [`vec:` package](../guides/simd-acceleration.md))
work under `--no-gc` too, lowered to plain scalar loops by default — so a
vector program keeps the "runs on any MVP runtime" property above. Add
[`--simd`](#simd-acceleration---simd) to lower those kernels to native
WebAssembly SIMD (`v128`) instead, which then needs a runtime with the SIMD
proposal (on by default in wasmtime).

### Eligible subset

A function is eligible only if its **entire transitive call graph** stays
inside this subset:

- numbers and booleans: arithmetic (`+ - * / mod rem 1+ 1- abs min max sqrt`),
  the integer bitwise operators (`logand logior logxor lognot ash`), comparison
  and predicates (`= < <= > >= not zerop plusp minusp evenp oddp`);
- control and binding: `if`/`when`/`unless`/`cond`/`progn`/`let`/`let*`,
  recursion and calls to other eligible functions;
- iteration and local mutation: `dotimes`/`do`/`do*` and the underlying
  `while`/`setq`/`return`, with a let/`do`-bound variable freely reassigned;
  `loop` is eligible only for its non-consing clauses (numeric `for`,
  `sum`/`count`/`maximize`/`minimize`, `repeat`/`while`/`until`/`do`/`return`)
  — its `collect`/`append`/`nconc` and `for ... in`/`on` clauses allocate lists
  and are not;
- float/int conversions: `float truncate floor ceiling round`;
- strings and characters: string literals, character literals,
  `(concatenate 'string ...)`, `length`, `subseq`, `string=`, `char`,
  `char-code`/`code-char`, `char=` and `princ-to-string` (of integers, floats
  and strings). There is no separate character type: a character is represented
  by its code point, so the portable idioms `(char= (char s i) #\x)` and
  `(char-code (char s i))` behave exactly like the other backends, while a bare
  `(char s i)` crossing an `:int` boundary shows the code;
- printing: `print`, `princ` and `terpri` (without the optional stream
  argument) — see [below](#printing-print--princ--terpri);
- memory reclamation:
  [`rontolisp:with-arena`](#reclaiming-from-lisp-rontolispwith-arena).

Anything else that would allocate a heap object (cons/list, symbols, vectors,
hash tables, `eval`/`apply`, I/O, `dolist`/list iteration, a free variable or
assignment to a global, a lambda-list keyword such as `&optional`/`&rest`/
`&key` — the rest list is a cons) makes the function ineligible. Rather than
miscompile silently, that is a **compile error** naming the offending
operation, so the boundary stays explicit.

### Numeric model

Each value's wasm type is chosen by static type inference: integers use `i64`,
floats use `f64`. Types are inferred with a fixpoint over the call graph seeded
by the export boundary designators, and where an integer and a float meet
(e.g. `(* 3.14 n)`) the integer is promoted to `f64`. Using `i64` makes integer
arithmetic exact to 2^63 — far wider than both the GC backend's `i31` fixnums
and what an all-`f64` lowering (exact only to 2^53) could offer; for example
`a*a - (a-1)*(a+1)` stays exactly `1` even when the intermediates exceed 2^53.

Inference also widens automatically: a let/`do`-bound variable takes the join
of its initializer and every value assigned to it, so an integer accumulator
summed with floats becomes an `f64`:

```lisp
(defun sum-squares (n)        ; sum of i*i for i in 0..n-1, as a float
  (let ((acc 0))              ; acc starts as an integer 0 ...
    (dotimes (i n)
      (setq acc (+ acc (* (float i) (float i)))))  ; ... and widens to f64 here
    acc))
(sum-squares 5)  ; => 30.0
```

Under `--no-gc` this infers `acc` (and the return value) as `f64` while the
loop counter `i` stays `i64`.

There is no rational type, so two things differ from full Common Lisp and from
the GC backend: `/` is floating-point division (no `1/3` ratios), and a value
is false in a boolean context exactly when it is zero (Common Lisp treats only
`nil` as false). The **boundary** designators stay host-width — `:int`/`:bool`
cross as a 32-bit `i32` (as in the GC backend), so a returned value outside the
32-bit range wraps; the wide `i64` range applies only to the internal
computation. When a parameter or result can exceed the 32-bit range, declare it
`:long` — it crosses the boundary as `i64` with no `wrap`/`extend` (`:long` is
`--no-gc`-only; the GC backend rejects it, its integers being `i31ref`). For
the numeric kernels this mode targets (factorials, math/finance functions,
validators) the results match the interpreter and the GC backend.

### Strings

A string is an `i32` pointer to a `[length][bytes]` header in linear memory,
and `(concatenate 'string ...)` bump-allocates a fresh buffer — so building up
a string is just an accumulator loop:

```lisp
(defun stars (n)               ; an n-character run of '*'
  (let ((out ""))
    (dotimes (k n)
      (setq out (concatenate 'string out "*")))
    out))
(stars 5)  ; => "*****"
```

Slicing and inspection work on the same representation: `length` reads the
header, `subseq` copies a slice into a fresh buffer, `string=` compares content
byte-wise, `char` indexes a byte, and `princ-to-string` renders an integer —
enough for routing/parsing kernels, not just accumulation:

```lisp
(defun describe-int (n)
  (let ((s (princ-to-string n)))
    (concatenate 'string s " has " (princ-to-string (length s)) " chars")))
(describe-int -42)  ; => "-42 has 3 chars"
```

A module that uses strings gains a (growable) linear memory, and exports that
`memory` plus a `__ronto_alloc(size)` bump allocator alongside your functions.
A `:string` parameter arrives as a `(ptr, len)` pair the host writes into
memory, and a `:string` result is returned the same way — so a string-valued
export needs a host that can read/write the exported memory (JavaScript, a
small Node script, the browser playground) rather than just
`wasmtime --invoke`. The [appendix](#passing-strings-string) walks through the
JS side, and [`--no-gc --component`](#compact-component-output---no-gc---component)
removes the manual protocol entirely.

This is what lets the ASCII-art Mandelbrot renderer run with no wasm-GC:
[`examples/console/mandelbrot-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/console/mandelbrot-nogc.lisp)
keeps the floating-point escape-time loop but returns the rendered grid as one
string instead of printing it:

```console
$ rontolisp examples/console/mandelbrot-nogc.lisp --no-gc --optimize -o mandelbrot.wasm
$ node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("mandelbrot.wasm"), {})).instance.exports;
  const [p, n] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
  process.stdout.write(Buffer.from(new Uint8Array(ex.memory.buffer, p, n)).toString());
})()'
```

### Printing (`print` / `princ` / `terpri`)

An exported function can print: `print` (readable form plus a trailing newline,
so strings come out quoted), `princ` (display form, no newline) and `terpri` (a
newline) work inside the eligible subset, with output byte-identical to the
interpreter:

```console
$ cat show.lisp
(defun show (n)
  (print n)
  (print (* 1.5 n))
  (print "done"))
(rontolisp:wasm-export 'show :params '(:int) :returns :void)
$ rontolisp show.lisp --no-gc -o show.wasm
$ wasmtime run --invoke show show.wasm 4
4
6.0
"done"
```

Floats print through the same digit-extraction printer as the GC backend,
including the IEEE edges (`NaN`, `Infinity`/`-Infinity`, `-0.0`; a magnitude ≥
2^63 uses the WASM backends' `E`-notation shape). Each `print` of a number
renders its text into a transient string that is reclaimed immediately, so a
print loop does not grow the heap.

Two things to know:

- **A printing module has one import.** `print`/`princ`/`terpri` write through
  a single `wasi_snapshot_preview1.fd_write` import — added **only when the
  program prints**, so a print-free module keeps zero imports and its exact
  bytes. Any WASI Preview 1 host provides `fd_write` for free (`wasmtime run`,
  Node's built-in `node:wasi` module), but a printing module no longer
  instantiates with an empty `{}` import object the way the
  [Mandelbrot snippet](#strings) does — a raw JavaScript embedder must supply
  `{ wasi_snapshot_preview1: { fd_write } }` (or use `node:wasi`).
- **Booleans print by literal only.** The value model has no runtime boolean
  type: `(print t)` / `(print nil)` print `t` / `nil`, but a *computed* boolean
  such as `(print (> a b))` prints its `0`/`1` integer. The optional stream
  argument and printing a packed float array are compile errors.

### Reclaiming memory (the arena API)

`__ronto_alloc` is a bump allocator that never frees, so a **resident** host —
one that keeps a single instance alive and calls it in a loop, allocating a
fresh input buffer each time — grows its linear memory without bound. Two
mechanisms keep it flat:

- **Automatic, for scalar returns.** When an export returns a non-memory scalar
  (`:int`/`:long`/`:float`/`:bool`/`:void`), its wrapper snapshots the heap top
  on entry and restores it on exit, so everything the *call* allocates (the
  internal copy of a `:string` argument, plus any
  `concatenate`/`subseq`/`princ-to-string` scratch) is reclaimed on return.
  Nothing to do host-side.
- **Manual, for the host's own buffer.** The host allocates its input buffer
  *before* the call, so it sits below the wrapper's auto-reset mark and is left
  live. To reclaim it too, the string-using module also exports a matched pair
  over the same heap pointer:

| export | signature | meaning |
| --- | --- | --- |
| `__ronto_alloc_mark` | `() -> i32` | snapshot the current bump-heap top |
| `__ronto_alloc_reset` | `(i32 mark) -> ()` | restore the top to a saved mark |

Snapshot **before** allocating the input, restore **after** reading the result,
and a resident instance stays perfectly flat no matter how many times it is
called:

```bash
node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("count_vowels.wasm"), {})).instance.exports;
  const enc = new TextEncoder();
  const countVowels = (s) => {
    const b = enc.encode(s);
    const mark = ex.__ronto_alloc_mark();        // snapshot BEFORE allocating input
    const ptr = ex.__ronto_alloc(b.length);
    new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
    const n = ex.count_vowels(ptr, b.length);    // scalar result read out here
    ex.__ronto_alloc_reset(mark);                // pop the input + wrapper scratch
    return n;
  };
  const before = ex.memory.buffer.byteLength;
  for (let i = 0; i < 100000; i++) countVowels("Hello, World! " + i);
  console.log(before, "->", ex.memory.buffer.byteLength);   // 65536 -> 65536 (flat)
})()'
```

The arena is a manual stack, not a garbage collector, so two rules apply:

- Only reset to a mark taken **before** everything still live — popping to a
  mark taken *after* data you still need frees that data.
- A `:string`-**returning** export does *not* auto-reset (its result is a live
  heap pointer). **Read the returned bytes out of memory before calling
  `__ronto_alloc_reset`** — resetting first frees the string and the next
  allocation overwrites it.

The [`count-vowels` example](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)
walks through this recipe with both a Node and an [Endive](https://endive.run)
(Java) host.

The wasm-GC backend exports the same `__ronto_alloc_mark`/`__ronto_alloc_reset`
pair with the same host recipe (see [above](#reclaiming-the-hosts-buffer-the-arena-api)),
but only there does the *host's* buffer need reclaiming — the engine handles
everything the Lisp side allocates. The automatic scalar-return reset is
`--no-gc`-only: it is sound because nothing a `--no-gc` call allocates can
outlive it (no cons, closures, hash tables or global `setq` in the subset).

### Reclaiming from Lisp (`rontolisp:with-arena`)

Both mechanisms above fire at the **export boundary** — nothing is freed
*within* one call. A loop that allocates each iteration
(`concatenate 'string` builds a fresh buffer, `vec:zeros`/`vec:ones` a fresh
vector) therefore grows the heap for the duration of the call.
[`rontolisp:with-arena`](../reference/macros/rontolisp-with-arena.md) names
that reclamation boundary in the source: it snapshots the bump heap pointer,
runs its body, and pops everything the body allocated — keeping only the body's
own value (a string or packed float array result is copied down to the snapshot
point):

```lisp
(defun train (epochs n)
  (let ((acc 0.0))
    (dotimes (i epochs)
      (rontolisp:with-arena ()                    ; everything allocated inside ...
        (setq acc (+ acc (vec:sum (vec:ones n)))) ; ... is popped here
        ))
    acc))
```

With the arena, a hundred thousand iterations stay within the initial linear
memory; without it, the same loop grows by one vector per iteration. The escape
contract is the same as `__ronto_alloc_reset`'s: **nothing allocated inside the
body may be reachable after it, except the body's own value.** On the
interpreter, the JVM backend and the default (wasm-GC) output, `with-arena` is
observationally a plain `progn` — a real garbage collector already reclaims —
so the same source runs on every backend.

### Compact Component Output (`--no-gc --component`)

Add `--component` to wrap the same MVP core module as a **WASM component**
whose exports become typed component-model exports, callable through the
canonical ABI with WAVE syntax. A print-free core module has zero imports, so
the wrap needs no WASI adapter, no shared-memory module and no wasm-GC — the
whole component stays in the hundreds of bytes for a small program and runs
with **no wasmtime flags at all**:

```bash
rontolisp fact.lisp --no-gc --component -o fact.wasm
wasmtime run --invoke 'fact(5)' fact.wasm
# 120
```

The typed WIT signature maps `:int` → `s32`, `:long` → `s64`, `:float` → `f64`,
`:bool` → `bool`, `:string` → `string`, and an omitted `:returns` → no result.
The component also transpiles with jco (`jco transpile`, where `:long` surfaces
as a JavaScript BigInt) and runs on any component-model host, with no wasm-GC
support required.

`:long` is valid here, unlike the GC component path — use it when a value can
exceed the 32-bit range, matching the backend's internal `i64` arithmetic:

```lisp
;; cube.lisp
(defun cube (n) (* n n n))
(rontolisp:wasm-export 'cube :params '(:long) :returns :long)
```

```bash
rontolisp cube.lisp --no-gc --component -o cube.wasm
wasmtime run --invoke 'cube(2000000)' cube.wasm
# 8000000000000000000
```

A `:string` boundary crosses as a real component-model `string` — no manual
pointer handling on either side. The host lowers the argument bytes into the
module's own memory and reads the result back out through the canonical ABI,
and the module frees every per-call allocation afterwards (a canonical
*post-return* function pops the bump allocator to its base), so a resident
instance stays flat across repeated calls:

```bash
rontolisp greet.lisp --no-gc --component -o greet.wasm
wasmtime run --invoke 'greet("world")' greet.wasm
# "Hello, world"
```

[Printing](#printing-print--princ--terpri) works here too: a program that
prints gets a built-in **print micro-adapter** — three tiny fixed core modules
that implement the core's single `fd_write` import over WASI 0.3
(`wasi:cli/stdout`'s `write-via-stream` plus the async stream/future
built-ins), wired in only when the program prints. WASI 0.3 has no synchronous
write, so the exports of a printing program become **async lifts** (the WIT
world shows them as `async func`) — which is why the component still runs with
zero flags: everything it uses is base component-model async, on by default in
wasmtime 46+ (the wasmtime floor for a *printing* component; a print-free one
has no imports at all and runs on older hosts too). The print output is
byte-identical to the interpreter — with the earlier `show.lisp`:

```bash
rontolisp show.lisp --no-gc --component -o show.wasm
wasmtime run --invoke 'show(4)' show.wasm
# 4
# 6.0
# "done"
# ()
```

Trade-offs against the plain `--no-gc` output, and current limits:

- A component needs a component-model-capable host; the raw core module runs on
  **any** WebAssembly engine through the plain embedding API. Both outputs stay
  available — pick per host, and note the component is *not* the default for
  `--no-gc`. (Without `--component`, a `:string` crosses as the manual
  `(ptr,len)` core ABI instead.)
- The component is a pure reactor: there is no `wasi:cli/run` entry (nothing
  runs at the top level). Printing inside an export works through the
  micro-adapter above; every other I/O stays outside the `--no-gc` subset as
  usual. `:async t` is rejected — a printing program's exports are lifted async
  automatically, and there is nothing else an export could suspend on.
- The export name must be a lower-kebab-case component-model name; for a Lisp
  name outside that grammar the compiler asks you to rename it with `:as`.
- `--optimize` composes: the core module is tree-shaken before the wrap.
- [`--emit-wit`](#emitting-the-wit-world---emit-wit) composes too, and writes a tiny
  import-free world of just the typed exports (plus the `wasi:cli/stdout@0.3.0`
  import — and `async func` export signatures — when the program prints).

## Running a Component in a Browser (jco)

A component is not a wasmtime-only artifact. `jco transpile` turns one into
JavaScript, and the result runs in a browser — the exports become plain
JavaScript functions. jco camel-cases the component-model export name, so the
WIT export `count-vowels` arrives as `countVowels`. (Verified with jco 1.25.2 on
Chrome 149.)

**A `--no-gc --component` needs nothing at all.** Its world has no imports, so
jco emits one self-contained ES module — the core WASM base64-inlined inside it,
about 90 KB for the [`count-vowels`](#compact-component-output---no-gc---component)
example — with no `import` statements of its own. The page supplies no shim, no
import map and no polyfill:

```bash
rontolisp count-vowels.lisp --no-gc --component --optimize -o cv.wasm
npx @bytecodealliance/jco transpile cv.wasm -o dist
```

```html
<script type="module">
  const { countVowels } = await import('./dist/cv.js');
  console.log(countVowels('Hello, World!'));  // 3
</script>
```

**A printing `--no-gc --component` cannot run through jco yet.** Its
[print micro-adapter](#compact-component-output---no-gc---component) imports
`wasi:cli/stdout@0.3.0` and lifts every export async, so it hits the same jco
gaps as the GC component below (jco cannot call an async-lifted export, and its
`future` runtime is incomplete) — and the WASI 0.3 shim is Node-only anyway.
Keep the program print-free if the component's destination is jco or a browser;
the [plain module path](#appendix-calling-a-module-from-javascript) with a
hand-written import object is unaffected.

**A wasm-GC `--component` loads and computes, but cannot print there yet.** Chrome
supports wasm-GC, JSPI and the canonical ABI, and the component's synchronous
exports return correct values. Two gaps are in the way of the rest, both on the
JavaScript side (wasmtime runs all of it):

- The WASI 0.3 imports it needs have no browser implementation:
  `@bytecodealliance/preview3-shim` declares only a `node` condition in its
  package `exports` and pulls in `node:worker_threads`, `node:net`, `node:http`,
  ... A page must hand-write a stand-in for the nine members jco destructures at
  module top level — `environment.getEnvironment`, `stdout.writeViaStream`,
  `stderr.writeViaStream`, `stdin.readViaStream`, `monotonicClock.now`,
  `systemClock.now`, `preopens.getDirectories`, `types.Descriptor`,
  `random.getRandomU64` — which for a pure-compute export only have to exist.
- Printing then fails inside jco's own generated code, which *references*
  `FutureReadableEnd` / `FutureWritableEnd` / `FutureEnd` but defines none of
  them (`ReferenceError: FutureReadableEnd is not defined`). It is reached
  through `wasi:cli/stdout`'s `write-via-stream`, whose WIT result is a `future`.
  Separately, jco cannot yet *call* an async export (its 0.3 async ABI gap
  again), which is what an
  [`:async t`](#component-model-function-exports-wasm-export) I/O export is.

Node is the weaker host here: Node 22 has no JSPI (`WebAssembly.Suspending is
not a constructor`), so it cannot even instantiate a transpiled GC component,
while Chrome can.

## Cross-Cutting Flags

### Optimize (Tree Shaking)

By default a compiled module embeds the **entire** runtime (printer, rational,
string, reader and `eval` helpers, the WASI import slots, …) regardless of what
the program actually uses, because function indices are held fixed. Add
`--optimize` to drop every function unreachable from the module's roots (its
exports and the `_start`/`_initialize` entry) and renumber the survivors.
Unused WASI imports are removed too, so a pure-compute reactor module shrinks
to a handful of functions:

```bash
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~2 KB module
```

For the `fact` example the module drops from ~100 KB to under 2 KB.
`--optimize` is opt-in and behavior-preserving: it walks the call graph from
the actual `call` instructions, so anything reachable (including code an
embedded `eval`/`load` dispatches to) is kept. On the **GC `--component`** path
it is a no-op (the WASI 0.3 adapter relies on the core's fixed import/index
layout, so the component is emitted unchanged); under
[`--no-gc --component`](#compact-component-output---no-gc---component) it works
— the core module is shaken before the wrap. The same flag also
dead-code-eliminates the [JVM output](jvm.md).

Independently of `--optimize` (and on every output mode, `--component`
included), compilation always tree-shakes the bundled Lisp-source libraries
(`linalg:`, `vec:`, JSON, URL, `equalp`/`string<`): a library function your
program never mentions -- by name anywhere in the source, including quoted
symbols and string literals -- is not compiled into the module. The one
consequence: a library function whose name is only assembled at runtime from
computed strings and called through `eval`/`apply` signals the usual
"undefined function" error. Compile with `--no-prune` (or `--dynamic`) to keep
every library definition in that case.

### SIMD Acceleration (`--simd`)

`--simd` is the one acceleration switch shared by every backend: it lowers the
vectorizable [`vec:` and `linalg:` kernels](../guides/simd-acceleration.md) to
real vector instructions. On WASM it is orthogonal to the value model:

- **wasm-GC + `--simd`** lowers the kernels to native fixed-width SIMD
  (`f64x2`/`f32x4`) over GC-managed lane-group arrays — packed float arrays
  stay ordinary GC objects, and memory behaves exactly as without the flag.
  Composes with `--component` and `--optimize`; run as usual with
  `wasmtime run -W gc` (wasmtime enables the SIMD proposal by default).
- **`--no-gc` + `--simd`** lowers the same kernels to `v128` over the packed
  linear-memory blocks. Without `--simd`, `--no-gc` emits plain scalar loops
  instead — a v128-free MVP module that also runs on a runtime lacking the
  SIMD proposal.

The full story — which kernels vectorize, precision rules for single-float
reductions, measured effects, and the `linalg` interception — lives in the
[SIMD acceleration guide](../guides/simd-acceleration.md).

## Appendix: Calling a Module from JavaScript

A reactor module (`--no-wasi` or `--no-gc`) imports nothing, so the whole host
side is "instantiate, then call the exports" — and it is the same code in Node
and the browser. Here is a complete, copy-paste example end to end. Start with
a small kit of three exports:

```lisp
;; mathkit.lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
(defun area (r) (* 3.141592653589793 r r))
(defun in-range (x lo hi) (if (< x lo) nil (if (> x hi) nil t)))
(rontolisp:wasm-export 'fact     :params '(:int)           :returns :int)
(rontolisp:wasm-export 'area     :params '(:float)         :returns :float)
(rontolisp:wasm-export 'in-range :params '(:int :int :int) :returns :bool)
```

Compile it with `--no-gc` (runs on any engine) and `--optimize` (drops
everything unreachable from the exports — here the whole module is ~200 bytes):

```bash
rontolisp mathkit.lisp --no-gc --optimize -o mathkit.wasm
```

On Node 18+, save this as `run.mjs` and run `node run.mjs`:

```js
import { readFile } from 'node:fs/promises';

// Node reads the .wasm from disk. In a browser, use the streaming fetch shown below.
const bytes = await readFile(new URL('./mathkit.wasm', import.meta.url));
const { instance } = await WebAssembly.instantiate(bytes);   // no import object

const ex = instance.exports;
console.log(ex.fact(10));                         // 3628800
console.log(ex.area(2));                          // 12.566370614359172
console.log(Boolean(ex['in-range'](5, 0, 10)));   // true   (:bool crosses as 0 / 1)
console.log(Boolean(ex['in-range'](42, 0, 10)));  // false
```

```
3628800
12.566370614359172
true
false
```

The browser differs only in how the bytes are loaded — `instantiateStreaming`
takes a `fetch` directly — so a whole page is:

```html
<!doctype html>
<script type="module">
  const { instance } = await WebAssembly.instantiateStreaming(fetch('./mathkit.wasm'));
  const ex = instance.exports;
  document.body.textContent = `fact(10) = ${ex.fact(10)}, area(2) = ${ex.area(2)}`;
</script>
```

A few boundary details worth knowing:

- A hyphenated Lisp name such as `in-range` is not a valid JavaScript
  identifier, so reach it with bracket access: `ex['in-range'](...)`.
- `:int`/`:float` arrive as plain JS numbers; `:bool` crosses as an `i32`
  (`0`/`1`), so wrap it in `Boolean(...)` for a real JS boolean.
- A **`--no-gc`** module runs on **any** WebAssembly engine; a GC **`--no-wasi`**
  module needs a wasm-GC-capable one (Node 22+, current browsers). The
  JavaScript above is byte-for-byte identical for both — swap the compile flag
  and nothing else changes.

### Passing strings (`:string`)

The scalar example above needs no memory because `:int`/`:float`/`:bool` cross
the boundary as plain numbers. A `:string` instead passes a `(ptr, len)` pair
through the module's exported `memory`: the host writes the argument bytes into
memory (at an offset reserved by the exported `__ronto_alloc(size)` bump
allocator), passes `(ptr, len)`, then decodes the `(ptr, len)` the export
returns.

`:string` works under `--no-gc`, so the module still runs on **any** engine —
as long as the function stays within the non-GC string subset (see the
[eligible subset](#eligible-subset) above). A greeting builder is enough to
show the protocol:

```lisp
;; greetkit.lisp
(defun greet (name) (concatenate 'string "Hello, " name "!"))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greetkit.lisp --no-gc --optimize -o greetkit.wasm
```

```js
import { readFile } from 'node:fs/promises';

const bytes = await readFile(new URL('./greetkit.wasm', import.meta.url));
const { instance } = await WebAssembly.instantiate(bytes);   // no import object
const ex = instance.exports;
const enc = new TextEncoder(), dec = new TextDecoder();

// Copy a JS string into linear memory; return its (ptr, len).
function write(str) {
  const b = enc.encode(str);
  const ptr = ex.__ronto_alloc(b.length);
  new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
  return [ptr, b.length];
}
// Decode a (ptr, len) result. Re-read ex.memory.buffer AFTER the call: a call may grow
// memory, which detaches the previous ArrayBuffer.
const read = (ptr, len) => dec.decode(new Uint8Array(ex.memory.buffer, ptr, len));

console.log(read(...ex.greet(...write('rontolisp'))));     // Hello, rontolisp!
```

```
Hello, rontolisp!
```

With [`--no-gc --component`](#compact-component-output---no-gc---component) the
same `:string` export instead crosses as a typed component-model `string`, and
all of the host-side glue above disappears (the canonical ABI does the copying,
and a post-return function keeps the heap flat).

Richer string functions (`string-upcase`, `subseq`, `string=`, …) are outside
the non-GC subset; using one means compiling for the wasm-GC backend
(`--no-wasi`) instead — the boundary protocol is identical, only the engine
must be wasm-GC capable. The `:s-expr` example below shows that path.

### Passing lists (`:s-expr`)

A `:s-expr` carries **any** Lisp value as s-expression *text*: the module
parses the input with its embedded reader and prints the result back, over the
same `(ptr, len)` / `__ronto_alloc` protocol. That reader/printer/cons
machinery is **wasm-GC only**, so `:s-expr` (and the richer string functions
above) need `--no-wasi` and a wasm-GC-capable engine (Node 22+, a current
browser):

```lisp
;; textkit.lisp
(defun shout (s) (string-upcase s))
(defun rev (lst) (reverse lst))
(rontolisp:wasm-export 'shout :params '(:string) :returns :string)   ; "hello" -> "HELLO"
(rontolisp:wasm-export 'rev   :params '(:s-expr)  :returns :s-expr)    ; a list, reversed
```

```bash
rontolisp textkit.lisp --no-wasi --optimize -o textkit.wasm
```

```js
// Same instantiate + write/read helper as above (textkit.wasm needs a wasm-GC engine).
console.log(read(...ex.shout(...write('hello'))));         // HELLO
console.log(read(...ex.rev(...write('("a" "b" "c")'))));   // ("c" "b" "a")
```

```
HELLO
("c" "b" "a")
```

In the browser only the loading line changes
(`WebAssembly.instantiateStreaming(fetch(...))`); the
`write`/`read`/`memory`/`__ronto_alloc` logic is identical. A function that
returns a multi-value `(ptr, len)` shows up in JS as a two-element array, hence
`read(...ex.shout(...))`.
