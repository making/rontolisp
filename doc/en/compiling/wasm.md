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
  compact typed reactor component that runs with no host flags at all. On the
  Preview 1 GC path, `--no-wasi` instead drops the WASI imports, turning the
  module into a pure-compute library ("reactor") a host can instantiate with no
  import object.

Crossing the two axes gives the five shapes:

| Output shape | Flags | Language | Runs on | Details |
| --- | --- | --- | --- | --- |
| WASI command module | (none) | full | wasm-GC engine with WASI Preview 1 (`wasmtime run -W gc`) | [wasm-GC core module](../guides/wasm-gc-module.md) |
| Library (reactor) module | `--no-wasi` | full (pure-compute exports) | any wasm-GC engine, no imports needed (Node 22+, current browsers) | [`--no-wasi` reactor mode](../guides/wasm-gc-module.md#no-wasi-reactor-mode) |
| WASI 0.3 component | `--component` | full, plus component-only I/O (`rontolisp:fetch`, TCP sockets) | wasmtime 46+ or another component host with wasm-GC | [WASI 0.3 component](../guides/wasm-component.md) |
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
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~5 KB module
```

For the `fact` example the module drops from ~330 KB to ~5 KB.
`--optimize` is opt-in and behavior-preserving: it walks the call graph from
the actual `call` instructions, so anything reachable (including code an
embedded `eval`/`load` dispatches to) is kept. It applies on **every** output
shape, `--component` included — the core module is shaken before the wrap. The
same flag also dead-code-eliminates the [JVM output](jvm.md).

`--optimize` also decides how much of a **loaded library** it can reach. A
compiled program calls most functions directly, but a `funcall` needs a dispatch
table, and a function listed there counts as reachable whether or not anything
ever calls it that way. So a function is listed only when your program can
actually obtain it as a value — `#'name`, a quoted `'name` designator, a
`lambda` — and everything else becomes ordinary dead code that `--optimize`
removes. On a program that loads `md5` and calls one function, that is the
difference between 1.18 MB and 598 KB.

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

Classes, generic functions, methods, conditions and structures always stay,
because a `make-instance` can reach a method no source line names.

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
| ironclad SHA-256/HMAC/PBKDF2, 4096 rounds | 2,075,455 B | 1,560,097 B (**-24.8%**) | 1.4 s -> 5.2 s (**3.8x**) |
| a `vec:`-kernel neural-net training loop | 288,576 B | 231,533 B (-19.8%) | 1.07 s -> 1.26 s (+18%) |
| a float MLP training loop (no `vec:`) | 177,173 B | 142,943 B (-19.3%) | 5.6 s -> 6.1 s (+9%) |
| `cl-postgres` hello world (`--component`) | 8,033,507 B | 6,408,277 B (-20.2%) | — |

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
