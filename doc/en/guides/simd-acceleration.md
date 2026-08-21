# Vector Kernels and SIMD Acceleration (vec, linalg)

The `vec` package provides portable packed-`f64` vector kernels: constructors, element access, element-wise arithmetic and reductions over the [packed float array type](../reference/data-types.md). It is the go-to package for tight numeric loops over vectors of doubles, and it carries an optional hardware-acceleration (SIMD) layer on every backend. The package names the portable abstraction; the `--simd` flag names how it is accelerated.

`--simd` is not a `vec`-only flag. It accelerates the [`linalg` package](linear-algebra.md) too, over the very same arrays. This guide covers `vec` first, then the flag, then what it does for `linalg`.

Like the JSON and `linalg` libraries, `vec` is implemented once in Lisp source (`vec.lisp`): the interpreter loads the definitions lazily on the first use of a `vec:` function, and the compile path splices them into the program when it references the package. This scalar definition is the implementation on the interpreter, the JVM compiler and the WASM (wasm-GC) backends, and the correctness oracle for the accelerated paths, so every function behaves identically everywhere.

## Choosing between vec and linalg

`vec` and `linalg` are not two implementations of the same idea. They are two **contracts** over the same packed float arrays -- and under `--simd` they land on the same accelerated kernels, so the choice is never about speed. It is about how a function should behave at the edges:

| | `linalg` | `vec` |
|---|---|---|
| accepted inputs | packed arrays, general boxed arrays such as `#(1 2 3)`, plain numbers | packed float arrays only |
| mixed widths (`#d` with `#f`) | allowed -- both are widened, the first operand's width wins | hard error |
| broadcasting | numpy rules -- a scalar on either side, and arrays of different shapes along their trailing axes | only the scalar of `vec:scale` |
| shapes | rank-n arrays and matrices, descriptive shape errors | rank-1 vectors (plus `vec:matvec`'s rank-2 matrix) |
| allocation control | every result is a fresh array | `-into` siblings write into a caller-supplied destination |
| `--no-gc` | does not compile | fully supported (the only vector package there) |

Rule of thumb: **write against `linalg` by default.** It is the broader, numpy-style API, it forgives mixed inputs, and with `--simd` it is accelerated by the same kernels. Reach for `vec` when one of its three exclusives is the point: an allocation-free hot loop (the `-into` kernels), a `--no-gc` target, or the fail-fast strictness that turns a width mistake into an immediate error instead of a silent widening.

## Data representation

A vector is a rank-1 [packed float array](../reference/data-types.md): the `double-float`-typed, unboxed array that `#d(...)` and `(make-array n :element-type 'double-float)` produce. The built-in `aref` / `length` interoperate with it, and any packed vector built elsewhere can be handed to a `vec` function. Element-wise kernels return a fresh vector; reductions return a scalar `double`.

The kernels are width-polymorphic: they also accept single-float vectors (`#f(...)` / `:element-type 'single-float`, which store elements as `f32` -- half the memory, twice the SIMD lanes). The element-wise kernels preserve the input width on every backend (a `#f` in gives a `#f` out), while the reductions always fold to a scalar `double`.

```lisp
(vec:arange 5)                         ; => #d(0.0 1.0 2.0 3.0 4.0)
(vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => #d(5.0 7.0 9.0)
(vec:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => 32.0
(vec:scale #d(1.0 2.0 3.0) 10)         ; => #d(10.0 20.0 30.0)
```

## The API

Construction: `vec:zeros` / `vec:ones` build a filled vector of length *n*, `vec:arange` builds `[0.0, 1.0, ..., n-1]`, and `vec:from-list` / `vec:to-list` convert between a vector and a Lisp list (the list forms run on the interpreter, the JVM and wasm-GC only, not `--no-gc`). `vec:zeros` / `vec:ones` / `vec:arange` also take an `:element-type` keyword: pass `:element-type 'single-float` for a packed single-float (`#f`) vector (the default is double-float), mirroring the [linalg constructors](linear-algebra.md#single-float-precision) and honored on every backend including the JVM and WASM `--simd` v128 paths.

Access: `vec:aref` reads an element (a `setf` place via `vec:aset`), and `vec:length` returns the element count. These are thin wrappers over the generic packed-array operators, so plain `aref` / `length` work too.

Element-wise (a fresh vector): `vec:add`, `vec:sub`, `vec:mul` (Hadamard product), `vec:div` and `vec:scale` (multiply by a scalar).

The first four also answer to their CL operator spellings -- `vec:+`, `vec:-`, `vec:*` and `vec:/` -- which are exact aliases and compile to the same code, accelerated paths included. Unlike their n-ary [`linalg:`](linear-algebra.md) counterparts they are **strictly binary**: every `vec:` kernel is fixed-arity and allocation-explicit (the reason the `-into` family below exists), so an n-ary spelling that silently allocated one intermediate vector per extra operand would work against the point of the package. Write `(vec:+ (vec:+ a b) c)`, or better, an `-into` loop.

Element-wise unary, under their numpy ufunc names (a fresh vector): `vec:exp`, `vec:log`, `vec:tanh`, `vec:sin`, `vec:cos`, `vec:tan`, `vec:asin`, `vec:acos`, `vec:atan`, `vec:sinh`, `vec:cosh`, `vec:sqrt`, `vec:abs`, `vec:square`, `vec:negative`, `vec:sign` and `vec:reciprocal` (`1 / x`). Each applies the backend's own scalar operation per element, so the transcendental members (`vec:exp` / `vec:log` / `vec:tanh` / `vec:sin` / `vec:cos` / `vec:tan` / `vec:asin` / `vec:acos` / `vec:atan` / `vec:sinh` / `vec:cosh`) on the WASM backends use their software approximations (whose low-order digits differ from the JVM's), and the `-0.0` edges of `vec:abs` / `vec:negative` / `vec:sign` / `vec:tanh` / `vec:sin` / `vec:tan` follow each backend's own scalar operation. On `--no-gc`, the transcendental members and `vec:sign` run the same software sequences as the other WASM backends, so all seventeen work everywhere.

Comparison selects: `vec:maximum` / `vec:minimum` (the element-wise larger / smaller of two vectors), `vec:relu` (element-wise `max(x, 0.0)`) and `vec:clip` (element-wise `min(max(x, lo), hi)` with scalar bounds). All four are defined by the strict comparison select `(if (> x y) x y)` and its mirrors -- never an IEEE min/max primitive -- so the second operand (or the bound) wins whenever the comparison is false: a `-0.0` element against `0.0` takes the second, a `NaN` follows the same rule (`vec:relu` maps it to `0.0`, `vec:clip` to `lo`), and every backend agrees exactly, `--no-gc` included.

Reductions (a scalar): `vec:sum`, `vec:dot`, `vec:mean` and `vec:norm` (the Euclidean norm, `sqrt` of the self-dot).

```lisp
(vec:sum (vec:arange 5))              ; => 10.0
(vec:mean #d(2.0 4.0 6.0))             ; => 4.0
(vec:norm #d(3.0 4.0))                 ; => 5.0
(vec:to-list (vec:mul #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))) ; => (4.0 10.0 18.0)
```

Matrix times vector (a fresh vector): `vec:matvec` is GEMV -- a rank-2 packed matrix `W` (shape *d* x *n*) times a rank-1 vector `x` of length *n*, giving a length-*d* vector whose *i*-th element is the dot product of row *i* of `W` with `x` (no transpose). It is the workhorse of a neural network's forward pass -- every projection, feed-forward and classifier layer is a `vec:matvec` -- so it is the one kernel run once per matrix row rather than element-wise. The result follows the input width. On `--no-gc`, build `W` with `(make-array (list d n) :element-type ...)` plus `setf` of a two-subscript `aref` -- a rank-2 `#d((...))` literal is not supported there, and `x` must be the same width as `W` (the usual `vec` strictness).

```lisp
(vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)) ; => #d(17.0 39.0)
```

The [`ml/nn-vec.lisp` example](https://github.com/making/rontolisp/blob/develop/examples/ml/nn-vec.lisp) is a small XOR network whose single-float forward pass is built from `vec:matvec`.

## Memory: where vectors live, and what reclaims them

A packed float array is an ordinary garbage-collected value on three of the four targets, and a block of WebAssembly linear memory on the fourth. Only the last one asks you to think about memory growth.

| target | packed arrays live in | reclaimed automatically? |
|---|---|---|
| interpreter (no `-o`) | the JVM heap | yes, by the JVM's collector |
| JVM (`-o prog.class`) | the JVM heap | yes, by the JVM's collector |
| wasm-GC (`-o prog.wasm`) | the WebAssembly GC heap | yes, by the engine's collector |
| `--no-gc` (`-o prog.wasm --no-gc`) | linear memory, bump-allocated | **no -- nothing is ever freed, so you must watch memory growth** |

On the three garbage-collected targets, a loop that discards its intermediates keeps a flat footprint. Building a fresh 1024-element vector 200000 times on wasm-GC (`wasmtime run -W gc`) peaks at the same ~123 MB as doing it 50000 times, even though 1.5 GB passed through the allocator. `linalg` arrays are the same packed type and behave identically.

`--no-gc` is different by design -- the name says it, there is no collector. `__ronto_alloc` is a bump allocator with no free, so **every kernel that returns a vector permanently consumes memory**. Reclamation happens only by discarding the whole arena at an export-call boundary, which a `--no-gc` module always has (its top level is nothing but `defun`s and `rontolisp:wasm-export` directives -- there is no `_start`):

- an export whose return type is a non-memory scalar (`:int` / `:long` / `:float` / `:bool` / `:void`) resets the bump pointer automatically when it returns;
- a resident host can bracket a call with the exported `__ronto_alloc_mark` / `__ronto_alloc_reset` pair;
- **inside a single export call nothing is freed.** A loop of `(setq acc (vec:add acc d))` grows linear memory until `memory.grow` fails.

That last point is what the destination-passing kernels below are for. Strings on `--no-gc` (`concatenate`, `subseq`, `princ-to-string`) bump-allocate the same way; `linalg` does not compile under `--no-gc` at all, so only `vec` is affected.

wasm-GC has a linear memory too, but packed arrays never touch it: it holds interned symbol names and string-stream buffers, and runtime strings were moved onto the GC heap precisely so that it would stop growing.

## Destination-passing kernels (allocation-free loops)

Every kernel above that returns a vector returns a **fresh** one, so a loop over them allocates one vector per iteration. Each has an `-into` sibling that writes into a caller-supplied destination and returns it, letting you hoist the allocation out of the loop. The destination comes first, mirroring Common Lisp's own `map-into`.

| allocating | destination-passing |
|---|---|
| `(vec:add a b)` | `(vec:add-into out a b)` |
| `(vec:sub a b)` | `(vec:sub-into out a b)` |
| `(vec:mul a b)` | `(vec:mul-into out a b)` |
| `(vec:div a b)` | `(vec:div-into out a b)` |
| `(vec:scale v s)` | `(vec:scale-into out v s)` |
| `(vec:matvec w x)` | `(vec:matvec-into out w x)` |
| `(vec:exp v)` | `(vec:exp-into out v)` |
| `(vec:log v)` | `(vec:log-into out v)` |
| `(vec:tanh v)` | `(vec:tanh-into out v)` |
| `(vec:sin v)` | `(vec:sin-into out v)` |
| `(vec:cos v)` | `(vec:cos-into out v)` |
| `(vec:tan v)` | `(vec:tan-into out v)` |
| `(vec:asin v)` | `(vec:asin-into out v)` |
| `(vec:acos v)` | `(vec:acos-into out v)` |
| `(vec:atan v)` | `(vec:atan-into out v)` |
| `(vec:sinh v)` | `(vec:sinh-into out v)` |
| `(vec:cosh v)` | `(vec:cosh-into out v)` |
| `(vec:sqrt v)` | `(vec:sqrt-into out v)` |
| `(vec:abs v)` | `(vec:abs-into out v)` |
| `(vec:square v)` | `(vec:square-into out v)` |
| `(vec:negative v)` | `(vec:negative-into out v)` |
| `(vec:sign v)` | `(vec:sign-into out v)` |
| `(vec:reciprocal v)` | `(vec:reciprocal-into out v)` |
| `(vec:maximum a b)` | `(vec:maximum-into out a b)` |
| `(vec:minimum a b)` | `(vec:minimum-into out a b)` |
| `(vec:relu v)` | `(vec:relu-into out v)` |
| `(vec:clip v lo hi)` | `(vec:clip-into out v lo hi)` |

The reductions (`vec:sum`, `vec:dot`, `vec:mean`, `vec:norm`) return a scalar and never allocated, so they have no sibling.

```lisp
(let ((acc (vec:zeros 3))
      (d #d(1.0 2.0 3.0)))
  (dotimes (i 3) (vec:add-into acc acc d))
  acc)                                   ; => #d(3.0 6.0 9.0)
```

In the element-wise kernels -- binary and unary alike -- the destination **may alias** an operand: element *i* of the result depends only on element *i* of the inputs, so `(vec:add-into acc acc d)` above and `(vec:exp-into v v)` are well-defined in-place updates. `vec:matvec-into` is the exception -- each output element folds over all of `x`, so writing into `x` would clobber a value a later row still has to read. Passing the same array as both `out` and `x` (or `w`) signals an error rather than corrupting it.

All operands must share an element type, and `out` must be at least as long as the inputs (its length is not checked, exactly as `vec:add` does not check its operands').

This is what makes `--no-gc` usable for real numeric loops (see the memory table above): with `-into`, peak memory equals the vectors you actually keep alive. Measured on `--no-gc --simd`, accumulating a 65536-element vector 12000 times peaks at 13.7 MB with `vec:add-into`, against 4.31 GB -- and then a trap -- with `vec:add`. On the three garbage-collected targets `-into` changes nothing about correctness; there it is an allocation-rate optimization.

On `--no-gc`, `vec:matvec-into`'s aliasing guard is a WebAssembly trap (an `unreachable` instruction) rather than a Lisp error -- the backend has no error channel -- and it matters most there: a decode loop of GEMVs would otherwise bump-allocate a fresh output vector per step with nothing ever freed.

## Hardware acceleration (optional)

The scalar `vec.lisp` reference is correct on every backend. `--simd` is the single, backend-independent switch that additionally lowers the vectorizable kernels (`add` / `sub` / `mul` / `div` / `scale` / `dot` / `sum` / `matvec` and the four operator aliases, the unary ufuncs `exp` / `log` / `tanh` / `sin` / `cos` / `tan` / `asin` / `acos` / `atan` / `sinh` / `cosh` / `sqrt` / `abs` / `negative` / `sign` / `reciprocal`, the comparison selects `maximum` / `minimum` / `relu` / `clip`, and all their `-into` siblings, plus `mean` / `norm` / `square` transitively) to real CPU vector instructions or de-boxed loops. It is opt-in. The element-wise kernels stay byte-for-byte identical to the scalar reference; the reductions sum in a different order, and a single-float reduction also accumulates in single precision, so those can differ from it -- see the two paragraphs on precision below. The same flag accelerates a set of `linalg` functions, listed in the next section. Two further orthogonal flags follow it: `--blas` routes `linalg`'s matrix product to a tuned BLAS out of the operating system, and `--gpu` routes that product, the element-wise transcendentals and the broadcast / axis-fold / axes-transpose shapes to an NVIDIA device. Both are covered below.

Which memory model you compile for (`.class`, wasm-GC `.wasm`, or `--no-gc` `.wasm`) and whether you pass `--simd` are **orthogonal** axes:

| target | without `--simd` | with `--simd` |
|---|---|---|
| interpreter (no `-o`) | scalar `vec.lisp` | `jdk.incubator.vector` (baked into the native binary; `java -jar` needs `--add-modules`) |
| JVM (`-o prog.class`) | scalar `vec.lisp` | `jdk.incubator.vector` bridge |
| wasm-GC (`-o prog.wasm`) | scalar `vec.lisp` | native v128 (`f64x2` / `f32x4`) |
| `--no-gc` (`-o prog.wasm --no-gc`) | scalar linear-memory loops | native v128 (`f64x2` / `f32x4`) |

- **Interpreter `--simd`**: `rontolisp prog.lisp --simd` runs the same kernels on `jdk.incubator.vector` instead of the scalar `vec.lisp` definitions -- no compilation step, and a large `vec:dot` gets several times faster. The native binary has the incubator module baked in and needs no runtime flag. On a plain `java -jar` the module is absent, so the flag falls back to the scalar reference and prints a note; re-run with `java --add-modules jdk.incubator.vector -jar rontolisp.jar prog.lisp --simd` to get the acceleration there. Without `--simd` the interpreter always runs the scalar reference -- it is the cross-backend oracle. The flag also works in the REPL: `rontolisp --simd` accelerates the `vec:` / `linalg:` kernels the same way.
- **JVM `--simd`**: `rontolisp prog.lisp -o Prog.class --simd` routes the kernels to an embedded `jdk.incubator.vector` bridge (a `DoubleVector` for `#d`, a `FloatVector` for `#f`; `vec:matvec` runs that vectorized dot once per matrix row). Running such a class requires the incubator module on the JVM: `java --add-modules jdk.incubator.vector Prog`. Without `--simd` the class runs the scalar reference on any JVM. **Whether the bridge becomes CPU vector instructions is up to the JVM that runs the class.** The Vector API is a normal library that a JVM may or may not compile down to vector instructions, operation by operation; where it does not, it falls back to emulating each lane, which is far slower than the plain scalar loop `--simd` replaced. So `--simd` is not automatically a win on the JVM backend, and the same class can behave very differently on two JVMs. Measure on the JVM you deploy on, with your own data.

There is another reason the JVM backend is hard to predict, and it has nothing to do with SIMD. A compiled Lisp numeric loop boxes every intermediate value -- one `Double` per array element read, per product, per running sum, plus a `Long` per loop counter -- so a scalar `vec:` kernel is bound by allocation and dispatch rather than by arithmetic. How much of that boxing a given JIT eliminates (through escape analysis and inlining) varies enormously between JVMs, so the very same scalar loop can be several times faster on one than on another. What `--simd` does here is sidestep the question: it replaces those kernels with primitive `double[]` / `float[]` loops that never box in the first place.
- **wasm-GC `--simd` native `v128`**: `rontolisp prog.lisp -o prog.wasm --simd` lowers the `vec:` kernels to WebAssembly fixed-width SIMD (`f64x2.*`, or `f32x4.*` for single-float). A packed float array becomes an `(array (mut v128))` of lane groups -- still an ordinary GC object, still reclaimed by the engine's collector, so memory behaves exactly as it does on scalar wasm-GC. The whole `vec:` API (including `vec:matvec` and `vec:from-list` / `vec:to-list`) keeps working, and the results are unchanged. Composes with `--component` and every `--optimize` level. Run it with `wasmtime run -W gc` as usual -- wasmtime enables the SIMD proposal by default.
- **`--no-gc --simd` native `v128`**: `rontolisp prog.lisp -o prog.wasm --no-gc --simd` lowers the same kernels over the packed linear-memory block. **Without `--simd`, `--no-gc` emits plain scalar loops** over the byte-identical block -- a v128-free MVP module that runs on a WebAssembly runtime lacking the SIMD proposal, trading away the vectorized speedup for that portability. `vec:matvec` / `vec:matvec-into` run over a rank-2 packed matrix block (`[rows][cols][data]`, built by a rank-2 `make-array`): the per-row dot is the `f64x2` / `f32x4` dot loop under `--simd` and the scalar loop without it. Only `vec:from-list` / `vec:to-list` (which need Lisp lists) remain unavailable on `--no-gc`; `vec:exp` / `vec:log` / `vec:tanh` / `vec:sin` / `vec:cos` / `vec:tan` / `vec:asin` / `vec:acos` / `vec:atan` / `vec:sinh` / `vec:cosh` / `vec:sign` have no vector instruction, so they run the same per-element loop in both modes, as does `vec:clip` (its bounds are full doubles, so each element is compared widened); `vec:maximum` / `vec:minimum` / `vec:relu` vectorize as comparison-mask selects under `--simd` and fall back to scalar compare-and-select loops without it.

On wasm-GC the speedup is large because `--simd` replaces two things at once: the boxing-heavy scalar `vec.lisp` defun *and* the one-element-at-a-time loop. A `vec:dot` over an 8192-element vector, 20000 iterations, runs in ~10.1 s scalar and ~0.10 s with `--simd` under `wasmtime run -W gc`.

Reading a lane group out of a GC array costs a bounds check that a `v128.load` from linear memory does not, and no engine hoists it out of the loop, so the same kernel loop is about 1.9x slower on wasm-GC `--simd` than on `--no-gc --simd`. That is the price of letting the collector own your vectors. If a numeric inner loop is your bottleneck and you can live without a garbage collector, compile it with `--no-gc --simd`.

Because reductions sum in a different order under SIMD, a reduction over inexact inputs can differ from the left-to-right scalar reference in the last ULP; over the exact doubles typical of tests the results match exactly. The element-wise kernels are always bit-identical.

Single-float reductions carry one more caveat. Under `--simd`, an `#f` reduction -- `vec:dot` / `vec:sum` / `vec:matvec` -- accumulates in single precision, in four lanes, on every backend, and widens only the final value. The scalar reference instead reads each element as a double and accumulates in double. So over data that a single-precision accumulator cannot hold, `--simd` can move an `#f` reduction by roughly the single-float epsilon rather than by the last ULP. Every `--simd` backend accumulates the same way, so they agree with one another, and the scalar reference remains the more accurate of the two. `#d` (`double-float`) reductions are unaffected. If a single-float reduction has to be as accurate as the scalar reference, use `#d` for it -- or leave `--simd` off for that computation.

## Accelerating linalg

The [`linalg` package](linear-algebra.md) is written over the same packed float arrays, and `--simd` routes thirty-six of its functions to the same kernels:

- **accelerated directly**: `add`, `sub`, `mul`, `div`, `sum`, `norm`, `amax`, `amin`, `argmax`, `argmin`, `trace`, `transpose`, `reshape`, `dot`, `outer`, the unary ufuncs `exp`, `log`, `tanh`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `sqrt`, `abs`, `negative`, `sign`, `erf` (an `emap` over a scalar series in the portable definition, and intercepted as a member of its own because `emap` never is -- it is what the exact [`torch:gelu`](../reference/functions/torch-gelu.md) is built from), the comparison selects `maximum`, `minimum`, the two internal convolution helpers behind the Deep Learning from Scratch examples (`linalg::%la-im2col` and `linalg::%la-col2im`, the im2col window unfolding and its adjoint -- index-arithmetic loops rather than lane kernels, bit-identical at both widths), and the internal stacked matrix product behind `matmul` at rank 3 or more (`linalg::%la-matmul-nd`, which is `torch.bmm` and therefore every attention layer: one matrix-product kernel per batch)
- **accelerated with them**: `mean`, `matmul`, `flatten`, `solve`, `square`, `reciprocal`, `clip` and `relu`, each of which is written in terms of the functions above
- **never accelerated**: `emap` (it applies an arbitrary function to each element), `det`, `inv`, `array-equal` and the constructors

There is no separate flag and nothing to opt into per function: compile or run with `--simd` and the calls are routed, exactly as they are for `vec`.

```lisp
(linalg:norm (linalg:sub #d(4.0 6.0) #d(1.0 2.0)))  ; => 5.0
(linalg:emap #'sqrt #d(1.0 4.0 9.0))                ; => #d(1.0 2.0 3.0)
```

Where `vec` insists on packed arrays of one width, `linalg` accepts far more: general boxed arrays such as `#(1 2 3)`, two operands of different widths, plain numbers, arrays of different shapes (broadcast by the numpy rules), and shapes that fit no broadcast (which signal an error). A kernel handles the packed, same-width cases: equal shapes, a scalar operand on either side, two arrays of different-but-broadcastable shapes (the numpy broadcast itself), `transpose`'s axes form (`(linalg:transpose x '(0 3 1 2))`), and the `:axis` forms of `sum` / `amax` / `amin` / `argmax` / `argmin` -- an integer axis, negative axes included, with or without `:keepdims`. **For everything else the portable `linalg.lisp` definition runs instead**, over the very same argument values -- same result, same broadcast, same error message, each argument form still evaluated exactly once. So `--simd` never changes what a linalg program accepts or rejects; it only makes the common case faster.

The precision rules above carry over, with one exception in linalg's favor:

- **Element-wise operations are bit-identical** to the portable definitions at both widths -- `add` / `sub` / `mul` / `div`, whether against another array, against a scalar or across a numpy broadcast, the unary ufuncs `exp` / `log` / `tanh` / `sin` / `cos` / `tan` / `asin` / `acos` / `atan` / `sinh` / `cosh` / `sqrt` / `abs` / `square` / `negative` / `sign` / `reciprocal` / `erf`, and the comparison selects `maximum` / `minimum` / `clip` / `relu` (a select only copies input bits, so it cannot round) -- and so are `transpose` (the axes form included), `reshape`, `outer`, `trace`, `amax`, `amin`, `argmax` and `argmin`.
- **Reductions over the whole array follow the `vec` rule**: `sum`, `mean`, `norm` and the vector and matrix-vector forms of `dot` sum in a different order, and over a single-float array they accumulate in single precision.
- **Reductions along an axis are bit-identical.** `(linalg:sum a :axis 0)`, `(linalg:amax a :axis 1)` and the other axis forms fold exactly as the portable definitions do -- in double, in the same order, with the same tie rules -- so unlike the whole-array reductions they cannot differ.
- **The full matrix product follows the `vec` rule too.** `(linalg:dot A B)` and `linalg:matmul` -- over two matrices, and over the stacked rank-3-and-up shape, which folds each batch exactly as `dot` would -- accumulate in the width of their operands: over `#d` they stay bit-identical to the portable definition, and over `#f` they fold each output cell in single precision -- in the portable definition's own order, but rounding at every step, so the two can differ. This is the ordinary behavior of a single-precision matrix multiply; every mainstream library does the same. It is also what makes `#f` faster than `#d` here rather than twice as slow, because a single-precision accumulator is what lets the kernel run single-precision lanes at all. Which lanes ran cannot move the result -- the lanes go across the output row, not along the axis being summed -- so all three `--simd` backends agree with one another exactly. If a single-float matrix product has to match the portable definition, use `#d` for it, or leave `--simd` off for that computation.

`linalg` does not compile under `--no-gc` at all, with or without `--simd`. The `--no-gc` row of the target table above therefore concerns `vec` only.

## Accelerating the matrix product with a tuned BLAS (`--blas`)

`--simd` gives the matrix product a hand-written lane kernel. Every desktop and server operating system can do far better than that, because a **tuned BLAS** -- a library whose matrix multiply is blocked for the machine's cache hierarchy and written against its matrix instructions -- is either already in the OS or one package away. `--blas` finds one and routes `linalg`'s matrix product to it. It is a second acceleration flag, orthogonal to `--simd`: either, both or neither.

```bash
rontolisp prog.lisp --blas                  # interpreter
rontolisp prog.lisp -o Prog.class --blas    # JVM
```

**A tuned BLAS is recommended, never required.** Nothing is bundled and nothing is downloaded. A machine without one runs the same programs to the same output, only slower, and the interpreter says so on standard error rather than failing.

- **macOS**: nothing to install. `Accelerate.framework` is part of the system, and `--blas` finds it.
- **Linux**: install one, for example `sudo apt install libopenblas0-pthread` (Debian / Ubuntu) or `sudo dnf install openblas` (Fedora / RHEL). NVIDIA NVPL, Intel MKL, BLIS, ATLAS and Arm Performance Libraries are recognized too.
- **Windows / anything else**: name the library yourself with `RONTOLISP_BLAS`, or run without the flag.

What it is worth, for one `#d` matrix product (Apple M4 Max, macOS, Accelerate; your machine and library will differ, so measure):

| n x n | portable definition | `--simd` | `--blas` |
|---|---|---|---|
| 128 | 1150 ms | 0.55 ms | 0.04 ms |
| 512 | -- | 21 ms | 0.4 ms |
| 1024 | -- | 180 ms | 3.1 ms |

On Linux the same measurement against OpenBLAS 0.3.26 on a 20-core Arm machine gives 20x `--simd` across all cores, and 5.2x pinned to one thread.

### What is accelerated, and what declines

The matrix product and nothing else: `linalg:dot` for matrix-by-matrix, matrix-by-vector and vector-by-matrix, and therefore `linalg:matmul` at rank <= 2 and `linalg:solve`, which are written over it. That is where the whole win is; the memory-bound members (`sum`, a vector-by-vector `dot`, element-wise arithmetic) would gain nothing from a library call, and `--simd` already covers them.

Everything else **declines** and runs exactly what it ran before -- the `--simd` kernel when that flag is on too, and the portable `linalg.lisp` definition otherwise. That includes general boxed arrays, mixed widths, a scalar operand, the batched rank-3 product, a shape mismatch (which signals the same error), and any product too small to pay for a library call. So `--blas` never changes what a program accepts or rejects.

### Reach, threads and precision

`--blas` reaches the **interpreter** (including the native binary) and the **JVM class output**. A tuned BLAS is called through the foreign function API, which WASM does not have, so `--blas` with a `.wasm` output is an error rather than a silent no-op. A compiled class calls a restricted method, so run it as `java --enable-native-access=ALL-UNNAMED Prog` to keep the JVM's warning off standard error.

A tuned BLAS is **multi-threaded**, which nothing else in rontolisp is: a single `linalg:matmul` may occupy every core of the machine. That is most of the Linux figure above. Cap it with the library's own environment variable -- `OPENBLAS_NUM_THREADS`, `MKL_NUM_THREADS`, or `VECLIB_MAXIMUM_THREADS` for Accelerate -- when a program shares the machine.

The library blocks and reorders its reduction, so **an accelerated product is close to the portable definition rather than equal to it**, at `linalg`'s default `#d` width. Over exact inputs (integers, powers of two) the results still match exactly; over inexact ones they differ in the last few ulps. This flag is the one acceleration in rontolisp whose numerical answer depends on **which library and which version is installed on the machine**, which is exactly why it is its own flag: an existing `--simd` build computes what it always computed.

### Which library was bound

A library being present does not make it tuned: the netlib **reference** implementation exports the same symbols and is slower than the kernel rontolisp already has, and on Debian `libblas.so.3` is an alternatives symlink that points at either one. `--blas` therefore accepts a candidate only when it identifies itself as a tuned implementation, and declines otherwise -- being slower than the unaccelerated build is the one thing this feature must never do.

```bash
RONTOLISP_BLAS_VERBOSE=1 rontolisp prog.lisp --blas   # print what was bound, or why nothing was
RONTOLISP_BLAS=/path/to/libopenblas.so.0 rontolisp prog.lisp --blas   # name one outright
```

`RONTOLISP_BLAS` skips both the search and the identification check, so it is also the way to use a tuned build this list cannot name. Both variables are read by a compiled class too, which is how you check a `.class` on the machine that runs it.

## Accelerating the matrix product and the transcendentals on a GPU (`--gpu`)

`--blas` puts the matrix product on the fastest thing the CPU has. `--gpu` puts it on a different machine altogether: an NVIDIA device, driven straight through the CUDA driver. It is a third acceleration flag, orthogonal to the other two -- any combination of the three, or none.

```bash
rontolisp prog.lisp --gpu                 # interpreter
rontolisp prog.lisp -o Prog.class --gpu   # JVM class output
rontolisp prog.lisp --simd --blas --gpu   # all three, chained; the device is asked first
```

**A GPU is recommended, never required**, exactly as a tuned BLAS is. Nothing is bundled and nothing is downloaded, and there is no CUDA toolkit to install: `libcuda.so.1`, which ships with the NVIDIA driver, is the entire runtime requirement, and the kernels travel inside rontolisp as a text that the driver compiles for whatever card it finds. A machine with no device, no driver, or a card older than Turing (compute capability 7.5) runs the same programs to the same output, only slower, and the interpreter says so on standard error rather than failing.

### What is accelerated, and what declines

**The matrix product, in both of its shapes.** `linalg:dot` over two rank-2 arrays -- and therefore `linalg:matmul` at rank 2 and `linalg:solve`, which are written over it. And the **stacked product** behind `linalg:matmul` at rank 3 or more, which is `torch.bmm`: every attention layer, and every `torch:linear` over a `(B T C)` activation. A stack costs one round trip and one launch however many matrices are in it, because the device carries the batch on an axis of its own; an operand that broadcasts over the batch -- the rank-2 weight matrix under a rank-3 activation -- is copied to the device once rather than once per matrix.

**And the twelve element-wise transcendentals**: `exp`, `log`, `tanh`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh` and [`erf`](../reference/functions/linalg-erf.md) -- so `torch:gelu`, `torch:softmax` and `torch:log-softmax`, which are written over them, reach the device too. These are the members with the highest ratio in the whole flag, not the matrix product: `linalg:erf` over 1.5 M double-floats is 103 ms on a SIMD CPU and 0.9 ms on the device.

**And ten more members, each at ONE call shape.** `add`, `sub`, `mul`, `div`, `maximum` and `minimum` when their two operands have DIFFERENT shapes and numpy broadcasts them -- `(4 256 256)` against `(4 256 1)`, an array against its own per-row reduction, which is what `torch:softmax` and `torch:layer-norm` are built from; `sum`, `amax` and `amin` in their `:axis` form; and `transpose` with an axes list. `mean`, `var`, `std`, `linalg:softmax` and `linalg:log-softmax` reach the device through those, exactly as they reach the lane kernels on the CPU. What these shapes have in common is not their arithmetic: it is that the CPU walks them one element at a time with an index odometer rather than in vector lanes, so the CPU cost they have to beat is five to eight times the cost of the same operation on two equally shaped arrays. Measured on the JVM class output at a transformer's own shapes, single-float: a broadcast `sub` over 393216 elements is 660 us on the CPU and 118 on the device, `sum :axis` is 297 against 70, an axes `transpose` 335 against 75, and a whole `linalg:softmax` -- five of these members chained, five round trips -- 1915 against 402.

**The same names at an EQUAL shape are refused, and refused by measurement.** `sqrt`, `abs`, `negative` and `sign` stay on the CPU at every size, and so do `add`, `sub`, `mul` and `div` whenever both operands have the same shape. There the CPU runs a vector lane loop, so its cost is already just the cost of walking the array -- and a device has to walk it twice, over a link slower than memory, before it can start. Measured over 1.5 M elements, `linalg:sqrt` is 700 us on the CPU against 502 on the device (and 500 against 245 at single width, the flag's best case for it), while `linalg:add` is 900 us against 780 -- and at single width the CPU **wins**, 350 us against 382. A same-shaped `sub` over 393216 single-floats is 85 us on the CPU against 112 on the device. A member that wins by less than the measurement's own noise is not a member.

Everything else declines and runs exactly what it ran before -- the tuned library when `--blas` is on too, the lane kernel when `--simd` is, the portable `linalg.lisp` definition otherwise. That includes the two matrix-by-vector shapes `--blas` does take (they are memory-bound, so the trip cannot pay for itself), a rank-1 operand on either side of a stacked product, a batch shape whose slabs no single stride can reach (a broadcast axis sitting under a non-broadcast one), general boxed arrays, mixed widths, a scalar operand, and a shape mismatch, which signals the same error as ever.

It also includes **everything small**, and there are two thresholds because there are two kinds of work. A round trip to a device costs about 15 microseconds however little data rides on it, so a product below roughly 51x51x51 (`n * m * p` under 131072) declines and stays on the CPU; for a stack the same threshold applies to the TOTAL work, `batch * n * m * p`, because the round trip is paid once for the whole stack rather than once per matrix. An element-wise call is measured in **elements** instead -- one library call each -- and declines below **16384** of them; a broadcast or an axes transpose declines below **32768** result elements, and an axis fold below **131072** input elements or 256 output slices (a fold with one output slice is a single-threaded loop on a device, and loses to any CPU). Every threshold is one more decline rather than a mechanism of its own, which is why every example in this repository, all of which run shapes far below them, prints byte-identical output with the flag and without it.

### How the three flags compose

Each flag adds one attempt in front of the others, and every attempt that declines hands the same arguments to the next:

```text
--gpu --blas --simd   ->   device -> library gemm -> lane kernel -> portable definition
--gpu --simd          ->   device ->                 lane kernel -> portable definition
--gpu                 ->   device ->                                portable definition
```

`--blas` takes only the rank-2 product, so a stacked one -- or an element-wise call -- has no library rung at all: `--gpu --blas --simd` chains those device -> lane kernel -> portable definition. The device is asked first because its size threshold is three orders of magnitude above the tuned library's: it turns down everything small before touching the driver at all, and from about n=256 up it is ahead of a threaded CPU BLAS at both widths. So what the device declines lands on the fastest CPU path the invocation asked for, never back on the portable definition. The exception is a narrow band just above the threshold -- roughly n=64 to n=96 -- where `--gpu --blas` together accept a product the library alone would have finished sooner. Both sides are far under a millisecond there, and asking the library first instead would give away the several-fold win at the sizes the flag exists for.

### Reach and precision

`--gpu` reaches the **interpreter** (including the native binary) and the **JVM class output**. The CUDA driver is called through the foreign function API, which WASM does not have, so `--gpu` with a `.wasm` output is an error rather than a silent no-op; a WASM program has `--simd`.

A class compiled with `--gpu` is still standalone -- the whole CUDA binding travels inside it, so there is nothing to put on the classpath and `java Prog` is the whole command. It does call a restricted method, so run it as `java --enable-native-access=ALL-UNNAMED Prog` to keep the JVM's warning off standard error. In the native **binary** each device call currently costs 20 to 50 times more than on the JVM (one n=512 double-float product measured 17.4 ms against 0.74), enough that on that build `--gpu --blas` is slower than `--blas` alone at every size measured; `--gpu` still beats `--simd` there by more than 2x, and the portable definition by four orders of magnitude. Compiling the program to a class is the way around that cost -- the class the native binary emits is the one `java -jar` emits, and it runs at the speeds in the second table below.

**`--gpu` is the first flag whose results you should not expect to match the other backends digit for digit.** Two separate reasons, and the second is the new one:

- An accelerated **product** is close to the portable definition rather than equal to it. The device kernel folds each output cell in the portable definition's own order, but it fuses every multiply and add into a single instruction, so each term is rounded once where the portable definition rounds twice. Over inputs that are exact at the operand width (integers, powers of two) that cannot show and the results match exactly; over inexact ones they differ -- measured on an NVIDIA GB10 over operands of magnitude 1, by up to 5e-15 at `#d` and 3e-6 at `#f`.
- An accelerated **transcendental** has no such exempt class of inputs, because the device carries its own implementation of `exp`, `erf` and the rest. Two correct libraries disagree in their last digits and neither is wrong. At `#f` there is a second cause on top: the device evaluates at the operand width, where every CPU kernel here evaluates in double and narrows only on the store. Measured across each member's own range on the same machine, the worst relative difference from the portable definition is **2e-16 to 1e-15 at `#d`** (one to five units in the last place) and **1.1e-7 to 1.7e-7 at `#f`** (one to two). `erf` is the largest at `#d`, and that is on rontolisp's side rather than the device's: the portable definition is a series expansion, not a correctly rounded `erf`. One difference is visible rather than microscopic: an accelerated `erf` of a negative zero prints `-0.0` where the portable definition prints `0.0`.

- The **broadcast**, **axis fold** and **axes transpose** members are the exception: they stay byte-for-byte identical to the portable definition at both widths. Their kernels read every element widened to double, compute in double and narrow only on the store, which is the portable definition's own rule, and there is no library function anywhere in them for two implementations to disagree about. A program whose accelerated calls are only those prints exactly what it prints without the flag.

So a program that sums a million accelerated `erf` values will print a slightly different number with the flag on -- and a training run will diverge from the CPU one after enough steps, exactly as it would between two GPUs. The portable definition remains the cross-backend oracle, and `--gpu` is deliberately absent from the cross-backend test suite. If you need identity, do not pass the flag; if you want to check that a program is unchanged in every other respect, run it with `CUDA_VISIBLE_DEVICES=` set, which makes every device call decline and the output byte-identical.

### What it is worth

One `n x n` `linalg:matmul` on the interpreter, microseconds per call, warm. The machine is an NVIDIA GB10 (Grace Blackwell, 20 CPU cores), and the `--blas` column is the best this machine has: OpenBLAS across all twenty of them. Your device, driver and library will all differ, so measure.

| n x n | `--simd` f64 | `--blas` f64 | `--gpu` f64 | `--simd` f32 | `--blas` f32 | `--gpu` f32 |
|---|---|---|---|---|---|---|
| 64 | 46 | 21 | 139 | 27 | 11 | 42 |
| 128 | 359 | 42 | 53 | 195 | 26 | 36 |
| 256 | 2647 | 164 | 156 | 1453 | 85 | 71 |
| 512 | 20267 | 1160 | 735 | 10567 | 510 | 215 |
| 1024 | -- | 6450 | 5150 | -- | 3083 | 1183 |
| 2048 | -- | 89200 | 38000 | -- | 44600 | 8067 |

Read it in two directions. Against the lane kernel the device is 7x at n=128 and 28x at n=512, and 49x at n=512 in single float -- a different order of magnitude, which is the point of the flag. Against a tuned BLAS on twenty cores it is a wash until about n=256 and then 1.6x to 2.3x at double width, 2.4x to 5.5x at single: double-float is the width this class of device is worst at, so **`--gpu` pays most for `single-float` data**, which is what `torch:` builds by default.

The same products compiled to a `.class` and run on the JVM, best of three timed rounds after 400 warm-up calls:

| n x n | `--simd` f64 | `--blas` f64 | `--gpu` f64 | `--simd` f32 | `--blas` f32 | `--gpu` f32 |
|---|---|---|---|---|---|---|
| 64 | 50 | 17 | 107 | 32 | 8 | 106 |
| 128 | 345 | 30 | 50 | 206 | 34 | 34 |
| 256 | 2613 | 170 | 145 | 1380 | 95 | 65 |
| 512 | 20760 | 1140 | 740 | 10480 | 530 | 210 |
| 1024 | -- | 6933 | 5367 | -- | 4433 | 2233 |
| 2048 | -- | 91750 | 39000 | -- | 44625 | 8375 |

It is the same table, which is the point: once the product is one device call, the backend around it no longer matters. Warm carefully before you compare anything near the threshold -- at n=64 and n=128 the device drops back to its idle clock between calls, and a single cold round there can measure several times these figures.

And the stacked product, which is the shape a transformer is made of: one `linalg:matmul` of `batch` `n x n` slabs, microseconds per call, interpreter, same machine and same warm-up. `--blas` has no column here because it does not take this member.

| batch x n | `--simd` f64 | `--gpu` f64 | `--simd` f32 | `--gpu` f32 |
|---|---|---|---|---|
| 256 x 8 | 60 | 48 | 46 | 30 |
| 64 x 16 | 75 | 43 | 71 | 29 |
| 16 x 32 | 110 | 45 | 69 | 29 |
| 4 x 64 | 176 | 49 | 101 | 31 |
| 16 x 64 | 710 | 86 | 400 | 56 |
| 16 x 128 | 5580 | 300 | 3040 | 130 |
| 12 x 256 | 31740 | 1240 | 16660 | 380 |

The batch is what the device is for: the CPU pays for every matrix in the stack while the round trip is paid once, so the ratio grows with the batch as much as with the matrix -- 1.25x at the threshold, 26x at 12 x 256 double-float and 44x single.

The element-wise members, on the JVM class output: one `linalg:` call over 1.5 M elements -- the feed-forward activation of the transformer below -- microseconds per call, best of five timed rounds after 30 warm-up calls.

| 1.5 M elements | `--simd` f64 | `--gpu` f64 | `--simd` f32 | `--gpu` f32 |
|---|---|---|---|---|
| `exp` | 7300 | 833 | 7933 | 333 |
| `log` | 7267 | 800 | 7800 | 333 |
| `tanh` | 9533 | 767 | 9733 | 333 |
| `erf` | 103400 | 900 | 101233 | 333 |
| `sin` | 7667 | 733 | 9233 | 333 |

Nine to twelve times at double width, twenty-three to twenty-nine at single, and **115x for `erf`** -- the member the CPU is slowest at, and the one the exact `torch:gelu` is written over. The device column is flat because at this size it is the copy and not the arithmetic: every member costs what 12 MB up and 12 MB back costs, which is also why single float is worth twice double float here rather than the fraction the arithmetic would suggest. The refused members are not in the table because the flag does not change what runs for them; the numbers that refused them are in the section above. Measure each width in a process of its own if you repeat this -- on the CPU the second width measured through the same call site is 1.5x to 2x slower than the first, which is a JIT artifact and not a property of the width.

And the members whose CPU twin is an index odometer rather than a lane loop, on the JVM class output, at a transformer's own shapes: microseconds per call, best of three timed rounds after 50 warm-up calls.

| single-float, per call | `--simd` | `--gpu --simd` |
|---|---|---|
| `sub`, (4 256 256) against (4 256 1) | 442 | 88 |
| `sub`, (4 256 384) against (4 256 1) | 660 | 118 |
| `mul`, (4 256 384) against (384) | 665 | 115 |
| `sum :axis 2`, (4 256 256) | 202 | 75 |
| `sum :axis 0`, (4 256 384) | 297 | 70 |
| `var :axis 2`, (4 256 384) | 1387 | 475 |
| `transpose '(0 2 1)`, (4 256 192) | 335 | 75 |
| `softmax :axis -1`, (4 256 256) | 1915 | 402 |
| `sub`, (4 256 384) against (4 256 384) | 85 | 85 |

Three to six times. The last row is the same operation at an equal shape: the flag refuses it, so both columns are the CPU running the same lane loop -- offering it to the device instead measures 112 us, which is why it is refused. That contrast is the whole selection rule for this group.

End to end, `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the notebook's own shapes (`*n-embd*` 384, `*block-size*` 256, which the file says is a one-line change) runs a training step **three to four times faster on the JVM class output** with the flag on -- 0.89 s against 0.21 s, from a 5-step and a 40-step run so that setup and sampling fall out of the slope, best of seven interleaved runs each. Quote the ratio rather than the digits: the same program varies by about 15% run to run on this machine.

**What is left of that step is no longer `linalg` at all**, which is worth knowing before you reach for another flag. An execution profile of the accelerated step spends about a third of it in the AdamW parameter update and another seventh in the dropout random numbers -- both per-element loops written in Lisp inside `torch:`, on no acceleration seam. The `linalg` kernels this section is about are around a tenth of it. On the **interpreter** the same program still shows no change at all -- 26.1 s per step against 25.5 -- and the reason is not the device: an interpreted step is 32 times a compiled one at the same shapes, so what dominates it is the tree walk around the kernels rather than the kernels. Compile the program before you measure a flag.

## Runnable examples

[`examples/ml/blas-matmul.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/blas-matmul.lisp) is the one example both acceleration flags reach, because it is one `linalg:matmul` at linalg's default `double-float` width and nothing else. Its entries are small integers, so every product and every sum is exact and no reordering -- lanes or library blocking -- can move a printed digit. Run it up to four ways:

```bash
rontolisp examples/ml/blas-matmul.lisp
rontolisp examples/ml/blas-matmul.lisp --simd
rontolisp examples/ml/blas-matmul.lisp --blas
rontolisp examples/ml/blas-matmul.lisp --simd --blas
```

Per 128x128 product on an Apple M4 Max: the interpreter goes 1848 ms -> 0.62 ms with `--simd` -> 0.034 ms with `--blas`, and the JVM 0.37 ms -> 0.043 ms. Compiled to wasm-GC, where there is no foreign function API and so no `--blas`, it goes 60 ms -> 1.4 ms with `--simd`. Raise `*reps*` in the source to time the accelerated runs: one product finishes well inside the millisecond tick the clock can see.

The `vec:` examples below are not affected by `--blas` at all: it intercepts the `linalg:` matrix product, which no `vec:` program calls.

The smallest of them is [`examples/ml/simd-dot.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-dot.lisp): one `vec:dot` over 1024 doubles, four thousand times, and nothing else. Its vector holds `0.0 .. 1023.0`, so the answer is an exact integer that no amount of lane reordering can change -- run it with and without `--simd` and only the elapsed time moves (interpreter 2.59 s -> 2.3 ms; wasm-GC 273 ms -> 2.4 ms).

[`examples/ml/simd-gemv.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv.lisp) does nothing but the two kernels acceleration exists for -- `vec:matvec` and `vec:dot` -- a hundred times over: project a vector through a 256x256 single-float matrix, rescale it to unit root-mean-square, repeat. That pair is a transformer's projection plus its RMSNorm, and it is where an LLM inference engine spends nearly all of its time. Run it twice:

```bash
rontolisp examples/ml/simd-gemv.lisp
rontolisp examples/ml/simd-gemv.lisp --simd
```

It prints integer `argmax` indices rather than floats, so the output is identical with and without acceleration -- only the elapsed time changes. On an Apple M4: wasm-GC 467 ms -> 3.9 ms, the interpreter 4.6 s -> 15 ms.

[`examples/ml/simd-gemv-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv-nogc.lisp) is the same inner loop compiled with `--no-gc`: a pure-compute reactor module whose host invokes the exported `fingerprint` function and reads back the `argmax` integer. Build it with and without `--simd` and invoke both:

```bash
rontolisp examples/ml/simd-gemv-nogc.lisp -o gemv.wasm --no-gc --simd
wasmtime run --invoke fingerprint gemv.wasm 100
```

Both builds print `85` -- the same dominant direction as every other backend -- and the `-into` kernels keep the never-freed `--no-gc` bump heap at exactly three blocks however many steps run. At 20000 steps the scalar module takes ~600 ms and the `--simd` one ~120 ms.

The whole engine is [`examples/llama2/llama2.lisp`](https://github.com/making/rontolisp/blob/develop/examples/llama2/llama2.lisp): llama2.c's `run.c` ported to one file -- checkpoint loader, tokenizer, forward pass, sampler -- over the real TinyStories checkpoints, telling the same stories as the C program token for token. Its 15 million weights load through `read-sequence` over packed single-float arrays, and its decode is over a hundred `vec:matvec`s per token; on stories15M, `--simd` takes the JVM from 23 to 87 tokens/s and wasm-GC from 0.4 to 46 (`run.c -O2`: 65). See [its README](https://github.com/making/rontolisp/blob/develop/examples/llama2/README.md).

A row must hold at least 128 elements before the interpreter and JVM kernels vectorize it; below that they run the scalar loop, because filling the vector registers would cost more than it saves. The two WASM backends have no such threshold.

## Packages

`vec` does not use `cl`; every function is external, referenced as `vec:name`. Put `(in-package :vec)` (or `(defpackage ... (:use :vec))`) in effect to write the exported names unqualified. The related [`linalg` package](linear-algebra.md) offers a broader numpy-style API (shape manipulation, matrix products, exact linear algebra) over the same arrays.
