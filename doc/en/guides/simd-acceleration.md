# Vector Kernels (vec)

The `vec` package provides portable packed-`f64` vector kernels: constructors, element access, element-wise arithmetic and reductions over the [packed float array type](../reference/data-types.md). It is the go-to package for tight numeric loops over vectors of doubles, and it carries an optional hardware-acceleration (SIMD) layer on two backends. The package names the portable abstraction; the `--simd` flag names how it is accelerated.

Like the JSON and `linalg` libraries, `vec` is implemented once in Lisp source (`vec.lisp`): the interpreter loads the definitions lazily on the first use of a `vec:` function, and the compile path splices them into the program when it references the package. This scalar definition is the implementation on the interpreter, the JVM compiler and the WASM (wasm-GC) backends, and the correctness oracle for the two accelerated backends, so every function behaves identically everywhere.

## Data representation

A vector is a rank-1 [packed float array](../reference/data-types.md): the `double-float`-typed, unboxed array that `#d(...)` and `(make-array n :element-type 'double-float)` produce. The built-in `aref` / `length` interoperate with it, and any packed vector built elsewhere can be handed to a `vec` function. Element-wise kernels return a fresh vector; reductions return a scalar `double`.

```lisp
(vec:arange 5)                         ; => #d(0.0 1.0 2.0 3.0 4.0)
(vec:add #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => #d(5.0 7.0 9.0)
(vec:dot #d(1.0 2.0 3.0) #d(4.0 5.0 6.0)) ; => 32.0
(vec:scale #d(1.0 2.0 3.0) 10)         ; => #d(10.0 20.0 30.0)
```

## The API

Construction: `vec:zeros` / `vec:ones` build a filled vector of length *n*, `vec:arange` builds `[0.0, 1.0, ..., n-1]`, and `vec:from-list` / `vec:to-list` convert between a vector and a Lisp list (the list forms run on the interpreter, the JVM and wasm-GC only, not `--no-gc`).

Access: `vec:aref` reads an element (a `setf` place via `vec:aset`), and `vec:length` returns the element count. These are thin wrappers over the generic packed-array operators, so plain `aref` / `length` work too.

Element-wise (a fresh vector): `vec:add`, `vec:sub`, `vec:mul` (Hadamard product) and `vec:scale` (multiply by a scalar).

Reductions (a scalar): `vec:sum`, `vec:dot`, `vec:mean` and `vec:norm` (the Euclidean norm, `sqrt` of the self-dot).

```lisp
(vec:sum (vec:arange 5))              ; => 10.0
(vec:mean #d(2.0 4.0 6.0))             ; => 4.0
(vec:norm #d(3.0 4.0))                 ; => 5.0
(vec:to-list (vec:mul #d(1.0 2.0 3.0) #d(4.0 5.0 6.0))) ; => (4.0 10.0 18.0)
```

## Hardware acceleration (optional)

The scalar `vec.lisp` reference is correct everywhere; two backends can additionally lower the vectorizable kernels (`add` / `sub` / `mul` / `scale` / `dot` / `sum`, and `mean` / `norm` transitively) to real SIMD hardware. This is opt-in and changes nothing about the results -- the accelerated output is byte-for-byte identical to the scalar reference.

- **JVM `--simd`**: `rontolisp prog.lisp -o Prog.class --simd` routes the kernels to an embedded `jdk.incubator.vector` bridge that the JIT intrinsifies to CPU vector instructions. Running such a class requires the incubator module on the JVM: `java --add-modules jdk.incubator.vector Prog`. Without `--simd` the class runs the scalar reference on any JVM.
- **`--no-gc` native `v128`**: on the non-GC scalar WASM backend (`rontolisp prog.lisp -o prog.wasm --no-gc`) the `vec:` kernels are lowered to WebAssembly fixed-width SIMD (`f64x2.*`) over a packed linear-memory block -- no acceleration flag needed, it is the backend's native implementation. `vec:from-list` / `vec:to-list` need Lisp lists and are unavailable here.

Because reductions sum in a different order under SIMD, a reduction over inexact inputs can differ from the left-to-right scalar reference in the last ULP; over the exact doubles typical of tests the results match exactly. The element-wise kernels are always bit-identical.

## Packages

`vec` does not use `cl`; every function is external, referenced as `vec:name`. Put `(in-package :vec)` (or `(defpackage ... (:use :vec))`) in effect to write the exported names unqualified. The related [`linalg` package](linear-algebra.md) offers a broader numpy-style API (shape manipulation, matrix products, exact linear algebra) over the same arrays.
