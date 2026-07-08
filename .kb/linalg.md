# The `linalg` package (numpy-style vector/matrix operations)

One hand-written Lisp-source library, `src/main/resources/am/ik/rontolisp/eval/linalg.lisp`,
following the `json.lisp` pattern (see `json.md`) so a single implementation
runs identically on all backends. 33 exported functions (constructors `zeros`/
`ones`/`full`/`eye`/`arange`/`linspace`/`from-list`, shape ops, broadcasting
`add`/`sub`/`mul`/`div`/`emap`, products `dot`/`matmul`/`outer`, reductions,
and floating-point Gaussian-elimination `det`/`inv`/`solve`) over the built-in
arrays. The library computes in packed float (speed over exactness), **double by
default** but **width-polymorphic** (`.todo/97`): a constructor opts into packed
single-float (`#f`) with a trailing `element-type` argument, and every transform
PRESERVES its input width (see "Single-float / width polymorphism" below).
Elementwise ops, reductions, `reshape`/`flatten` and `array-equal`
walk elements via `row-major-aref`, so they work for any rank; `dot`/`matmul`/
`outer`/`det`/`inv`/`solve`/`trace`/`transpose` stay defined for rank <= 2.

## API quick reference (enough to write linalg programs)

Inputs are the ordinary built-in arrays: a vector is a rank-1 array `#(...)`,
a matrix rank-2 `#2A(...)` (higher ranks `#nA(...)`); both are readable literal
syntax as well as print syntax (the reader parses `#nA(...)` via
`Token.ArrayOpen`), so examples should prefer `#(1 2 3)` / `#2A((1 2) (3 4))`
over `from-list`. linalg RESULTS, however, are packed double-float arrays
(`LispDoubleFloatArray`) and print with the **`#d(...)`** reader syntax at every
rank (`#d(1.0 2.0 ...)` for a vector, nested `#d((...) ...)` for a matrix), so the
printed form round-trips to a packed array rather than degrading to a general one
-- an example that pins linalg output must expect `#d(...)`, not `#(...)` (the
todo-95 flip: `#d` is the double packed prefix, `#f` is now single-float).
Read/write elements with `aref` / `(setf (aref ...))`.
`shape` below means an integer `n` (vector) or a list of dimension sizes.
Stay in `cl-user` and call qualified names (the package does not use `cl`).
`#'linalg:name` works (they are plain defuns). Errors signal via `error`.

| Function | Semantics |
| --- | --- |
| `(linalg:zeros shape)` / `(linalg:ones shape)` / `(linalg:full shape v)` | new array filled with 0 / 1 / v |
| `(linalg:eye n)` | n x n identity matrix |
| `(linalg:arange stop)` / `(arange start stop)` / `(arange start stop step)` | vector, stop exclusive, step may be negative |
| `(linalg:linspace start stop n)` | n evenly spaced values inclusive (packed double-float) |
| `(linalg:from-list lst)` | flat list -> vector; list of equal-length rows -> matrix |
| `(linalg:to-list a)` | inverse of from-list |
| `(linalg:shape a)` / `(linalg:size a)` | dims list `(n)` or `(rows cols)` / total element count |
| `(linalg:reshape a shape)` | row-major copy; error if sizes differ |
| `(linalg:flatten a)` | rank-1 row-major copy |
| `(linalg:transpose a)` | matrix transpose; a vector is returned unchanged |
| `(linalg:add a b)` / `sub` / `mul` / `div` | elementwise; a scalar operand on either side broadcasts; two arrays must have equal shapes; `mul` is Hadamard (NOT matrix product); results are packed double-float arrays |
| `(linalg:emap f a)` | fresh array with f applied to every element |
| `(linalg:dot a b)` | numpy dispatch: vec.vec -> scalar, mat.vec / vec.mat -> vector, mat.mat -> matrix product; scalar operand multiplies elementwise |
| `(linalg:matmul a b)` | matrix product (also mat.vec); rejects scalar operands |
| `(linalg:outer u v)` | outer product (inputs flattened first) |
| `(linalg:sum a)` / `(linalg:mean a)` | sum / mean over all elements (a reduction follows the element type: a packed/float array reduces to a double, a plain integer array to an integer/ratio) |
| `(linalg:amax a)` / `(linalg:amin a)` | largest / smallest element; error on empty |
| `(linalg:argmax v)` / `(linalg:argmin v)` | index in a VECTOR (first on ties) |
| `(linalg:norm a)` | Euclidean / Frobenius norm (a float, via sqrt) |
| `(linalg:trace a)` | main-diagonal sum; square matrices only |
| `(linalg:det a)` / `(linalg:inv a)` / `(linalg:solve a b)` | Gaussian elimination with partial pivoting in floating point (double); a singular matrix's `det` may be a small epsilon rather than exactly 0; `inv` errors on a singular matrix; `solve` solves a.x = b for a vector or matrix b |
| `(linalg:array-equal a b)` | same shape + numerically equal elements (1 = 1.0); needed because arrays themselves are `eq`-compared only |

Gotchas when writing programs: no numpy-style row/column broadcasting (scalar
only); `dot`/`matmul`/`outer`/`det`/`inv`/`solve`/`trace`/`transpose` are
rank <= 2 only (everything else is rank-generic); results are fresh arrays
(inputs are never mutated);
because linalg now computes in double-float, non-terminating results print at
fewer significant digits on WASM than on the JVM, so cross-backend-deterministic
linalg output should stick to integer-valued or short-terminating-decimal
results (e.g. power-of-two matrices for `inv`/`solve`, whose inverses are exact
short decimals). See `examples/ml/linear-regression.lisp`,
`examples/ml/deep-digits.lisp` and `examples/ml/heat3d.lisp` for worked idioms
(incl. an i31-safe fixed-seed LCG, matrix backpropagation and the rank-3 idioms).

## Single-float / width polymorphism (`.todo/97`)

linalg is **double by default** (precision) but accepts and preserves packed
single-float (`#f`) so a `#f` value flowing in from `vec:` is never silently
widened back to double -- the widening would force a mixed-width `--simd` error on
the next `vec:matvec` (a `#d` matrix x a `#f` vector). Two orthogonal mechanisms:

- **Constructor opt-in.** Every constructor takes a trailing optional
  `element-type` (positional symbol, default `'double-float`):
  `(linalg:zeros '(3 4) 'single-float)`, `(linalg:ones n 'single-float)`,
  `(linalg:full shape v 'single-float)`, `(linalg:eye n 'single-float)`,
  `(linalg:linspace a b n 'single-float)`, `(linalg:from-list lst 'single-float)`,
  and `(linalg:arange ... 'single-float)`. `arange` is variadic (`&optional b
  step`), so it disambiguates by type -- `step` is always a number and an
  `element-type` a non-nil symbol, so `(linalg:arange 0 10 'single-float)` reads
  the symbol as the type (step defaulting) and `(linalg:arange 0 10 2 'single-float)`
  keeps both. It is a **positional** symbol, NOT a `:element-type` keyword: a `&key`
  cannot follow `arange`'s `&optional` (the optional greedily eats the keyword), so
  the whole family uses a trailing optional for uniformity.
- **Width-following transforms.** `add`/`sub`/`mul`/`div`/`emap` (via
  `%la-like`), `transpose`/`reshape`/`flatten`, `dot`/`matmul`/`outer` and
  `inv`/`solve` all PRESERVE the (first) array input's width -- a `#f` stays `#f`,
  a `#d` stays `#d` -- so a functional weight update `(linalg:sub W grad)` keeps
  `W`'s single-float width. No API change; it is automatic.

The seam is `linalg::%la-make (dims init &optional element-type)`: `(if (eq
element-type 'single-float) (make-array ... 'single-float ...) (make-array ...
'double-float ...))`. Both branches take a **literal** `:element-type`, so every
backend -- interpreter, JVM AND **wasm-GC** -- picks the `double[]`/`float[]`
(`TYPE_F64ARR`/`TYPE_F32ARR`) repr statically and produces `#f` directly; a
runtime-computed element-type could not. `linalg::%la-etype a` returns the literal
symbol matching `a`'s width (a general/boxed array reads back as `t`, so it maps to
`'double-float`), and `%la-like` threads it. **No `#+/#-rontolisp-wasm` reader
conditional is needed** -- unlike `vec::%make-like`, whose split (double-only on
wasm) predates the wasm-GC `TYPE_F32ARR` (`.todo/95` Phase 4) and is now vestigial:
`vec:` still renders a `#f` element-wise result as `#d` on wasm-GC while linalg
renders `#f` on every backend. Because single-float trades precision for speed,
precision-critical `det`/`inv`/`solve` are best left double (the default); a `#f`
input to `inv`/`solve` computes (and returns) in single-float, matching numpy.

Cross-backend determinism: like double results, a non-terminating `#f` decimal
prints differently on WASM, so a cross-backend `#f` pin must use f32-EXACT values
(integers / halves) -- see the `linalg-single-float-cross-backend` ci-spec case,
which is byte-identical on all four backends.

## Wiring

- **Package**: `linalg` is a built-in package registered in the
  `PackageRegistry` constructor; the exported names live in
  `PackageRegistry.LINALG_FUNCTIONS` (exposed as `linalgFunctionNames()`).
  Like `rontolisp`, it does not use `cl`, so inside `(in-package linalg)`
  standard names need `cl:` qualification. Adding a function = add the name
  there + a defun in `linalg.lisp` (+ per-operator doc pages, en and ja).
- **Driver**: `am.ik.rontolisp.eval.LinalgLibrary`, a simplified `JsonLibrary`
  mirror. Unlike JSON there is **no call-site rewriting**: every entry point is
  a plain defun (`&optional` desugars through `LambdaLists`), so
  `process(program)` only detects usage (any `linalg:`/`linalg::` qualified
  symbol anywhere, or a bare exported name while `(in-package linalg)` is in
  effect) and prepends `forms()`.
- **Interpreter (lazy load)**: no per-function dispatchers. `LispEvaluator.
  resolveFunction` falls back, on a missed lookup of a `linalg:`-qualified
  name, to evaluating `LinalgLibrary.forms()` into the global environment once
  and retrying. `#'linalg:name` works through the same path.
- **Compile path**: `RontoLispCli.compileToFile` and the web playground
  (`RontoPlayground.compileJvm/Wasm`) wrap the program as
  `LinalgLibrary.process(JsonLibrary.process(...))`. Compiler unit tests must
  call `LinalgLibrary.process` explicitly (see `compileAndRunLinalg` helpers).
- **Native image**: `linalg.lisp` is registered in
  `META-INF/native-image/.../resource-config.json` (typeReachable
  `LinalgLibrary`).

## Source constraints (linalg.lisp)

- Written in canonical package shape (external `linalg:name` defuns, internal
  `linalg::%la-*` helpers, bare `cl` names), so resolving it is a fixed-point
  no-op — pinned by `PackageResolverTest.linalgLibraryFormsAreAResolverFixedPoint`.
- `%la-bcast`'s broadcast lambdas capture the operator and the scalar operand,
  so its parameters use `%la-` names, a leftover workaround from when the
  compiled backends resolved a captured name against a same-named user global
  first (fixed 2026-07-03 in `Jvm/WasmLambdaCompiler`; the ci-spec
  `dynamic-function-selection` case defines a global `f` and broke `linalg:add`
  before the rename — the rename stays because it is harmless).
- Arithmetic runs in packed float (speed over exactness), double by default but
  width-polymorphic (see "Single-float / width polymorphism"); the
  `linalg-package-cross-backend` ci-spec case uses power-of-two matrices for
  `det`/`inv`/`solve` so their float results are exact and print identically on
  every backend, and `linalg-single-float-cross-backend` pins the `#f` path.
- Flat iteration uses `row-major-aref` / `(setf (row-major-aref ...))`
  directly (the former `%la-cols`/`%la-fref`/`%la-fset` cols-encoding helpers
  were deleted when rank-n arrays landed), which is what makes the elementwise
  ops rank-generic.
- Not supported: `--no-gc` (arrays), runtime `eval` of linalg forms (the
  emitted eval runtime has no array ops), broadcasting between different
  non-scalar shapes (numpy-style row/column broadcast).

## Standard array functions added alongside

`array-dimensions` and `row-major-aref`/`%row-major-aset` are the backend
primitives (interpreter `Environment.registerArrays`; JVM helpers in
`JvmArrayRuntimeBuilder` -- row-major access reuses `_aref1`/`_aset1` because
the data is stored flat right after the header; WASM inline emission in
`WasmArrayCompiler`). Everything else is `LispMacroExpander` expansion over
existing primitives: `vector` (make-array + %aset), `svref` (aref; also a setf
place sharing the `%aset` case), `array-rank`/`array-dimension`/
`array-total-size`/`array-row-major-index` (over array-dimensions), and
`coerce` (literal `'list`/`'vector`/`'string` only, runtime dispatch on
listp/stringp). The JVM helper gating
(`JvmLispCompiler.programUsesAnyArrayOp`) must list the derived names too,
because the scan runs before expansion. None are first-class function values
(matching `aref`/`make-array`).
