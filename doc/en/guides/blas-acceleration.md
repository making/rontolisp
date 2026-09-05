# Tuned BLAS Acceleration (`--blas`)

`--blas` routes the matrix product -- [`linalg`](linear-algebra.md)'s, and the GEMV in [`vec`](simd-acceleration.md) -- to a tuned BLAS out of the operating system. It is one of three orthogonal acceleration flags: [`--simd`](simd-acceleration.md) lowers the vectorizable `vec:` and `linalg:` kernels to CPU vector instructions, `--blas` replaces the matrix product with a library call, and [`--gpu`](gpu-acceleration.md) puts that product and the element-wise transcendentals on a GPU. Any combination of the three, or none.

`--simd` gives the matrix product a hand-written lane kernel. Every desktop and server operating system can do far better than that, because a **tuned BLAS** -- a library whose matrix multiply is blocked for the machine's cache hierarchy and written against its matrix instructions -- is either already in the OS or one package away. `--blas` finds one and routes the product to it.

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

The matrix-by-**vector** product is a different story, and a much more machine-dependent one. Against the same lane kernel, one `cblas_sgemv` is 6-9x on an Apple M4 Max but only 1.2-2.0x on an x86-64 Xeon pinned to one thread -- the lane kernel is twice as fast there to begin with, because 128-bit vectors are all NEON has while AVX2 gives the same source 256-bit registers. **Measure on the machine you deploy on**, and read the thread note below before you do.

## What is accelerated, and what declines

The matrix product and nothing else, in both packages that have one:

- **`linalg:dot`** for matrix-by-matrix, matrix-by-vector and vector-by-matrix, and therefore `linalg:matmul` at rank <= 2 and `linalg:solve`, which are written over it.
- **`vec:matvec` and `vec:matvec-into`**, the GEMV. `vec:matvec-into` is the closer fit of the two: the library writes into a caller-supplied destination, so the interception drops the result allocation as well as the loop.

That is where the whole win is; the memory-bound members (`linalg:sum`, a vector-by-vector `dot`, `vec:sum`, every element-wise kernel in either package) would gain nothing from a library call, and `--simd` already covers them.

The `vec` half is what makes the flag reach the programs it exists for: a transformer decoding one token at a time multiplies every weight matrix by a *vector*, never by another matrix, so `examples/ml/simd-gemv.lisp`, `examples/ml/tiny-llm.lisp` and `examples/llm/llm.lisp` are GEMV from end to end. **`--blas` alone is enough** -- it does not need `--simd` beside it to reach `vec`.

Everything else **declines** and runs exactly what it ran before -- the `--simd` kernel when that flag is on too, and the portable `linalg.lisp` / `vec.lisp` definition otherwise. That includes general boxed arrays, mixed widths, a scalar operand, the batched rank-3 product, a `vec:matvec-into` whose destination is one of its own operands, a shape mismatch (which signals the same error), and any product too small to pay for a library call. So `--blas` never changes what a program accepts or rejects.

## Reach, threads and precision

`--blas` reaches the **interpreter** (including the native binary) and the **JVM class output**. A tuned BLAS is called through the foreign function API, which WASM does not have, so `--blas` with a `.wasm` output is an error rather than a silent no-op. A compiled class calls a restricted method, so run it as `java --enable-native-access=ALL-UNNAMED Prog` to keep the JVM's warning off standard error.

A tuned BLAS is **multi-threaded**, which nothing else in rontolisp is: a single `linalg:matmul` may occupy every core of the machine. That is most of the Linux figure above. Cap it with the library's own environment variable -- `OPENBLAS_NUM_THREADS`, `MKL_NUM_THREADS`, or `VECLIB_MAXIMUM_THREADS` for Accelerate -- when a program shares the machine.

**Which library it is decides whether that matters, so measure before you cap.** On an Apple machine Accelerate already runs a decode loop's products on one thread, and capping it only takes away the threads the big products want: the same llama2 stories15M decode is 538 tok/s under `--simd`, **1021-1028 tok/s** under `--simd --blas`, and 920-943 tok/s with `VECLIB_MAXIMUM_THREADS=1`. On a 64-core Linux machine with OpenBLAS the same three builds go the other way, and there capping is not politeness -- it is the difference between a win and a rout.

**For a GEMV loop against a threaded library, capping it is not politeness -- it is the difference between a win and a rout.** A matrix-by-vector product is memory-bound and short, so a threaded library pays a thread barrier per call that the call itself cannot amortize. On a 64-core machine, `examples/llm/llm.lisp` decoding stories15M on the JVM backend runs at 102-110 tok/s under `--simd`, **124 tok/s** under `--simd --blas` with `OPENBLAS_NUM_THREADS=1`, and **16 tok/s** under `--simd --blas` with the library's default thread count. rontolisp does not set the variable for you -- a library's thread pool is yours to size -- so set it yourself:

```bash
OPENBLAS_NUM_THREADS=1 rontolisp examples/ml/tiny-llm.lisp --simd --blas
```

It does not leave you to find that out afterwards, either. `--blas` asks the library how many threads it will use, and once a program has issued 64 products too small to pay for a barrier it writes one line to standard error naming the variable to set. A program whose products are big enough to want the threads never sees it: on the same 64-core machine a 1024x1024 matrix product is 6x *faster* threaded than capped, so the default is wrong only for the short calls, and only the calls can tell. A library that is already capped, and one with no way to ask (Accelerate exports none), say nothing.

The library blocks and reorders its reduction, so **an accelerated product is close to the portable definition rather than equal to it**, at either width. Over exact inputs (integers, powers of two) the results still match exactly; over inexact ones they differ in the last few ulps -- more at `single-float`, where a GEMV's reduction also accumulates in single precision. This flag is the one acceleration in rontolisp whose numerical answer depends on **which library and which version is installed on the machine**, which is exactly why it is its own flag: an existing `--simd` build computes what it always computed.

## Which library was bound

A library being present does not make it tuned: the netlib **reference** implementation exports the same symbols and is slower than the kernel rontolisp already has, and on Debian `libblas.so.3` is an alternatives symlink that points at either one. `--blas` therefore accepts a candidate only when it identifies itself as a tuned implementation, and declines otherwise -- being slower than the unaccelerated build is the one thing this feature must never do.

```bash
RONTOLISP_BLAS_VERBOSE=1 rontolisp prog.lisp --blas   # print what was bound, or why nothing was
RONTOLISP_BLAS=/path/to/libopenblas.so.0 rontolisp prog.lisp --blas   # name one outright
```

`RONTOLISP_BLAS` skips both the search and the identification check, so it is also the way to use a tuned build this list cannot name. Both variables are read by a compiled class too, which is how you check a `.class` on the machine that runs it. The verbose line also reports the thread count the library admitted to, or `0` when it has no way to say.

## A runnable example

[`examples/ml/blas-matmul.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/blas-matmul.lisp) is the `linalg` half in isolation: one `linalg:matmul` at linalg's default `double-float` width and nothing else. Its entries are small integers, so every product and every sum is exact and no reordering -- lanes or library blocking -- can move a printed digit. Run it up to four ways:

```bash
rontolisp examples/ml/blas-matmul.lisp
rontolisp examples/ml/blas-matmul.lisp --simd
rontolisp examples/ml/blas-matmul.lisp --blas
rontolisp examples/ml/blas-matmul.lisp --simd --blas
```

Per 128x128 product on an Apple M4 Max: the interpreter goes 1848 ms -> 0.62 ms with `--simd` -> 0.034 ms with `--blas`, and the JVM 0.37 ms -> 0.043 ms. Compiled to wasm-GC, where there is no foreign function API and so no `--blas`, it goes 60 ms -> 1.4 ms with `--simd`. Raise `*reps*` in the source to time the accelerated runs: one product finishes well inside the millisecond tick the clock can see.

For the GEMV half, [`examples/ml/simd-gemv.lisp`](https://github.com/making/rontolisp/blob/develop/examples/ml/simd-gemv.lisp) is the one to run -- a hundred `vec:matvec` calls on a 256x256 `single-float` matrix and nothing else. It prints `argmax` indices, integers derived from every multiply-add that produced them, and they must not move:

```bash
rontolisp examples/ml/simd-gemv.lisp                                   # scalar
rontolisp examples/ml/simd-gemv.lisp --blas                            # library GEMV
OPENBLAS_NUM_THREADS=1 rontolisp examples/ml/simd-gemv.lisp --simd --blas
```

On a 64-core Xeon with OpenBLAS the interpreter goes 8964 ms -> 187 ms with `--simd` -> 131 ms with `--simd --blas` capped to one thread (and back up to 371 ms uncapped). `--blas` alone is 629 ms: the GEMV is a library call, but the `vec:dot` and `vec:scale` beside it are still portable definitions, which is most of what is left.

