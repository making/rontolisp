# Tuned BLAS Acceleration (`--blas`)

`--blas` routes [`linalg`](linear-algebra.md)'s matrix product to a tuned BLAS out of the operating system. It is one of three orthogonal acceleration flags: [`--simd`](simd-acceleration.md) lowers the vectorizable `vec:` and `linalg:` kernels to CPU vector instructions, `--blas` replaces the matrix product with a library call, and [`--gpu`](gpu-acceleration.md) puts that product and the element-wise transcendentals on an NVIDIA device. Any combination of the three, or none.

`--simd` gives the matrix product a hand-written lane kernel. Every desktop and server operating system can do far better than that, because a **tuned BLAS** -- a library whose matrix multiply is blocked for the machine's cache hierarchy and written against its matrix instructions -- is either already in the OS or one package away. `--blas` finds one and routes `linalg`'s matrix product to it.

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


## What is accelerated, and what declines

The matrix product and nothing else: `linalg:dot` for matrix-by-matrix, matrix-by-vector and vector-by-matrix, and therefore `linalg:matmul` at rank <= 2 and `linalg:solve`, which are written over it. That is where the whole win is; the memory-bound members (`sum`, a vector-by-vector `dot`, element-wise arithmetic) would gain nothing from a library call, and `--simd` already covers them.

Everything else **declines** and runs exactly what it ran before -- the `--simd` kernel when that flag is on too, and the portable `linalg.lisp` definition otherwise. That includes general boxed arrays, mixed widths, a scalar operand, the batched rank-3 product, a shape mismatch (which signals the same error), and any product too small to pay for a library call. So `--blas` never changes what a program accepts or rejects.

## Reach, threads and precision

`--blas` reaches the **interpreter** (including the native binary) and the **JVM class output**. A tuned BLAS is called through the foreign function API, which WASM does not have, so `--blas` with a `.wasm` output is an error rather than a silent no-op. A compiled class calls a restricted method, so run it as `java --enable-native-access=ALL-UNNAMED Prog` to keep the JVM's warning off standard error.

A tuned BLAS is **multi-threaded**, which nothing else in rontolisp is: a single `linalg:matmul` may occupy every core of the machine. That is most of the Linux figure above. Cap it with the library's own environment variable -- `OPENBLAS_NUM_THREADS`, `MKL_NUM_THREADS`, or `VECLIB_MAXIMUM_THREADS` for Accelerate -- when a program shares the machine.

The library blocks and reorders its reduction, so **an accelerated product is close to the portable definition rather than equal to it**, at `linalg`'s default `#d` width. Over exact inputs (integers, powers of two) the results still match exactly; over inexact ones they differ in the last few ulps. This flag is the one acceleration in rontolisp whose numerical answer depends on **which library and which version is installed on the machine**, which is exactly why it is its own flag: an existing `--simd` build computes what it always computed.

## Which library was bound

A library being present does not make it tuned: the netlib **reference** implementation exports the same symbols and is slower than the kernel rontolisp already has, and on Debian `libblas.so.3` is an alternatives symlink that points at either one. `--blas` therefore accepts a candidate only when it identifies itself as a tuned implementation, and declines otherwise -- being slower than the unaccelerated build is the one thing this feature must never do.

```bash
RONTOLISP_BLAS_VERBOSE=1 rontolisp prog.lisp --blas   # print what was bound, or why nothing was
RONTOLISP_BLAS=/path/to/libopenblas.so.0 rontolisp prog.lisp --blas   # name one outright
```

`RONTOLISP_BLAS` skips both the search and the identification check, so it is also the way to use a tuned build this list cannot name. Both variables are read by a compiled class too, which is how you check a `.class` on the machine that runs it.

## A runnable example

[`examples/ml/blas-matmul.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/blas-matmul.lisp) is the one example both `--simd` and `--blas` reach, because it is one `linalg:matmul` at linalg's default `double-float` width and nothing else. Its entries are small integers, so every product and every sum is exact and no reordering -- lanes or library blocking -- can move a printed digit. Run it up to four ways:

```bash
rontolisp examples/ml/blas-matmul.lisp
rontolisp examples/ml/blas-matmul.lisp --simd
rontolisp examples/ml/blas-matmul.lisp --blas
rontolisp examples/ml/blas-matmul.lisp --simd --blas
```

Per 128x128 product on an Apple M4 Max: the interpreter goes 1848 ms -> 0.62 ms with `--simd` -> 0.034 ms with `--blas`, and the JVM 0.37 ms -> 0.043 ms. Compiled to wasm-GC, where there is no foreign function API and so no `--blas`, it goes 60 ms -> 1.4 ms with `--simd`. Raise `*reps*` in the source to time the accelerated runs: one product finishes well inside the millisecond tick the clock can see.

