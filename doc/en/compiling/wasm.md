# Compile to WASM

Give `rontolisp` an output path ending in `.wasm` with `-o`, and it compiles the
source to a WebAssembly binary instead of interpreting it. As with the JVM
backend, the output extension selects the target, and the binary is emitted by
hand without a third-party assembler. Run the result on any wasm-GC capable
runtime:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
```

```
3
```

The generated `.wasm` binary uses:

- **wasm-GC** -- Integers are represented as `i31ref`. Floating-point numbers are boxed in a `float_struct { f64 }`. All values on the stack are typed as `(ref eq)`.
- **WASI Preview 1** -- `fd_write` for stdout output.

Requires a wasm-GC capable runtime such as wasmtime 14+.

On the WASM backend a function (`defun` or `lambda`) may take at most **seven
parameters**; a larger arity is a compile error (the interpreter and JVM backends have no
such limit). Bundle the extra arguments into a list to stay within it.

A float with magnitude 2³¹ or larger computes and compares correctly but
cannot be printed (`print`/`princ-to-string` trap with an integer overflow);
this also limits `rontolisp:json-stringify` for such values.

The default output is a Preview 1 core module that exposes only the WASI `_start` entry
point. The sections below cover the WASM-specific options: marking individual functions as
host-callable (`rontolisp:wasm-export`), calling host functions from Lisp
(`rontolisp:wasm-import`), dropping the WASI imports for a reactor/library
module (`--no-wasi`), shrinking the module by tree shaking (`--optimize`), emitting a plain
non-wasm-GC module that runs on any engine (`--no-gc`), and emitting a WASI 0.3 component
(`--component`).

## Exporting Lisp Functions

By default a compiled module only exposes the WASI `_start` entry point. To make an
individual Lisp function callable directly from a host (`wasmtime --invoke`, JavaScript,
or another module), mark it with the `rontolisp:wasm-export` directive,
declaring the WASM-boundary types of its parameters and result:

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

The type designators and their boundary representations are:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | 31-bit signed range (the internal `i31ref`) |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory |
| `:s-expr` | `(ptr, len)` | s-expression text in linear memory (any value except a function) |

The default wasm-GC output supports all five designators (the `:int` range above is the
internal `i31ref`). The non-GC backend ([`--no-gc`](#non-gc-output---no-gc)) supports
`:int`/`:float`/`:bool`/`:string` — with a wider internal integer range — but not
`:s-expr`, which needs the cons/reader/printer runtime.

A side-effecting function can declare a **void** result by omitting `:returns` (or giving it
as `nil`, `'()` or `:void`); the wrapper then discards the Lisp return value and has no WASM
result. Likewise an omitted, `nil` or `'()` `:params` means no arguments.

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

`:as` renames the export — useful when the host-facing API wants a name that is not an
idiomatic Lisp symbol, e.g. camelCase for JavaScript:

```lisp
(defun draw-board (w h) (* w h))
(rontolisp:wasm-export 'draw-board :as "drawBoard" :params '(:int :int) :returns :int)
```

Functions whose parameters and result are all scalar (`:int`/`:float`/`:bool`) get a plain
numeric signature, so they can be called straight from `wasmtime --invoke`. The
memory-backed `:string` and `:s-expr` designators pass a pointer/length pair through the
module's exported `memory`, so they need a host that can read and write it (e.g.
JavaScript). For input, the module also exports a bump allocator `__ronto_alloc(size)`
that returns a scratch offset to write the argument bytes into:

```js
const { instance } = await WebAssembly.instantiate(bytes, { wasi_snapshot_preview1: stubs });
const ex = instance.exports, mem = ex.memory;
const b = new TextEncoder().encode('("a" "b" "c")');
const ptr = ex.__ronto_alloc(b.length);
new Uint8Array(mem.buffer, ptr, b.length).set(b);
const [rptr, rlen] = ex.rev(ptr, b.length);          // (rontolisp:wasm-export 'rev :params '(:s-expr) :returns :s-expr)
new TextDecoder().decode(new Uint8Array(mem.buffer, rptr, rlen)); // => ("c" "b" "a")
```

Limitations:

- The directive applies to the Preview 1 core module only; under `--component` it is a
  no-op (no wrapper is emitted). On the interpreter and JVM backends it is also a no-op
  (it just returns the named symbol), so the same source runs on every backend.
- Only a top-level `defun` can be exported, the declared parameter count must match its
  arity, and functions that take or return function values are out of scope.
- The exported name defaults to the bare Lisp name (`fact`) and can be renamed with
  `:as`; how arguments are written depends on the host
  (`wasmtime --invoke fact module.wasm 5`, `instance.exports.fact(5)`, ...).
- By default, instantiating the module still needs the eight `wasi_snapshot_preview1`
  imports satisfied; `wasmtime run` provides them automatically, and a browser host can
  supply no-op stubs for a pure-compute function. Add `--no-wasi`
  ([below](#no-wasi-reactor-mode)) to drop them.

## Importing Host Functions

`rontolisp:wasm-import` is the reverse direction: it declares a function the **host**
provides and makes it callable from Lisp under the given name exactly like a top-level
`defun` — including `#'name`, `funcall`, `mapcar` and `eval`. `:from` names the import
module (default `"env"`), `:as` names the field inside it (default: the Lisp name), and
the type designators are the same table as above:

```lisp
; main.lisp
(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
(defun add10 (n) (add n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

In wasmtime, satisfy the imports by preloading another module that exports them — here a
host module that is itself written in Lisp, exporting its function under the `:as` alias
`add`:

```console
$ cat host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
$ rontolisp host.lisp -o host.wasm --no-wasi
$ rontolisp main.lisp -o main.wasm --no-wasi
$ wasmtime run -W gc --preload host=host.wasm --invoke add10 main.wasm 32
42
```

In a browser (or Node) the import object *is* the module table — one key per `:from`
name, one property per `:as` name. This is also the escape hatch for anything the WASM
backend does not provide; for example it has no trigonometric built-ins, so borrow
JavaScript's:

```lisp
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

```js
const imports = { math: { sin: Math.sin, cos: Math.cos } };
const { instance } = await WebAssembly.instantiate(bytes, imports);
```

The [WebGL triangle example](https://github.com/making/rontolisp/tree/develop/examples/webgl-triangle)
is the hello world of this pattern: ten imported functions, no exports, and a colored
triangle drawn entirely from Lisp. The
[WebGL cube example](https://github.com/making/rontolisp/tree/develop/examples/webgl-cube)
adds 3D: the perspective and rotation matrices are computed in Lisp every frame. The
[WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/webgl-galaxy)
is the same idea grown into a complete browser program: the entire WebGL pipeline is driven from
Lisp -- the GLSL shaders live in the Lisp source, and Lisp compiles, links, buffers and
issues every draw call through 34 imported host functions, while JavaScript supplies
only one-line bindings over a handle table.

Boundary details beyond the scalar types:

- A `:string`/`:s-expr` **argument** reaches the host as a `(ptr, len)` pair into the
  module's exported `memory` (an `:s-expr` argument is printed to readable text first).
- A `:string` **result** must be written into linear memory by the host — reserve the
  buffer with the exported `__ronto_alloc`, then return the `(ptr, len)` pair (a
  two-element array in JavaScript).
- An `:s-expr` **result** is parsed with the embedded reader, so the host can hand back
  a whole list structure as text.

Limitations:

- Default (wasm-GC) Preview 1 output only: `--component` and `--no-gc` reject the
  directive with an error.
- On the interpreter and JVM backends the directive defines a stub that signals an
  error when called, so a shared source still loads everywhere, but actually calling an
  import needs the WASM host.
- Imported functions have the same 7-parameter arity limit as other functions.
- Instantiating the module requires every declared import to be provided: `wasmtime
  run` needs a `--preload <module>=<file>.wasm` per import module name, and a
  JavaScript host passes an import object.

## No-WASI (Reactor) Mode

Add `--no-wasi` to emit a Preview 1 module that imports **no** WASI functions, so a host
can instantiate it with no import object at all — a "reactor"/library module whose only
surface is the exported Lisp functions:

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

A reactor is just as easy to drive from JavaScript: there is **no import object**, so the
host side is just "instantiate, then call the exports"
(`WebAssembly.instantiate(bytes).then(({ instance }) => instance.exports.fact(5))`). A
complete, copy-paste runnable Node + browser example is in the
[appendix](#appendix-calling-a-module-from-javascript) at the end of this page.

The eight WASI import slots are filled with internal trap stubs so every function index
stays fixed (no other codegen changes). This mode is for **pure-compute** exports only:
any I/O (`print`/`read`/`open`/`getenv`/time/`random`, including a top-level form that
prints) hits a stub and **traps**. It is Preview 1 only — `--no-wasi` is ignored under
`--component`.

Because the module is a reactor (not a WASI command), its top-level initializer is
exported as **`_initialize`** rather than `_start`. A host should call `_initialize` once
after instantiation to run top-level forms (`defvar`/`defparameter`/`setq` globals that an
exported function reads); pure-compute reactors that hold no top-level state can skip it.

## Optimize (Tree Shaking)

By default a compiled module embeds the **entire** runtime (printer, rational, string,
reader and `eval` helpers, the WASI import slots, …) regardless of what the program
actually uses, because function indices are held fixed. Add `--optimize` to drop every
function unreachable from the module's roots (its exports and the `_start`/`_initialize`
entry) and renumber the survivors. Unused WASI imports are removed too, so a pure-compute reactor module shrinks to
a handful of functions:

```bash
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~1 KB module
```

For the `fact` example above the module drops from ~26 KB to ~1.3 KB. `--optimize`
is opt-in and behavior-preserving: it walks the call graph from the actual `call`
instructions, so anything reachable (including code an embedded `eval`/`load` dispatches
to) is kept. It has **no effect** under `--component` (the WASI 0.3 adapter relies on
the core's fixed import/index layout). The same flag also dead-code-eliminates the
[JVM output](jvm.md).

## Non-GC Output (`--no-gc`)

The default output — even the optimized reactor above — still needs a **wasm-GC
capable** runtime, because every value is a GC heap type (`i31ref`, the float struct,
`(ref eq)`). Add `--no-gc` to emit a plain **MVP** module instead: no rec group, no
`struct`/`array`/`i31` type, no `eqref` and no import (a plain linear memory is added only
when the program uses strings — see [below](#strings)). It instantiates with no import
object and runs on any MVP-class runtime with **no `-W gc`**:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, no -W gc needed
```

It achieves this by lowering each value directly onto an unboxed wasm scalar, plus a small
linear-memory representation for strings — so the eligible subset is a restriction of the
language, not a different one.

### Eligible subset

A function is eligible only if its **entire transitive call graph** stays inside this
subset:

- numbers and booleans: arithmetic (`+ - * / mod rem 1+ 1- abs min max sqrt`), the integer
  bitwise operators (`logand logior logxor lognot ash`), comparison and predicates
  (`= < <= > >= not zerop plusp minusp evenp oddp`);
- control and binding: `if`/`when`/`unless`/`cond`/`progn`/`let`/`let*`, recursion and
  calls to other eligible functions;
- iteration and local mutation: `dotimes`/`do`/`do*` and the underlying
  `while`/`setq`/`return`, with a let/`do`-bound variable freely reassigned; `loop` is
  eligible only for its non-consing clauses (numeric `for`, `sum`/`count`/`maximize`/
  `minimize`, `repeat`/`while`/`until`/`do`/`return`) — its `collect`/`append`/`nconc`
  and `for ... in`/`on` clauses allocate lists and are not;
- float/int conversions: `float truncate floor ceiling round`;
- strings: string literals and `(concatenate 'string ...)`.

Anything else that would allocate a heap object (cons/list, characters, symbols, vectors,
hash tables, `eval`/`apply`, I/O, `dolist`/list iteration, a free variable or assignment to
a global) makes the function ineligible. Rather than miscompile silently, that is a
**compile error** naming the offending operation, so the boundary stays explicit.

The supported boundary designators are `:int`, `:float`, `:bool`, `:string` (and
`:void`/omitted). `:s-expr` is **not** supported — it would need the cons/reader/printer
runtime that this backend deliberately omits.

### Numeric model

Each value's wasm type is chosen by static type inference: integers use `i64`, floats use
`f64`. Types are inferred with a fixpoint over the call graph seeded by the export boundary
designators, and where an integer and a float meet (e.g. `(* 3.14 n)`) the integer is
promoted to `f64`. Using `i64` makes integer arithmetic exact to 2^63 — far wider than both
the GC backend's `i31` fixnums and what an all-`f64` lowering (exact only to 2^53) could
offer; for example `a*a - (a-1)*(a+1)` stays exactly `1` even when the intermediates exceed
2^53.

Inference also widens automatically: a let/`do`-bound variable takes the join of its
initializer and every value assigned to it, so an integer accumulator summed with floats
becomes an `f64`:

```lisp
(defun sum-squares (n)        ; sum of i*i for i in 0..n-1, as a float
  (let ((acc 0))              ; acc starts as an integer 0 ...
    (dotimes (i n)
      (setq acc (+ acc (* (float i) (float i)))))  ; ... and widens to f64 here
    acc))
(sum-squares 5)  ; => 30.0
```

Under `--no-gc` this infers `acc` (and the return value) as `f64` while the loop counter
`i` stays `i64`.

There is no rational type, so two things differ from full Common Lisp and from the GC
backend: `/` is floating-point division (no `1/3` ratios), and a value is false in a
boolean context exactly when it is zero (Common Lisp treats only `nil` as false). The
**boundary** designators stay host-width — `:int`/`:bool` cross as a 32-bit `i32` (as in
the GC backend), so a returned value outside the 32-bit range wraps; the wide `i64` range
applies only to the internal computation. For the numeric kernels this mode targets
(factorials, math/finance functions, validators) the results match the interpreter and the
GC backend.

### Strings

A string is an `i32` pointer to a `[length][bytes]` header in linear memory, and
`(concatenate 'string ...)` bump-allocates a fresh buffer — so building up a string is just
an accumulator loop:

```lisp
(defun stars (n)               ; an n-character run of '*'
  (let ((out ""))
    (dotimes (k n)
      (setq out (concatenate 'string out "*")))
    out))
(stars 5)  ; => "*****"
```

A module that uses strings gains a (growable) linear memory, and exports that `memory` plus
a `__ronto_alloc(size)` bump allocator alongside your functions. A `:string` parameter
arrives as a `(ptr, len)` pair the host writes into memory, and a `:string` result is
returned the same way — so a string-valued export needs a host that can read/write the
exported memory (JavaScript, a small Node script, the browser playground) rather than just
`wasmtime --invoke`. The [appendix](#passing-strings-string) walks through the JS side.

This is what lets the ASCII-art Mandelbrot renderer run with no wasm-GC:
[`examples/mandelbrot-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/mandelbrot-nogc.lisp)
keeps the floating-point escape-time loop but returns the rendered grid as one string
instead of printing it:

```console
$ rontolisp examples/mandelbrot-nogc.lisp --no-gc --optimize -o mandelbrot.wasm
$ node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("mandelbrot.wasm"), {})).instance.exports;
  const [p, n] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
  process.stdout.write(Buffer.from(new Uint8Array(ex.memory.buffer, p, n)).toString());
})()'
```

### Composition

`--no-gc` is a pure-compute reactor: like `--no-wasi` it imports nothing and exports each
`rontolisp:wasm-export` function under its name (so it cannot do I/O, and a top-level form
that prints is rejected). It composes with `--optimize`, but cannot be combined with
`--component` (that path is wasm-GC bound). Calling it from JavaScript is the same
"instantiate, then call the exports" as a GC reactor — see the
[appendix](#appendix-calling-a-module-from-javascript) — only here the module runs on
**any** WebAssembly engine, with no wasm-GC support required.

## WASI 0.3 Component

Add `--component` to emit a WASI 0.3 (Preview 3) **component** instead of a Preview 1 core module. The component prints through `wasi:cli/stdout@0.3.0`:

```bash
rontolisp hello.lisp --component -o hello.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y hello.wasm
```

```
3
```

In WASI 0.3 all byte I/O flows through the built-in component-model `stream<u8>` / `future<T>` types and the async canonical ABI. rontolisp keeps the same Preview 1 core module unchanged — it still imports the eight `wasi_snapshot_preview1` functions — and an **adapter** core module implements them over WASI 0.3 (`wasi:cli`, `wasi:filesystem`, `wasi:clocks`, `wasi:random`) using `stream.new`/`stream.read`/`stream.write` and `future.read`. The component's `wasi:cli/run@0.3.0` export (an `async func`) is lifted as a **stackful** async export, so the synchronous stream/future built-ins block cooperatively and the adapter stays straight-line code. The three `component-model-async*` flags enable those features (stackful async lift + synchronous stream/future built-ins).

The wasmtime invocation does **not** select the output kind. `wasmtime run` is wasmtime's default subcommand and auto-detects a core module vs a component, so `wasmtime run -W gc` runs the Preview 1 `hello.wasm` from the previous section just as well. Only the `--component` compile flag decides whether a Preview 1 core module or a WASI 0.3 component is produced. (The practical difference shows up on a component-only runtime, which runs the component but not the Preview 1 core module.)

The default output (without `--component`) stays a Preview 1 core module, so nothing changes for existing usage.

File I/O works in component mode too — it is implemented over `wasi:filesystem@0.3.0` (`read-via-stream` / `append-via-stream`, driven through `stream`/`future`). As in Preview 1, file access needs `--dir`:

```bash
cat > fileio.lisp <<'EOF'
(with-open-file (out "greeting.txt" :direction :output)
  (write-line "hello" out))
(with-open-file (in "greeting.txt")
  (print (read-line in)))
EOF
rontolisp fileio.lisp --component -o fileio.wasm
wasmtime run -W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y --dir . fileio.wasm
# "hello"
```

Notes and current limitations of component mode:

- Requires a runtime with WASI 0.3 component-model async support: **wasmtime 46+** (pass `-W gc=y -W component-model-async=y -W component-model-async-stackful=y -W component-model-more-async-builtins=y`).
- `print`/stdout, stdin (`read`, 0-argument `read-line`, over `wasi:cli/stdin@0.3.0`), and file I/O (`open`, `close`, `write-line`, stream `read-line`, `load`, `with-open-file`) all work. File access requires `--dir` (paths resolve against the first preopened directory).
- `random` draws real entropy from `wasi:random@0.3.0` (Preview 1 uses the host's `random_get`), so `(random N)` differs each run. `get-universal-time` / `get-internal-real-time` / `get-internal-run-time` read `wasi:clocks@0.3.0` (`system-clock`/`monotonic-clock`), and `getenv` reads `wasi:cli/environment@0.3.0`.
- Outgoing HTTP (`rontolisp:fetch` with the `rontolisp:await` / `rontolisp:then` / `rontolisp:promisep` promise operations) works in component mode, including true asynchrony: `fetch` sends the request and returns a promise (wrapping the in-flight `wasi:http` response handle) immediately, so several requests can overlap before `await` blocks on each. The promise operations themselves compile in every mode; only `fetch` is component-only. It is a **hybrid**: the base I/O stays WASI 0.3 while fetch itself imports `wasi:http@0.2` + `wasi:io@0.2` (async `wasi:http@0.3` does not exist upstream yet — see `.todo/02-upgrade-fetch-to-wasi-http-0.3.md`). Run a fetch component with `-S http=y` in addition to the async flags. Non-fetch components do not import `wasi:http`, so they do not need `-S http`.
- The compiled Lisp otherwise behaves identically to the Preview 1 output for the supported features.

## Appendix: Calling a Module from JavaScript

A reactor module (`--no-wasi` or `--no-gc`) imports nothing, so the whole host side is
"instantiate, then call the exports" — and it is the same code in Node and the browser.
Here is a complete, copy-paste example end to end. Start with a small kit of three exports:

```lisp
;; mathkit.lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
(defun area (r) (* 3.141592653589793 r r))
(defun in-range (x lo hi) (if (< x lo) nil (if (> x hi) nil t)))
(rontolisp:wasm-export 'fact     :params '(:int)           :returns :int)
(rontolisp:wasm-export 'area     :params '(:float)         :returns :float)
(rontolisp:wasm-export 'in-range :params '(:int :int :int) :returns :bool)
```

Compile it with `--no-gc` (runs on any engine) and `--optimize` (drops everything
unreachable from the exports — here the whole module is ~200 bytes):

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

The browser differs only in how the bytes are loaded — `instantiateStreaming` takes a
`fetch` directly — so a whole page is:

```html
<!doctype html>
<script type="module">
  const { instance } = await WebAssembly.instantiateStreaming(fetch('./mathkit.wasm'));
  const ex = instance.exports;
  document.body.textContent = `fact(10) = ${ex.fact(10)}, area(2) = ${ex.area(2)}`;
</script>
```

A few boundary details worth knowing:

- A hyphenated Lisp name such as `in-range` is not a valid JavaScript identifier, so reach
  it with bracket access: `ex['in-range'](...)`.
- `:int`/`:float` arrive as plain JS numbers; `:bool` crosses as an `i32` (`0`/`1`), so wrap
  it in `Boolean(...)` for a real JS boolean.
- A **`--no-gc`** module runs on **any** WebAssembly engine; a GC **`--no-wasi`** module
  needs a wasm-GC-capable one (Node 22+, current browsers). The JavaScript above is
  byte-for-byte identical for both — swap the compile flag and nothing else changes.

### Passing strings (`:string`)

The scalar example above needs no memory because `:int`/`:float`/`:bool` cross the boundary
as plain numbers. A `:string` instead passes a `(ptr, len)` pair through the module's
exported `memory`: the host writes the argument bytes into memory (at an offset reserved by
the exported `__ronto_alloc(size)` bump allocator), passes `(ptr, len)`, then decodes the
`(ptr, len)` the export returns.

`:string` works under `--no-gc`, so the module still runs on **any** engine — as long as
the function stays within the non-GC string subset (string literals and
`(concatenate 'string ...)`). A greeting builder is enough to show the protocol:

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

Richer string functions (`string-upcase`, `subseq`, `string=`, …) are outside the non-GC
subset; using one means compiling for the wasm-GC backend (`--no-wasi`) instead — the
boundary protocol is identical, only the engine must be wasm-GC capable. The `:s-expr`
example below shows that path.

### Passing lists (`:s-expr`)

A `:s-expr` carries **any** Lisp value as s-expression *text*: the module parses the input
with its embedded reader and prints the result back, over the same `(ptr, len)` /
`__ronto_alloc` protocol. That reader/printer/cons machinery is **wasm-GC only**, so
`:s-expr` (and the richer string functions above) need `--no-wasi` and a wasm-GC-capable
engine (Node 22+, a current browser):

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

In the browser only the loading line changes (`WebAssembly.instantiateStreaming(fetch(...))`);
the `write`/`read`/`memory`/`__ronto_alloc` logic is identical. A function that returns a
multi-value `(ptr, len)` shows up in JS as a two-element array, hence `read(...ex.shout(...))`.
