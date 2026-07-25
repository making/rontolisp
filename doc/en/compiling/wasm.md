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
  range, floats boxed in a struct), which supports the **full
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
wasmtime run --invoke fact -W gc fact.wasm 5      # => 120, from a ~18 KB module
```

For the `fact` example the module drops from ~170 KB to ~18 KB.
`--optimize` is opt-in and behavior-preserving: it walks the call graph from
the actual `call` instructions, so anything reachable (including code an
embedded `eval`/`load` dispatches to) is kept. On the **GC `--component`** path
it is a no-op (the WASI 0.3 adapter relies on the core's fixed import/index
layout, so the component is emitted unchanged); under
[`--no-gc --component`](../guides/wasm-nogc.md#compact-component-output---no-gc---component)
it works — the core module is shaken before the wrap. The same flag also
dead-code-eliminates the [JVM output](jvm.md).

For a much smaller module still, the same `fact.lisp` compiled with
[`--no-gc`](../guides/wasm-nogc.md) lowers `fact` to unboxed `i32` and drops
the whole GC runtime that made the 18 KB (the reader's case-fold table, the
condition hierarchy, cons cells, the printer):

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, from a ~76 byte module (no -W gc)
```

The source is unchanged — `wasm-export` works identically on both value models
— and the resulting module also drops the `-W gc` runtime requirement.

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
