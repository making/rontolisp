# One transcendental algorithm on all four backends: fdlibm (StrictMath + a WASM port)

Difficulty: High

Measured in `.kb/transcendentals.md` (2026-09-17): the interpreter/JVM use `Math` (per-CPU
intrinsics; x86-64 and AArch64 already disagree), WASM uses software cores that are up to
~4e5 ulp off (`log`, float `expt`, `sin`/`cos`/`tan` near a zero) and match the JVM on as
few as 12-45% of arguments. No cross-backend corpus can print these digits, and a SICP
sample (`(exp (double (log 14)))`) already prints differently on wasm.

Plan:

1. Interpreter and JVM: `StrictMath` for `exp log sin cos tan asin acos atan atan2 sinh cosh
   tanh pow` (and the complex functions built on them, `.kb/jvm-complex.md`; the vec/linalg
   kernels whose contract is "bit-identical to the defun"; the pinning tests that assert
   `Math` values). Measure the per-call cost on a real loop first (+0-50% in a lambda loop).
2. WASM: port fdlibm as runtime FUNCTIONS (not per-site inline emission) and call them from
   the defun path, the `--simd`/`--no-gc` kernels (`WasmVecSimdRuntimeBuilder.emit*F64`)
   and the complex runtime, retiring the series cores. `__ieee754_rem_pio2` needs
   `__kernel_rem_pio2` and its table for large arguments.
3. Then pin real digits: a `ci-spec.yaml` case printing each function over a spread of
   arguments on all four backends, and drop the tolerance note from `.kb/transcendentals.md`
   and `doc/*/guides/scheme.md` ("Deviations").
4. Measure `.wasm` size and speed before/after (per-site inline vs one function) and record.

`--gpu` transcendental kernels stay outside (`.kb/linalg-simd.md` states their contract).
