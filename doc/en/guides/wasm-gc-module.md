# wasm-GC Core Module (Default Output)

The default output — no flags beyond `-o file.wasm` — is a **WASI Preview 1
core module** over the wasm-GC value model:

- **wasm-GC** — Integers are represented as `i31ref` (a value past the fixnum
  range is boxed as a signed 64-bit struct, and past that as a limb-based big
  integer, keeping arithmetic exact at any magnitude).
  Floating-point numbers are boxed in a `float_struct { f64 }`. All values on the stack are typed as
  `(ref eq)`. This is what supports the full language (cons cells, symbols,
  closures, hash tables, `eval`, ...), and why the module needs a wasm-GC
  capable runtime such as wasmtime 14+ (`-W gc`), Node 22+, or a current
  browser.
- **WASI Preview 1** — the module imports the eight `wasi_snapshot_preview1`
  functions (`fd_write` for stdout, `random_get`, clocks, environment, ...)
  and exposes the `_start` entry point, so `wasmtime run` executes the
  program's top level like a command.

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp -o hello.wasm
wasmtime run -W gc hello.wasm
# 3
```

An exported function is a **raw core function**: scalars
(`:int`/`:float`/`:bool`) cross as plain numbers, so `wasmtime --invoke` and
`instance.exports.fact(5)` work directly. The memory-backed `:string` and
`:s-expr` designators pass a `(ptr, len)` pair through the module's exported
`memory`, together with a `__ronto_alloc(size)` bump allocator the host uses
to stage argument bytes — that protocol needs a host that can read and write
memory (JavaScript, not `wasmtime --invoke`), and is walked through end to
end in the [browser guide's reactor section](wasm-browser.md#reactor-modules-by-hand).
Instantiating the module still needs the eight WASI imports satisfied;
`wasmtime run` provides them automatically, a browser host can supply no-op
stubs for a pure-compute function, or add [`--no-wasi`](#no-wasi-reactor-mode)
to drop them entirely.

For the full picture of how `wasm-export` behaves in this shape (types
carried, `:as` renaming, arity match, void returns), see the
[host-boundary guide](wasm-host-boundary.md).

## Value-Model Behavior Notes

Two behavioral notes on the wasm-GC value model:

- **Parameter limit.** A function (`defun` or `lambda`) may take at most
  **seven parameters** (the interpreter and JVM backends have no such limit).
  A fixed-arity `defun` past the limit is bundled automatically: the compiler
  keeps the first six parameters, packs the rest into a list, and rewrites
  every direct call site to match — so wide library signatures compile
  unchanged. Taking such a function's value with `#'name`/`symbol-function`
  is a compile error (only direct calls know the bundled shape), and a
  `lambda` or variadic function past the limit still errors — bundle those
  arguments into a list yourself. The rest list of a variadic function
  counts as one parameter, so a `&rest` function may declare at most six
  required parameters while accepting any number of arguments at a direct
  call site.
- **Float printing shape.** Floats of every magnitude print on WASM: the
  integer part is exact up to 2⁶³, larger values fall back to an approximate
  exponent form (`1.0E19`), and `Infinity`, `-Infinity` and `NaN` print as
  those words, like the other backends. One shape difference remains: from
  10⁷ up to 2⁶³ WASM prints all the digits (`1500000000000.0`) where the
  interpreter and the JVM use exponent notation (`1.5E12`);
  `rontolisp:json-stringify` inherits that shape difference.

## Reclaiming the Host's Buffer (the Arena API)

The engine collects everything the Lisp side allocates — cons cells,
closures, strings — so a wasm-GC module needs no memory discipline *inside*.
The one thing the engine cannot see is the buffer the **host** wrote the
argument bytes into: that is linear memory, an opaque byte array it never
traces, handed out by the `__ronto_alloc` bump allocator, which never frees.
A resident host that allocates a fresh input buffer per call therefore grows
linear memory without bound.

So a module that exports its `memory` also exports a matched pair over the
same heap pointer:

| export | signature | meaning |
| --- | --- | --- |
| `__ronto_alloc_mark` | `() -> i32` | snapshot the current bump-heap top |
| `__ronto_alloc_reset` | `(i32 mark) -> ()` | restore the top to a saved mark |

Snapshot **before** allocating the input, restore **after** reading the
result, and a resident instance stays flat no matter how many times it is
called or how long each input is:

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

One backend-specific guard: on the GC backend the same heap pointer also
holds the interned-symbol byte pool (a symbol's identity *is* its offset
there), so `__ronto_alloc_reset` never pops below that pool's high-water
mark. A call that interns a new symbol (`read`, `intern`, `gensym`)
therefore keeps its input buffer; every other call pops all the way back.
Nothing to do host-side.

The bracket is the same one the
[`count-vowels` example](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)
walks through on `--no-gc` (from Node and from
[Endive](https://endive.run)) — the boundary protocol does not change with
the backend, only what you may write inside the function does. Under
[`--component`](wasm-component.md) there is no arena API and nothing to
bracket: the canonical ABI's `post-return` frees the argument strings for
you.

## No-WASI (Reactor) Mode

Add `--no-wasi` to emit a Preview 1 module that imports **no** WASI
functions, so a host can instantiate it with no import object at all — a
"reactor"/library module whose only surface is the exported Lisp functions:

```bash
rontolisp fact.lisp --no-wasi -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120
```

A reactor is just as easy to drive from JavaScript: there is **no import
object**, so the host side is just "instantiate, then call the exports"
(`WebAssembly.instantiate(bytes).then(({ instance }) => instance.exports.fact(5))`).
A complete, copy-paste runnable Node + browser example is in the
[browser guide's reactor section](wasm-browser.md#reactor-modules-by-hand).

The WASI import slots are filled with internal stubs so every function index
stays fixed (no other codegen changes). This mode is for **pure-compute**
exports: any INPUT, file, time or `random` call (`read`/`open`/`uiop:getenv`/
`get-universal-time`/`random`, including from a top-level form) hits a stub and
**traps**, because a stub could only answer by inventing data.

Output is the exception. `print`, `format t` and everything else that writes to
standard output or standard error go to a **sink**: a reactor host hands the
module no file descriptors, so the bytes are discarded and the call returns
normally. That is what lets a library that logs while it loads be quickloaded
into a reactor at all — the alternative was killing the instance for a log line.
If you need the text, return it from the export instead.

It is Preview 1 only — `--no-wasi` is ignored under `--component`.

Because the module is a reactor (not a WASI command), its top-level
initializer is exported as **`_initialize`** rather than `_start`. A host
should call `_initialize` once after instantiation to run top-level forms
(`defvar`/`defparameter`/`setq` globals that an exported function reads);
pure-compute reactors that hold no top-level state can skip it.
