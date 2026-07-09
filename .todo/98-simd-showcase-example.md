# 98 — an example that actually shows the `--simd` win

**Motivation:** we have a real SIMD acceleration layer on every backend, and (until now) no example
where it pays off. `examples/ml/nn-vec.lisp`'s tensors are rows of length 2 and 4 — far below the
JVM/interpreter `THRESHOLD = 128`, so `--simd` does literally nothing there.

## Status 2026-07-09: measured, parked mid-flight

A full transformer decoder was built and works (see "Parked" below), but the user asked to **start
smaller**: extract only the handful of vector/matrix operations the transformer needs, as a minimal
example. Do that first; the transformer can come back later as a follow-up.

### DECISION: the next example is a MINIMAL one

Just the accelerated primitives, at a size that crosses `THRESHOLD = 128`:

- `vec:matvec` (GEMV) — the one op an autoregressive LLM spends ~all its time in
- `vec:dot` — attention scores / sum-of-squares in RMSNorm
- maybe `vec:add` / `vec:mul` / `vec:scale` — residual, Hadamard, normalization

with a deterministic integer checksum + an elapsed-time line, so the reader runs it twice.

## MEASURED FACTS — do not re-derive these

All on an M4 (aarch64), from `examples/ml/tiny-llm.lisp` (2-layer decoder, dim=256, hidden=512,
ctx=12, 1.34M MAC per forward pass, single-float), decode time only:

| config | scalar | `--simd` | ratio |
|---|---|---|---|
| **wasm-GC** (`wasmtime run -W gc`) | 891 ms | **7.8 ms** | **114x** |
| wasm component | 894 ms | 8.2 ms | 109x |
| **interpreter** (native binary) | 11351 ms | **1631 ms** | **7.0x** |
| JVM on **HotSpot C2** (`-XX:-UseJVMCICompiler`) | 388 ms | 119 ms | 3.3x |
| JVM on **GraalVM JIT** (the default `java`) | 80 ms | 1049 ms | **0.08x — SLOWER** |

Pure kernel, 200 x `vec:dot` over 1048576 f32 elements (209.7M MACs):

| runtime | time | ns/MAC |
|---|---|---|
| jar + `-XX:-UseJVMCICompiler` (C2) + Vector API | **79 ms** | 0.38 |
| jar + GraalVM JIT + Vector API | 11482 ms | 55 |
| native binary (`-H:+VectorAPISupport` IS on the command line) + Vector API | 21671 ms | 103 |
| interpreter scalar (C2) | 65039 ms | 310 |

### FINDING A (solid): GraalVM's JIT does not intrinsify the Vector API — JVM `--simd` is a trap there

Same jar, same kernels, only the compiler differs: **11482 ms on the GraalVM JIT vs 79 ms on
HotSpot C2** (`-XX:-UseJVMCICompiler`). A non-intrinsified Vector API is ~150x slower than an
intrinsified one and ~200x slower than a plain compiled scalar loop, so on GraalVM — the JDK this
project's own native profile uses, and the default `java` on this machine — `-o Prog.class --simd`
is a large **pessimization** (Graal's auto-vectorizer already handles the scalar loop: 80 ms).

To fix (NOT yet done): `doc/{en,ja}/guides/simd-acceleration.md`'s JVM bullet says "an embedded
`jdk.incubator.vector` bridge that **the JIT intrinsifies** to CPU vector instructions." True on
HotSpot C2, false on GraalVM. It must name the JVM.

### FINDING B (unresolved — needs its own investigation): native binary `--simd` looks unvectorized

The native binary is built WITH `-H:+VectorAPISupport` (confirmed on the `native-image` command
line), yet its `vec:dot` runs at **103 ns/MAC** — per-lane-emulation speed, ~270x slower than C2's
intrinsified 0.38 ns/MAC and ~100x slower than a plain compiled scalar loop. Its 6-7x win over the
scalar interpreter is therefore the Java kernel beating the tree-walking evaluator, NOT SIMD.

This **contradicts** memory `native-image-vector-api.md`, which reports a standalone `VecBench.java`
native image measuring 3.69x for float dot with the flag on (and 0.16x with it off). Both cannot be
right. Before rewriting that memory or the docs, re-run that standalone benchmark and compare it
against a native image of rontolisp's own `VecSimdKernels` — the difference may be the header-less
`float[]` shape, `SPECIES_PREFERRED` resolution at image-build time, or something about how
`VecSimd.install()` reaches the kernels. Do not assert either way until measured.

### What can be honestly advertised today

**wasm-GC (100x+)** and **the interpreter (7x)**. The JVM number is JIT-dependent and must say so.

### Other hard constraints for any `--simd` example

- **`THRESHOLD = 128`** (`JvmSimdVectorTemplate`, `eval/VecSimdKernels`): below 128 elements per
  row/vector the JVM and interpreter run a scalar loop. wasm-GC and `--no-gc` have no threshold.
- **Print only INTEGERS.** The WASM backend prints floats to ~7 significant digits
  (`2.718281` vs `2.7182818284590455`), and its `exp` differs from the JVM's in the low bits
  (`exp 10.0` -> `22026.465767` vs `22026.465794806718`). So a float never compares across backends.
  `deep-digits.lisp` sets the precedent: fixed-seed LCG, no transcendentals, integer-scaled output.
- `get-internal-real-time` = **milliseconds**; an INTEGER on interpreter/JVM, a FLOAT on WASM.
  `internal-time-units-per-second` does NOT exist. So the elapsed-time line must not be checked by
  `examples.yaml`.
- `--no-gc` cannot compile `linalg:` at all (`&optional` in `linalg::%la-make`) and rejects
  `vec:matvec`. Any example using either is `[interpreter, jvm, wasm]` only.
- Weight-init cost on the interpreter is ~1 microsecond per element; GEMV is ~700 ns per MAC.
  Interpreter budget: existing examples run 0.0 s (heat3d) .. 10.1 s (deep-digits) .. 38.4 s (mlp).

## Parked: `examples/ml/tiny-llm.lisp` (untracked, works, NOT registered)

A complete 2-layer transformer decoder: RMSNorm, Q/K/V GEMVs, causal self-attention over a KV cache,
softmax, output projection, SwiGLU FFN, residuals, classifier head, greedy argmax decode. The one
idea in it worth keeping is the **KV cache layout**: the K cache is row-major `(n-ctx x dim)` so
`(vec:matvec kc q)` yields every attention score in one GEMV, and the V cache is **transposed**
`(dim x n-ctx)` so `(vec:matvec vt a)` is the attention-weighted value sum, again one GEMV. Store V
row-major and that step degrades to a scalar loop. llama2.c makes the same choice.

Verified: it prints `generated: (39 27 23 18 42 7 5 39 27)` **identically on all ten configurations**
(interpreter / JVM x 2 JITs / wasm-GC / wasm component, each with and without `--simd`) — the token
ids are a fingerprint of the whole computation, robust to the last-ULP differences `--simd` and the
WASM `exp` introduce. Interpreter run: ~12.6 s total (1.4 s init + 11.2 s decode).

Decide later: keep it as a second, heavier example, or delete it.

## Registration checklist for whatever ships

- `examples/examples.yaml`: `backends: [interpreter, jvm, wasm]`, `expect: contains` the stable
  header + checksum lines only (never the elapsed line). The `wasm` backend compiles with
  `--optimize` and runs `wasmtime run -W gc --dir .`.
- `examples/README.md`: a row in the "Numerical & machine learning — `ml/`" table.
  (`nn-vec.lisp` is currently MISSING from that table — fix while there.)
- Consider linking it from `doc/{en,ja}/guides/simd-acceleration.md`.
