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

```lisp
(print (+ 1 2))
```

```
3
```

## Choosing an Output

Two independent choices determine the shape of the output:

- **Value model.** By default, values live on the WebAssembly **GC heap**
  (integers as `i31ref`, boxed as a signed 64-bit struct past the fixnum
  range and as a limb-based big integer past that, floats boxed in a struct), which supports the **full
  language** but requires a wasm-GC capable runtime (wasmtime 14+, Node 22+,
  current browsers). `--no-gc` instead lowers a **pure-compute subset** of the
  language onto unboxed `i64`/`f64` scalars and linear-memory strings — the
  result is a plain MVP module that runs on **any** WebAssembly engine and is
  orders of magnitude smaller.
- **Packaging.** By default the output is a **WASI Preview 1 core module**.
  `--component` wraps it as a **component**: on the GC path a WASI 0.3
  component with full I/O over the async canonical ABI, on the `--no-gc` path a
  compact typed reactor component that runs with no host flags at all.
  `--no-wasi` drops the WASI imports, turning either packaging into a
  pure-compute library ("reactor"): a Preview 1 module a host instantiates
  with no import object, or — with `--component` — a **reactor component
  that imports nothing** and runs its top-level forms at instantiation.

Crossing the two axes gives the six shapes:

| Output shape | Flags | Language | Runs on | Details |
| --- | --- | --- | --- | --- |
| WASI command module | (none) | full | wasm-GC engine with WASI Preview 1 (`wasmtime run -W gc`) | [wasm-GC core module](../guides/wasm-gc-module.md) |
| Library (reactor) module | `--no-wasi` | full (pure-compute exports) | any wasm-GC engine, no imports needed (Node 22+, current browsers) | [`--no-wasi` reactor mode](../guides/wasm-gc-module.md#no-wasi-reactor-mode) |
| WASI 0.3 component | `--component` | full, plus component-only I/O (`rontolisp:fetch`, TCP sockets) | wasmtime 46+ or another component host with wasm-GC | [WASI 0.3 component](../guides/wasm-component.md) |
| Reactor component | `--component --no-wasi` | full (pure-compute exports) | any component host with wasm-GC, empty import object | [Reactor components](../guides/wasm-component.md#reactor-components---component---no-wasi) |
| Plain core module | `--no-gc` | numeric/string [subset](../guides/wasm-nogc.md#eligible-subset) | **any** WebAssembly engine, even without wasm-GC or SIMD | [Non-GC output](../guides/wasm-nogc.md) |
| Compact typed component | `--no-gc --component` | numeric/string [subset](../guides/wasm-nogc.md#eligible-subset) | any component host, **zero flags** | [Compact component output](../guides/wasm-nogc.md#compact-component-output---no-gc---component) |

Rule of thumb: pick the **value model** by what the code needs — the full
language means the GC heap; a numeric/string kernel that fits the subset gains
universal portability and a hundreds-of-bytes binary from `--no-gc` — then pick
the **packaging** by the host: a component host gets `--component`, a plain
engine or JavaScript embedder gets a core module.

## Host Boundaries

Two complementary directives declare what crosses the module/host boundary:

- [**`rontolisp:wasm-export` / `rontolisp:wasm-import`**](../guides/wasm-host-boundary.md)
  spell out the boundary by hand, in rontolisp's own type designators (`:int`,
  `:float`, `:string`, `:s-expr`, ...). The same directive compiles into four
  different host contracts depending on the output shape (raw core function,
  typed component-model export, ...).
- [**WIT contracts (`wit-export` / `wit-import`)**](../guides/wit-contracts.md)
  drive the boundary from a `.wit` file — one contract, checked on every
  backend, with per-backend implementations (typed component-model exports
  under `--component`, provider callbacks on the interpreter and the JVM).
  Also covers [`--emit-wit`](../guides/wit-contracts.md#emitting-the-wit-world---emit-wit)
  and [`--scaffold-wit`](../guides/wit-contracts.md#scaffolding-an-implementation---scaffold-wit).

## Running a Component in a Browser

`jco transpile` turns a component into plain JavaScript that runs in a page:
see the [browser guide](../guides/wasm-browser.md) for what works today (a
`--no-gc --component` needs nothing at all, a wasm-GC `--component` loads and
computes but cannot yet print) and, at the end, a complete Node + browser
walkthrough for calling a `--no-wasi` / `--no-gc` reactor module by hand.

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
echo "(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)" > fact.lisp
rontolisp fact.lisp --no-wasi --optimize -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~4 KB module
```

For the `fact` example the module drops from ~320 KB to ~4 KB.
`--optimize` is opt-in and behavior-preserving: it walks the call graph from
the actual `call` instructions, so anything reachable (including code an
embedded `eval`/`load` dispatches to) is kept. It applies on **every** output
shape, `--component` included. The
same flag also dead-code-eliminates the [JVM output](jvm.md).

The dead functions take their baggage with them: the WASI imports only they used,
the type definitions nothing left names, and the static string data no surviving
code still addresses — a printed literal's module is a few hundred bytes rather
than the whole runtime's string table.

That floor does not depend on how the program spells the write. A constant text
is rendered at compile time and emitted as bytes, so `print`, `princ` + `terpri`,
`write-string`, `write-line` and `(format t "Hello, ~a!~%" "World")` all leave the
runtime printer behind and land within a few dozen bytes of each other (under 600 B
as a core module, under 1.8 KB as a component). What is left of the static data is
only what the program itself writes: the printer's own fixed strings — `NIL`, the
list punctuation, the float specials, the character names — go with the printer.
Print a computed value and both come back, as they must.

A value the compiler can work out for itself is not a computed value, though. A
call to a **pure built-in whose every argument is a literal** — `(* 6 7)`,
`(length "Hello World!")`, `(concatenate 'string "Hello" " " "World!")`,
`(string-upcase "hi")` — is evaluated at compile time and the call is deleted, on
every backend that compiles (the interpreter still evaluates it at run time, which
is the same answer). That happens before the printer fold above, so
`(princ (* 6 7))` reaches the very same floor as `(princ 42)` and
`(format t "~a~%" (length "abc"))` the same as `(format t "~a~%" 3)`. Redefine one
of those names — a `defun`, a `defmethod`, an `flet` — and your definition wins:
the compiler stops folding that name anywhere in the program. `--dynamic` turns
the whole thing off, since every name there resolves at run time.

On the `--component` path the **wrapper shrinks with the core**, not just the core
itself. Which WASI 0.3 interfaces a component imports follows from what the program
can actually reach: `(print "Hello World!")` compiles to a component importing
`wasi:cli/types` and `wasi:cli/stdout` and nothing else — no `wasi:filesystem`, no
`wasi:clocks`, no `wasi:random`, and not even `wasi:cli/stderr`, since nothing in
that program can write to standard error — while a program that opens a file, reads
the clock and draws random bytes keeps them all, and one that calls
[`warn`](../reference/macros/warn.md) or writes to `*error-output*` gets
`wasi:cli/stderr` back. `--emit-wit` prints the world the component really has, so
the emitted `.wit` shrinks with it.

```bash
echo '(print "Hello World!")' > hello.lisp
rontolisp hello.lisp --component --optimize -o hello.wasm    # ~2 KB
rontolisp hello.lisp --component -o hello-full.wasm          # ~325 KB
```

Without `--optimize` a component always declares the full fixed WASI surface, which
is what makes the two builds comparable byte-for-byte across releases.

`--optimize` also decides how much of a **loaded library** it can reach. A
compiled program calls most functions directly, but a `funcall` needs a dispatch
table, and a function listed there counts as reachable whether or not anything
ever calls it that way. So a function is listed only when your program can
actually obtain it as a value — `#'name`, a quoted `'name` designator, a
`lambda` — and everything else becomes ordinary dead code that `--optimize`
removes. On a program that loads `md5` and calls one function, that is the
difference between about 1.1 MB and 582 KB.

The listing is all-or-nothing, and one thing switches it off: if the program can
name a function at run time, every function has to stay reachable. That is any
use of `eval`, `read`, `read-from-string`, a runtime `load`, `intern`,
`find-symbol`, `make-symbol`, `symbol-function`, `fdefinition`, `fboundp` or
`uiop:symbol-call` — including one inside a library you loaded. When
`--optimize` does not shrink a program as much as you expected, ask the compiler
which operator it was:

```bash
rontolisp -Drontolisp.debug.dispatchgate=true app.lisp -o app.wasm --optimize
# => [dispatch-gate] every function stays dispatchable because of: INTERN
```

One `intern` shape is exempt: `(intern name :keyword)` only ever builds a
keyword, and a keyword can never name a function, so it leaves the listing on —
a handler upcasing a request method into `:GET`/`:POST` does not cost you the
optimization.

A `~/name/` directive in a format control string counts as well, because it
names its function at run time — but only a control string the compiler can see
brings it in, so a program that spells no such directive is unaffected (see
[`format`](../reference/macros/format.md)).

`--dynamic` switches it off too, by design: late binding resolves any name at
run time.

For a much smaller module still, the same `fact.lisp` compiled with
[`--no-gc`](../guides/wasm-nogc.md) lowers `fact` to unboxed `i32` and drops
the whole GC runtime that made the 5 KB (the condition hierarchy, cons cells,
the printer):

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, from a ~76 byte module (no -W gc)
```

The source is unchanged — `wasm-export` works identically on both value models
— and the resulting module also drops the `-W gc` runtime requirement.

Independently of `--optimize` (and on every output mode, `--component`
included), compilation always tree-shakes the libraries it splices in: the
bundled Lisp-source ones (`linalg:`, `vec:`, JSON, URL, `equalp`/`string<`) and
every system loaded with
[`asdf:load-system` / `ql:quickload`](../guides/asdf-systems.md). A function,
variable or constant your program never mentions -- by name anywhere in the
source, including quoted symbols and string literals -- is not compiled into the
module. Your own code is never pruned, and neither is anything a `load`/`require`
splices in: only a library that came from a system is subject to it.

Classes, generic functions, methods, conditions and structures are pruned by
the same rule: a class nothing references leaves together with its methods,
and a method on a generic your program does call is still dropped when no
reachable code can create an instance of the class it specializes on. Methods
on the standard protocol names (`initialize-instance`, `print-object`,
`close`, ...) follow their class alone, since those calls are implicit.

The one consequence: a library function whose name is only assembled at runtime
from computed strings and called through `eval`/`apply` signals the usual
"undefined function" error. Compile with `--no-prune` (or `--dynamic`) to keep
every library definition in that case.

The flag takes an optional level. `--optimize` and `--optimize=default` are the
same thing — everything above — and the bare spelling keeps that meaning
permanently; `--optimize=size` is that plus the trades in the next section.

### Optimizing for Size (`--optimize=size`)

Two wasm-GC emissions deliberately spend bytes to gain speed, and both are on
whether or not you pass `--optimize`:

- an integer expression tree like `(logand (+ (ash x 7) i) #xFFFFFFFF)` compiles
  **twice** — once as a single unboxed `i64` computation, and once through the
  generic helpers, as the fallback a float, a ratio or an overflow into bignum
  territory takes;
- a `let` binding whose assignments are integer arithmetic gets an unboxed
  `i64` slot beside its ordinary boxed one.

`--optimize=size` declines both. Nothing the program computes changes — the
fast path only ever existed as an alternative to the fallback, which stays —
but the arithmetic now runs through the generic helpers, so the price is real,
and how much you pay depends on how integer-heavy the program is:

| program | `--optimize` | `--optimize=size` | run time |
| --- | --- | --- | --- |
| ironclad SHA-256/HMAC/PBKDF2, 4096 rounds | 2,078,195 B | 1,562,816 B (**-24.8%**) | 1.4 s -> 5.2 s (**3.8x**) |
| a `vec:`-kernel neural-net training loop | 271,233 B | 214,169 B (-21.0%) | 1.07 s -> 1.26 s (+18%) |
| a float MLP training loop (no `vec:`) | 159,747 B | 125,496 B (-21.4%) | 5.6 s -> 6.1 s (+9%) |
| `cl-postgres` hello world (`--component`) | 8,024,998 B | 6,384,099 B (-20.4%) | — |

(wasmtime 47, best of three runs.) The size win barely varies; the run-time
price does, because only integer arithmetic fuses — a float kernel pays it on
its loop indices alone, while a crypto round pays it on everything.

So reach for it when the module has to travel — an edge deploy, a browser
download, a registry with a size limit — unless the program's hot loop is
integer arithmetic (hashing, crypto, bit twiddling), where the same win costs
several times the run time.

The level is accepted on every backend, so a build script need not know which
one it targets, but only wasm-GC (Preview 1 and `--component`) has anything to
trade: the [JVM](jvm.md) and [`--no-gc`](../guides/wasm-nogc.md) outputs are
byte-for-byte what `--optimize` produces.

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
