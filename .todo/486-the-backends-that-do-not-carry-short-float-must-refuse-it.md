# 486. The backends that do not carry `short-float` must refuse it, not misread it

Difficulty: Low

Part of `.todo/482`, whose scope is deliberately **interpreter and JVM only**. Everything
else must say so at the point of failure. A width that silently degrades to a boxed array
on one backend and stays packed on another breaks the cross-backend identity contract
that `.kb/vec.md` and `ci-spec.yaml` exist to hold.

## Refuse, with a message that names the width

- **wasm-GC** (`WasmQuoteCompiler`, `WasmArrayCompiler`'s `$farray` struct, and the
  `TYPE_F64ARR`/`TYPE_F32ARR` pair the data field is told apart by): a `#h(...)` literal
  or `:element-type 'short-float` is a compile error.
- **`--no-gc`** (`NoGcWasmCompiler`, `Ty.F64VEC` / `Ty.F32VEC` linear-memory blocks):
  same.
- **the component / WIT path**: `short-float` has no WIT counterpart; refuse at the
  export boundary rather than at the first read.

The error must say the width and the backend -- "`short-float` arrays are supported on
the interpreter and the JVM only" -- because the failure a user will otherwise hit is a
wrong number, not a crash.

## Decline, silently and correctly

- **`--gpu`** (`LinalgGpu`, `LinalgGpuKernels`): the device kernels are f32/f64. After
  `.todo/483` these sites are exhaustive switches, so the f16 arm returns `null` and the
  caller falls through to the lane or defun path exactly as it already does when the
  device is absent or the matrix is too small. Check `installVec`'s `matvec` intercept and
  the residency map in particular: a `short[]` must never be *offered* to the device.
  (f16 on the device is a real win and a separate item -- `.kb/gpu.md`, and the "where it
  would pay" section of `.todo/482` -- but it is not this one.)
- **BLAS** (`LinalgBlas`): there is no f16 GEMM in the intercepted set; the f16 arm
  declines and the scalar/lane path runs.

## Verify

- A `#h(1.0 2.0)` program compiled with `-o x.wasm`, with `--no-gc`, and with
  `--component`: three clear errors, no output file.
- `--gpu` and `--simd` runs over a `short-float` array answer bit-identically to the
  plain interpreter run.
- The `ci-spec.yaml` case added by `.todo/484` must declare only the backends that carry
  the width.
