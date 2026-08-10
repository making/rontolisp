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
stays fixed (no other codegen changes). What those stubs do follows one rule:
**a stub answers when the answer is true of the module, and refuses when
answering would mean inventing a value you could not tell from a real one** —
though a value the *host* hands in is not an invention, which is how the clock
and randomness are served. A reactor really has no output destination, no
environment variables and no files, so those are answered; nothing about it
makes a byte of input true, so that is not.

| what your program does | on `--no-wasi` |
| --- | --- |
| `print`, `format t`, writes to `*error-output*` | **discarded** (a sink); the call returns normally |
| `(uiop:getenv "X")` | `nil` — the environment is empty |
| `probe-file`, `directory`, `load` | nothing is found (`nil`, or a catchable error) |
| `with-open-file`, `open` | **signals** a catchable error naming WASI |
| `(random n)`, `(random 1.0)` | works — a built-in generator, or the host's with `--host-random` |
| `rontolisp:random-bytes` | **signals**, unless `--host-random` supplies real entropy |
| `get-universal-time` and the other clocks | the time the host set through `__ronto_set_time`; **signals** until it does |
| `(sleep n)` | **signals** — nothing here can make an interval elapse |
| `read`, `read-line`, `read-char` (standard input) | **traps** |

Everything that signals does so at CALL time, so an `ignore-errors` or
`handler-case` around it keeps working and a library whose file-loading or
clock-probing branch is dead code still compiles and runs. Only standard input
traps, and a trap is not catchable — that is the one place where a `--no-wasi`
module still dies rather than reports.

Output being a sink is what lets a library that logs while it loads be
quickloaded into a reactor at all — the alternative was killing the instance for
a log line. If you need the text, return it from the export instead.

The clock and randomness are the two services with a choice to make, because
both are values the module cannot produce for itself. A core module exports one
hook for each — `__ronto_set_time` (nanoseconds since the Unix epoch) and
`__ronto_seed_random` — to be called **before `_initialize`**, which is what
makes a library that timestamps or draws while it *loads* loadable at all; and
`--host-random` routes `random` at a host import instead. Unseeded, the
generator repeats one sequence; unset, the clock signals rather than report
1970, and it holds the value you wrote until you write another (so `(sleep n)`
signals here — nothing can make an interval elapse). A **reactor component** has
neither hook: its top level runs at instantiation, so there is no window in
which a host could go first. All of it, with the JavaScript, is in the
[clock and randomness guide](clock-and-random.md).

Combined with `--component`, the same contract produces a **reactor
component** — a component that imports nothing, whose top-level forms run at
instantiation — see
[the component guide](wasm-component.md#reactor-components---component---no-wasi).

A `--no-wasi` compile also reads the source with the `:rontolisp-reactor`
feature active, which is how a `clack:clackup ... :server :rontolisp` program
becomes a **served** reactor here: the handler backend stores the application
and the compiler synthesizes a `handle-request` export the host calls per
request — see [the Clack guide](clack.md#a-host-that-calls-you-the-reactor-build).

Because the module is a reactor (not a WASI command), its top-level
initializer is exported as **`_initialize`** rather than `_start`. A host
should call `_initialize` once after instantiation to run top-level forms
(`defvar`/`defparameter`/`setq` globals that an exported function reads);
pure-compute reactors that hold no top-level state can skip it.

### What the build tells you before you run it

A refusal reached from a **top-level form** is the exception to everything
above: there is no caller to catch the condition, and the message goes to the
output sink — so the instance dies inside `_initialize` with a bare
`RuntimeError: unreachable` naming nobody. Which primitives your load path can
reach is something the build already knows, so it says so — one line per
primitive, with the call chain that got there:

```console
$ rontolisp app.lisp --no-wasi -o app.wasm
.../session/state/cookie.lisp:25:12: warning: GET-UNIVERSAL-TIME is reachable from a top-level
form of this --no-wasi module (the top-level (DEFSTRUCT COOKIE-STATE)), so it can run while the
module LOADS -- where nothing catches it and the host sees only RuntimeError: unreachable. The
module imports no clock: its time is whatever the host writes through the exported
__ronto_set_time hook (nanoseconds since the Unix epoch), so call that BEFORE _initialize --
until something does, reading it signals
```

The clock is the line worth having: a program that reads it while loading *is*
loadable — on a host that sets it first — so this is a **host obligation**, not
a refusal, and nothing but the build can tell you about it in advance. Entropy
reads the same way and names `--host-random`.

A primitive only an **export** can reach stays quiet, because that one is an
ordinary call-time condition your caller can catch. Reachability is static, but
it is not blind to the **arguments**: a call carries what the site says about
each one — `#'app`, a literal, a `(defvar *app* (make-instance 'ningle:app))` —
so a `typecase` branch whose type that value cannot have is not on the load
path. That is what keeps every `clack` program quiet about `clackup`'s
`(clackup "app.lisp")` file branch, which a reactor never takes. Where the call
site says nothing, nothing is ruled out and the line stands. A refusal wrapped
in `handler-case` or `ignore-errors` is not reported at all; the program
already handles it.
