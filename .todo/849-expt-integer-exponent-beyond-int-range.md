# expt with an integer exponent beyond the int range answers three different things

Difficulty: Medium

Found while unifying the transcendentals (2026-09-17). `(expt 2 4294967297)`:

- interpreter: `Infinity` -- a `power` outside `[-Integer.MAX_VALUE, Integer.MAX_VALUE]`
  falls to `StrictMath.pow(2.0, 4294967297.0)`.
- JVM: `2` -- `JvmNumericRuntimeBuilder.buildPow`'s exact path narrows the exponent
  with `L2I` (`(int) 4294967297 == 1`) before the squaring loop, so it silently
  answers `base^(e mod 2^32)`. `(expt 1.5 -4294967297)` answers `0.0` on both only
  because a double LITERAL base compiles straight to `pow`.
- WASM: traps -- `WasmExptCompiler.emitIntegerExponent` reads the exponent through
  `WasmMathHelper.getI32` (an i31 cast), and a bignum exponent is not an i31.

Plan: make the JVM `_pow` and the WASM exact loop take the interpreter's rule -- an
integer exponent inside the int range is exact, anything else is `pow` over
`_dbl`/`_as_f64` of both operands -- and pin the three spellings in `ci-spec.yaml`
(`transcendentals-bit-identical-cross-backend` is where the float digits already live).
