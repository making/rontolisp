# Vector Kernels (vec)

The `vec` package provides portable packed-`f64` vector kernels: constructors, element access, element-wise arithmetic and reductions over the [packed float array type](../reference/data-types.md). It is the go-to package for tight numeric loops over vectors of doubles, and it carries an optional hardware-acceleration (SIMD) layer on every backend. The package names the portable abstraction; the `--simd` flag names how it is accelerated.

Like the JSON and `linalg` libraries, `vec` is implemented once in Lisp source (`vec.lisp`): the interpreter loads the definitions lazily on the first use of a `vec:` function, and the compile path splices them into the program when it references the package. This scalar definition is the implementation on the interpreter, the JVM compiler and the WASM (wasm-GC) backends, and the correctness oracle for the accelerated paths, so every function behaves identically everywhere.

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

Construction: `vec:zeros` / `vec:ones` build a filled vector of length *n*, `vec:arange` builds `[0.0, 1.0, ..., n-1]`, and `vec:from-list` / `vec:to-list` convert between a vector and a Lisp list (the list forms run on the interpreter, the JVM and wasm-GC only, not `--no-gc`). `vec:zeros` / `vec:ones` / `vec:arange` also take an optional trailing `element-type`: pass `'single-float` for a packed single-float (`#f`) vector (the default is double-float), mirroring the [linalg constructors](linear-algebra.md#single-float-precision) and honored on every backend including the JVM and WASM `--simd` v128 paths.

Access: `vec:aref` reads an element (a `setf` place via `vec:aset`), and `vec:length` returns the element count. These are thin wrappers over the generic packed-array operators, so plain `aref` / `length` work too.

Element-wise (a fresh vector): `vec:add`, `vec:sub`, `vec:mul` (Hadamard product) and `vec:scale` (multiply by a scalar).

Reductions (a scalar): `vec:sum`, `vec:dot`, `vec:mean` and `vec:norm` (the Euclidean norm, `sqrt` of the self-dot).

```lisp
(vec:sum (vec:arange 5))              ; => 10.0
(vec:mean #d(2.0 4.0 6.0))             ; => 4.0
(vec:norm #d(3.0 4.0))                 ; => 5.0
(vec:to-list (vec:mul #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))) ; => (4.0 10.0 18.0)
```

Matrix times vector (a fresh vector): `vec:matvec` is GEMV -- a rank-2 packed matrix `W` (shape *d* x *n*) times a rank-1 vector `x` of length *n*, giving a length-*d* vector whose *i*-th element is the dot product of row *i* of `W` with `x` (no transpose). It is the workhorse of a neural network's forward pass -- every projection, feed-forward and classifier layer is a `vec:matvec` -- so it is the one kernel run once per matrix row rather than element-wise. The result follows the input width.

```lisp
(vec:matvec #d((1.0 2.0) (3.0 4.0)) #d(5.0 6.0)) ; => #d(17.0 39.0)
```

The [`ml/nn-vec.lisp` example](https://github.com/making/rontolisp/blob/main/examples/ml/nn-vec.lisp) is a small XOR network whose single-float forward pass is built from `vec:matvec`.

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
| `(vec:scale v s)` | `(vec:scale-into out v s)` |
| `(vec:matvec w x)` | `(vec:matvec-into out w x)` |

The reductions (`vec:sum`, `vec:dot`, `vec:mean`, `vec:norm`) return a scalar and never allocated, so they have no sibling.

```lisp
(let ((acc (vec:zeros 3))
      (d #d(1.0 2.0 3.0)))
  (dotimes (i 3) (vec:add-into acc acc d))
  acc)                                   ; => #d(3.0 6.0 9.0)
```

In the element-wise kernels the destination **may alias** an operand: element *i* of the result depends only on element *i* of the inputs, so `(vec:add-into acc acc d)` above is a well-defined in-place accumulation. `vec:matvec-into` is the exception -- each output element folds over all of `x`, so writing into `x` would clobber a value a later row still has to read. Passing the same array as both `out` and `x` (or `w`) signals an error rather than corrupting it.

All operands must share an element type, and `out` must be at least as long as the inputs (its length is not checked, exactly as `vec:add` does not check its operands').

This is what makes `--no-gc` usable for real numeric loops (see the memory table above): with `-into`, peak memory equals the vectors you actually keep alive. Measured on `--no-gc --simd`, accumulating a 65536-element vector 12000 times peaks at 13.7 MB with `vec:add-into`, against 4.31 GB -- and then a trap -- with `vec:add`. On the three garbage-collected targets `-into` changes nothing about correctness; there it is an allocation-rate optimization.

`vec:matvec-into` is unavailable on `--no-gc`, like `vec:matvec`.

## Hardware acceleration (optional)

The scalar `vec.lisp` reference is correct on every backend. `--simd` is the single, backend-independent switch that additionally lowers the vectorizable kernels (`add` / `sub` / `mul` / `scale` / `dot` / `sum` / `matvec` and their `-into` siblings, plus `mean` / `norm` transitively) to real CPU vector instructions. It is opt-in and changes nothing about the results -- the accelerated output is byte-for-byte identical to the scalar reference (reductions may differ in the last ULP over inexact inputs; see below).

Which memory model you compile for (`.class`, wasm-GC `.wasm`, or `--no-gc` `.wasm`) and whether you pass `--simd` are **orthogonal** axes:

| target | without `--simd` | with `--simd` |
|---|---|---|
| interpreter (no `-o`) | scalar `vec.lisp` | `jdk.incubator.vector` (baked into the native binary; `java -jar` needs `--add-modules`) |
| JVM (`-o prog.class`) | scalar `vec.lisp` | `jdk.incubator.vector` bridge |
| wasm-GC (`-o prog.wasm`) | scalar `vec.lisp` | native v128 (`f64x2` / `f32x4`) |
| `--no-gc` (`-o prog.wasm --no-gc`) | scalar linear-memory loops | native v128 (`f64x2` / `f32x4`) |

- **Interpreter `--simd`**: `rontolisp prog.lisp --simd` runs the same seven kernels on `jdk.incubator.vector` instead of the scalar `vec.lisp` definitions -- no compilation step, and a large `vec:dot` gets several times faster. The native binary has the incubator module baked in and needs no runtime flag. On a plain `java -jar` the module is absent, so the flag falls back to the scalar reference and prints a note; re-run with `java --add-modules jdk.incubator.vector -jar rontolisp.jar prog.lisp --simd` to get the acceleration there. Without `--simd` the interpreter always runs the scalar reference -- it is the cross-backend oracle. The flag has no effect in the REPL.
- **JVM `--simd`**: `rontolisp prog.lisp -o Prog.class --simd` routes the kernels to an embedded `jdk.incubator.vector` bridge (a `DoubleVector` for `#d`, a `FloatVector` for `#f`; `vec:matvec` runs that vectorized dot once per matrix row). Running such a class requires the incubator module on the JVM: `java --add-modules jdk.incubator.vector Prog`. Without `--simd` the class runs the scalar reference on any JVM. **Whether the bridge becomes CPU vector instructions is up to the JVM that runs the class.** The Vector API is a normal library that a JVM may or may not compile down to vector instructions, operation by operation; where it does not, it falls back to emulating each lane, which is far slower than the plain scalar loop `--simd` replaced. So `--simd` is not automatically a win on the JVM backend, and the same class can behave very differently on two JVMs. Measure on the JVM you deploy on, with your own data.

One shape is worth knowing about, because it is ours rather than the JVM's. To keep an `#f` (`single-float`) reduction bit-identical to the scalar reference, `vec:dot` / `vec:sum` / `vec:matvec` widen every `f32` lane to `f64` before accumulating. That widening is an extra vector operation on the critical path, and it is the one most likely to be missing from a JVM's intrinsics. If you measure `--simd` making a single-float reduction slower, that is where to look: the same reduction over `#d` (`double-float`) contains no widening, and the element-wise `#f` kernels (`vec:add` and friends) contain none either.

There is a second reason the JVM backend is hard to predict, and it has nothing to do with SIMD. A compiled Lisp numeric loop boxes every intermediate value -- one `Double` per array element read, per product, per running sum, plus a `Long` per loop counter -- so a scalar `vec:` kernel is bound by allocation and dispatch rather than by arithmetic. How much of that boxing a given JIT eliminates (through escape analysis and inlining) varies enormously between JVMs, and on some of them the scalar path is already fast enough to beat the accelerated one. What `--simd` does here is sidestep the question: it replaces those kernels with primitive `double[]` / `float[]` loops that never box in the first place.
- **wasm-GC `--simd` native `v128`**: `rontolisp prog.lisp -o prog.wasm --simd` lowers the `vec:` kernels to WebAssembly fixed-width SIMD (`f64x2.*`, or `f32x4.*` for single-float). A packed float array becomes an `(array (mut v128))` of lane groups -- still an ordinary GC object, still reclaimed by the engine's collector, so memory behaves exactly as it does on scalar wasm-GC. The whole `vec:` API (including `vec:matvec` and `vec:from-list` / `vec:to-list`) keeps working, and the results are unchanged. Composes with `--component` and `--optimize`. Run it with `wasmtime run -W gc` as usual -- wasmtime enables the SIMD proposal by default.
- **`--no-gc --simd` native `v128`**: `rontolisp prog.lisp -o prog.wasm --no-gc --simd` lowers the same kernels over the packed linear-memory block. **Without `--simd`, `--no-gc` emits plain scalar loops** over the byte-identical block -- a v128-free MVP module that runs on a WebAssembly runtime lacking the SIMD proposal, trading away the vectorized speedup for that portability. `vec:from-list` / `vec:to-list` (which need Lisp lists) and `vec:matvec` / `vec:matvec-into` (which need a rank-2 matrix) are unavailable on `--no-gc` either way.

On wasm-GC the speedup is large because `--simd` replaces two things at once: the boxing-heavy scalar `vec.lisp` defun *and* the one-element-at-a-time loop. A `vec:dot` over an 8192-element vector, 20000 iterations, runs in ~10.1 s scalar and ~0.10 s with `--simd` under `wasmtime run -W gc`.

Reading a lane group out of a GC array costs a bounds check that a `v128.load` from linear memory does not, and no engine hoists it out of the loop, so the same kernel loop is about 1.9x slower on wasm-GC `--simd` than on `--no-gc --simd`. That is the price of letting the collector own your vectors. If a numeric inner loop is your bottleneck and you can live without a garbage collector, compile it with `--no-gc --simd`.

Because reductions sum in a different order under SIMD, a reduction over inexact inputs can differ from the left-to-right scalar reference in the last ULP; over the exact doubles typical of tests the results match exactly. The element-wise kernels are always bit-identical.

Single-float reductions carry one more caveat. The WASM `--simd` kernels accumulate an `#f` reduction in single precision and widen only the final result, while the interpreter and JVM kernels widen each element and accumulate in double, as the scalar reference does. So an `#f` `vec:dot` / `vec:sum` / `vec:matvec` over data that a single-precision accumulator cannot hold may differ between those backends by more than one ULP -- by roughly the single-float epsilon. If a single-float reduction has to agree across backends to the last bit, use `#d`.

## Runnable examples

The smallest one is [`examples/ml/simd-dot.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-dot.lisp): one `vec:dot` over 1024 doubles, four thousand times, and nothing else. Its vector holds `0.0 .. 1023.0`, so the answer is an exact integer that no amount of lane reordering can change -- run it with and without `--simd` and only the elapsed time moves (interpreter 2.59 s -> 2.3 ms; wasm-GC 273 ms -> 2.4 ms).

[`examples/ml/simd-gemv.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv.lisp) does nothing but the two kernels acceleration exists for -- `vec:matvec` and `vec:dot` -- a hundred times over: project a vector through a 256x256 single-float matrix, rescale it to unit root-mean-square, repeat. That pair is a transformer's projection plus its RMSNorm, and it is where an LLM inference engine spends nearly all of its time. Run it twice:

```bash
rontolisp examples/ml/simd-gemv.lisp
rontolisp examples/ml/simd-gemv.lisp --simd
```

It prints integer `argmax` indices rather than floats, so the output is identical with and without acceleration -- only the elapsed time changes. On an Apple M4: wasm-GC 467 ms -> 3.9 ms, the interpreter 4.67 s -> 0.68 s.

A row must hold at least 128 elements before the interpreter and JVM kernels vectorize it; below that they run the scalar loop, because filling the vector registers would cost more than it saves. The two WASM backends have no such threshold.

## Packages

`vec` does not use `cl`; every function is external, referenced as `vec:name`. Put `(in-package :vec)` (or `(defpackage ... (:use :vec))`) in effect to write the exported names unqualified. The related [`linalg` package](linear-algebra.md) offers a broader numpy-style API (shape manipulation, matrix products, exact linear algebra) over the same arrays.
