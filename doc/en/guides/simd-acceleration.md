# Vector Kernels (vec)

The `vec` package provides portable packed-`f64` vector kernels: constructors, element access, element-wise arithmetic and reductions over the [packed float array type](../reference/data-types.md). It is the go-to package for tight numeric loops over vectors of doubles, and it carries an optional hardware-acceleration (SIMD) layer on three of the four backends. The package names the portable abstraction; the `--simd` flag names how it is accelerated.

Like the JSON and `linalg` libraries, `vec` is implemented once in Lisp source (`vec.lisp`): the interpreter loads the definitions lazily on the first use of a `vec:` function, and the compile path splices them into the program when it references the package. This scalar definition is the implementation on the interpreter, the JVM compiler and the WASM (wasm-GC) backends, and the correctness oracle for the accelerated paths, so every function behaves identically everywhere.

## Data representation

A vector is a rank-1 [packed float array](../reference/data-types.md): the `double-float`-typed, unboxed array that `#d(...)` and `(make-array n :element-type 'double-float)` produce. The built-in `aref` / `length` interoperate with it, and any packed vector built elsewhere can be handed to a `vec` function. Element-wise kernels return a fresh vector; reductions return a scalar `double`.

The kernels are width-polymorphic: they also accept single-float vectors (`#f(...)` / `:element-type 'single-float`, which store elements as `f32` -- half the memory, twice the SIMD lanes). The element-wise kernels preserve the input width on the interpreter and JVM (a `#f` in gives a `#f` out), while the reductions always fold to a scalar `double`.

```lisp
(vec:arange 5)                         ; => #d(0.0 1.0 2.0 3.0 4.0)
(vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => #d(5.0 7.0 9.0)
(vec:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => 32.0
(vec:scale #d(1.0 2.0 3.0) 10)         ; => #d(10.0 20.0 30.0)
```

## The API

Construction: `vec:zeros` / `vec:ones` build a filled vector of length *n*, `vec:arange` builds `[0.0, 1.0, ..., n-1]`, and `vec:from-list` / `vec:to-list` convert between a vector and a Lisp list (the list forms run on the interpreter, the JVM and wasm-GC only, not `--no-gc`). `vec:zeros` / `vec:ones` / `vec:arange` also take an optional trailing `element-type`: pass `'single-float` for a packed single-float (`#f`) vector (the default is double-float), mirroring the [linalg constructors](linear-algebra.md#single-float-precision) and honored on every backend including the JVM `--simd` and the `--no-gc` v128 paths.

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

## Hardware acceleration (optional)

The scalar `vec.lisp` reference is correct on every backend. `--simd` is the single, backend-independent switch that additionally lowers the vectorizable kernels (`add` / `sub` / `mul` / `scale` / `dot` / `sum` / `matvec`, and `mean` / `norm` transitively) to real CPU vector instructions. It is opt-in and changes nothing about the results -- the accelerated output is byte-for-byte identical to the scalar reference (reductions may differ in the last ULP over inexact inputs; see below).

Which memory model you compile for (`.class`, wasm-GC `.wasm`, or `--no-gc` `.wasm`) and whether you pass `--simd` are **orthogonal** axes:

| target | without `--simd` | with `--simd` |
|---|---|---|
| interpreter (no `-o`) | scalar `vec.lisp` | `jdk.incubator.vector` (baked into the native binary; `java -jar` needs `--add-modules`) |
| JVM (`-o prog.class`) | scalar `vec.lisp` | `jdk.incubator.vector` bridge |
| wasm-GC (`-o prog.wasm`) | scalar `vec.lisp` | scalar `vec.lisp` (not supported yet; a warning is printed) |
| `--no-gc` (`-o prog.wasm --no-gc`) | scalar linear-memory loops | native v128 (`f64x2` / `f32x4`) |

- **Interpreter `--simd`**: `rontolisp prog.lisp --simd` runs the same seven kernels on `jdk.incubator.vector` instead of the scalar `vec.lisp` definitions -- no compilation step, and a large `vec:dot` gets several times faster. The native binary has the incubator module baked in and needs no runtime flag. On a plain `java -jar` the module is absent, so the flag falls back to the scalar reference and prints a note; re-run with `java --add-modules jdk.incubator.vector -jar rontolisp.jar prog.lisp --simd` to get the acceleration there. Without `--simd` the interpreter always runs the scalar reference -- it is the cross-backend oracle. The flag has no effect in the REPL.
- **JVM `--simd`**: `rontolisp prog.lisp -o Prog.class --simd` routes the kernels to an embedded `jdk.incubator.vector` bridge that the JIT intrinsifies to CPU vector instructions (a `DoubleVector` for `#d`, a `FloatVector` for `#f`; `vec:matvec` runs that vectorized dot once per matrix row). Running such a class requires the incubator module on the JVM: `java --add-modules jdk.incubator.vector Prog`. Without `--simd` the class runs the scalar reference on any JVM.
- **`--no-gc --simd` native `v128`**: `rontolisp prog.lisp -o prog.wasm --no-gc --simd` lowers the `vec:` kernels to WebAssembly fixed-width SIMD (`f64x2.*`, or `f32x4.*` for single-float) over the packed linear-memory block. **Without `--simd`, `--no-gc` emits plain scalar loops** over the byte-identical block -- a v128-free MVP module that runs on a WebAssembly runtime lacking the SIMD proposal, trading away the vectorized speedup for that portability. `vec:from-list` / `vec:to-list` (which need Lisp lists) and `vec:matvec` (which needs a rank-2 matrix) are unavailable on `--no-gc` either way.
- **wasm-GC**: the default `.wasm` backend cannot honor `--simd` yet (its packed arrays are GC objects, not linear memory); passing `--simd` prints a warning and falls back to the scalar reference. Use `--no-gc --simd` for native v128, or the JVM `--simd`.

Because reductions sum in a different order under SIMD, a reduction over inexact inputs can differ from the left-to-right scalar reference in the last ULP; over the exact doubles typical of tests the results match exactly. The element-wise kernels are always bit-identical.

## Packages

`vec` does not use `cl`; every function is external, referenced as `vec:name`. Put `(in-package :vec)` (or `(defpackage ... (:use :vec))`) in effect to write the exported names unqualified. The related [`linalg` package](linear-algebra.md) offers a broader numpy-style API (shape manipulation, matrix products, exact linear algebra) over the same arrays.
