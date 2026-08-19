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

The scalar `vec.lisp` reference is correct on every backend. `--simd` is the single, backend-independent switch that additionally lowers the vectorizable kernels (`add` / `sub` / `mul` / `div` / `scale` / `dot` / `sum` / `matvec` and the four operator aliases, the unary ufuncs `exp` / `log` / `tanh` / `sin` / `cos` / `tan` / `asin` / `acos` / `atan` / `sinh` / `cosh` / `sqrt` / `abs` / `negative` / `sign` / `reciprocal`, the comparison selects `maximum` / `minimum` / `relu` / `clip`, and all their `-into` siblings, plus `mean` / `norm` / `square` transitively) to real CPU vector instructions or de-boxed loops. It is opt-in. The element-wise kernels stay byte-for-byte identical to the scalar reference; the reductions sum in a different order, and a single-float reduction also accumulates in single precision, so those can differ from it -- see the two paragraphs on precision below. The same flag accelerates a set of `linalg` functions, listed in the next section.

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

The [`linalg` package](linear-algebra.md) is written over the same packed float arrays, and `--simd` routes thirty-four of its functions to the same kernels:

- **accelerated directly**: `add`, `sub`, `mul`, `div`, `sum`, `norm`, `amax`, `amin`, `argmax`, `argmin`, `trace`, `transpose`, `reshape`, `dot`, `outer`, the unary ufuncs `exp`, `log`, `tanh`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `sqrt`, `abs`, `negative`, `sign`, the comparison selects `maximum`, `minimum`, and the two internal convolution helpers behind the Deep Learning from Scratch examples (`linalg::%la-im2col` and `linalg::%la-col2im`, the im2col window unfolding and its adjoint -- index-arithmetic loops rather than lane kernels, bit-identical at both widths)
- **accelerated with them**: `mean`, `matmul`, `flatten`, `solve`, `square`, `reciprocal`, `clip` and `relu`, each of which is written in terms of the functions above
- **never accelerated**: `emap` (it applies an arbitrary function to each element), `det`, `inv`, `array-equal` and the constructors

There is no separate flag and nothing to opt into per function: compile or run with `--simd` and the calls are routed, exactly as they are for `vec`.

```lisp
(linalg:norm (linalg:sub #d(4.0 6.0) #d(1.0 2.0)))  ; => 5.0
(linalg:emap #'sqrt #d(1.0 4.0 9.0))                ; => #d(1.0 2.0 3.0)
```

Where `vec` insists on packed arrays of one width, `linalg` accepts far more: general boxed arrays such as `#(1 2 3)`, two operands of different widths, plain numbers, arrays of different shapes (broadcast by the numpy rules), and shapes that fit no broadcast (which signal an error). A kernel handles the packed, same-width cases: equal shapes, a scalar operand on either side, two arrays of different-but-broadcastable shapes (the numpy broadcast itself), `transpose`'s axes form (`(linalg:transpose x '(0 3 1 2))`), and the `:axis` forms of `sum` / `amax` / `amin` / `argmax` / `argmin` -- an integer axis, negative axes included, with or without `:keepdims`. **For everything else the portable `linalg.lisp` definition runs instead**, over the very same argument values -- same result, same broadcast, same error message, each argument form still evaluated exactly once. So `--simd` never changes what a linalg program accepts or rejects; it only makes the common case faster.

The precision rules above carry over, with one exception in linalg's favor:

- **Element-wise operations are bit-identical** to the portable definitions at both widths -- `add` / `sub` / `mul` / `div`, whether against another array, against a scalar or across a numpy broadcast, the unary ufuncs `exp` / `log` / `tanh` / `sin` / `cos` / `tan` / `asin` / `acos` / `atan` / `sinh` / `cosh` / `sqrt` / `abs` / `square` / `negative` / `sign` / `reciprocal`, and the comparison selects `maximum` / `minimum` / `clip` / `relu` (a select only copies input bits, so it cannot round) -- and so are `transpose` (the axes form included), `reshape`, `outer`, `trace`, `amax`, `amin`, `argmax` and `argmin`.
- **Reductions over the whole array follow the `vec` rule**: `sum`, `mean`, `norm` and the vector and matrix-vector forms of `dot` sum in a different order, and over a single-float array they accumulate in single precision.
- **Reductions along an axis are bit-identical.** `(linalg:sum a :axis 0)`, `(linalg:amax a :axis 1)` and the other axis forms fold exactly as the portable definitions do -- in double, in the same order, with the same tie rules -- so unlike the whole-array reductions they cannot differ.
- **The full matrix product is exempt.** `(linalg:dot A B)` and `linalg:matmul` over two matrices accumulate in double at both widths and stay bit-identical to the portable definition. The rule behind these lines is the same one: an accumulator narrows to the lane element type only when the lanes *are* the axis being summed, and in a matrix product they run across the output row instead.

`linalg` does not compile under `--no-gc` at all, with or without `--simd`. The `--no-gc` row of the target table above therefore concerns `vec` only.

## Runnable examples

The smallest one is [`examples/ml/simd-dot.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-dot.lisp): one `vec:dot` over 1024 doubles, four thousand times, and nothing else. Its vector holds `0.0 .. 1023.0`, so the answer is an exact integer that no amount of lane reordering can change -- run it with and without `--simd` and only the elapsed time moves (interpreter 2.59 s -> 2.3 ms; wasm-GC 273 ms -> 2.4 ms).

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
