# Transcendental functions: one algorithm, fdlibm, on every backend

**Invariant: `exp log sin cos tan asin acos atan atan2 sinh cosh tanh`, float `expt`
(`pow`), `hypot`, `log1p` and every complex function built on them answer the SAME BITS
on the interpreter, the JVM (on every CPU) and both WASM targets, with or without
`--simd`.** The algorithm is fdlibm -- `java.lang.StrictMath`'s -- everywhere; a
cross-backend corpus may print their full digits (`ci-spec.yaml`'s
`transcendentals-bit-identical-cross-backend` does, over the large-argument trig
reduction, the subnormal log, the overflow edges, the plane escapes and the complex
arms). `sqrt` stays `Math.sqrt` / `f64.sqrt`, correctly rounded and identical everywhere.
The one stated exception is `--gpu`'s transcendental tier (`.kb/linalg-simd.md`).

## Where each lives

- Interpreter (`eval/Environment`, `eval/VecSimdKernels`, `eval/LinalgSimdKernels`'s
  erf, `eval/GeomKernels`) and JVM (`codegen/jvm/JvmMathFnCompiler.buildOps`,
  `JvmNumericRuntimeBuilder`'s `_pow`, `JvmComplexRuntimeBuilder.callMath`,
  `JvmSimdVectorTemplate`, `JvmGeomTemplate`): `StrictMath`. `Math` is per-CPU
  (`Math.exp(1.0)` is `2.718281828459045` on x86-64 and `2.7182818284590455` on AArch64)
  and must not come back for any of these names; `Math.sqrt`/`abs`/`copySign`/`rint`
  are exact operations and stay.
- WASM (no transcendental instruction): `codegen/wasm/WasmFdlibmRuntimeBuilder`, one
  runtime FUNCTION per algorithm (`Fn`: the 13 public ones plus `k_sin`/`k_cos`/`k_tan`,
  `rem_pio2`/`krem` and `expm1`/`log1p`/`hypot`), shared by the GC backend and
  `--no-gc`. The sources are fdlibm transliterated statement for statement from the
  JDK's own `java.lang.FdLibm` into a C-like subset (`Sources`), which a small
  compiler in the same class (lexer, parser, emitter over raw `f64`/`i32`/`i64`
  locals) turns into a body; the `__HI`/`__LO` word accessors are `i64.reinterpret`
  arithmetic, `(int) d` is the saturating `i32.trunc_sat_f64_s` (Java's cast), and
  the language has NO implicit promotion, so a missed cast in a transliteration fails
  to parse. The trig reduction's 2/pi table, `npio2_hw` and `__kernel_rem_pio2`'s
  scratch arrays live in linear memory (`tables()`, a reader-owned blob kept while a function
  `addressesTables` names survives; every access cites the base as its own `i32.const`, which
  `WasmLispCompilerTest#everyFdlibmBodyThatCitesTheTablesIsOneTheShakerCountsAsTheirReader` pins).
  - GC backend: fixed slots `FUNC_FD_BASE + Fn.ordinal()` after `_cdr` (types
    `TYPE_FD_*` after the Schubfach block). Every call site goes through
    `Ctx.fdlibm(fn)`, which records the function; the code section gives the closure
    of that set real bodies and every other slot a trapping stub
    (`WasmFdlibmRuntimeBuilder.stub`). An INJECTED wrapper-catalog body (`#'exp`'s)
    records into a per-wrapper set instead, merged only when the wrapper is reachable
    (materialized as a value, hittable through the name registry, or named as `#'op`
    in the user's program) -- otherwise every program would carry every body, since
    the catalog wraps every transcendental. The tables blob is placed before Pass 2
    when a name that can reach trig is spelled (`sin cos tan exp expt cis sinh cosh
    tanh`), under `--simd`, or when any name can resolve at run time; a trig body
    without the blob is refused as a compiler bug.
  - `--no-gc`: `NoGcWasmCompiler.fdlibmFunctions` scans the reachable bodies for the
    `vec:` transcendental kernels AND, since 2026-09-17, the scalar builtins themselves
    (`collectFdlibmRoots`), closes over the callees, and `placeFunctions` gives them
    indices after the Schubfach helpers (`Mem.fdlibmIndex`); the blob follows the
    Schubfach tables (`layoutData`), placed only when a reached name can carry `sin`,
    `cos` or `tan`'s reduction tables, so a pure-numeric module that never calls one
    still carries no memory section. The scalar site is a `call` straight into the
    fdlibm function over a raw `f64` (the argument coerced through the same INT ->
    FLOAT promotion the numeric lattice already runs, no boxing): `compileTranscendentalUnary`
    for the eleven unary names, `compileLog` for `(log n)`/`(log n base)` (two calls,
    `f64.div`), `compileAtan` for `(atan x)`/`(atan y x)` (`Fn.ATAN2`, `y` pushed before
    `x`), `compileExpt` for `expt` -- only when the STATIC type of either operand is
    FLOAT (`staticType`); an exact base to an exact exponent needs the rational-loop
    tier this backend's value model (no ratio, no bignum) does not carry, so that shape
    is a compile error naming the operation, not a guess. No complex tier here either:
    an argument that would leave the real domain (`(log -1.0)`, `(asin 2.0)`) answers
    fdlibm's own NaN.
  - Call sites: `WasmTranscendentalCompiler` (real unary), `WasmExptCompiler` (`pow`,
    dispatching exactly as the interpreter's `expt`: an exact base to an integer
    exponent is the rational loop, anything with a float or a ratio exponent is
    `pow` -- and an integer exponent outside the i31 range is `pow` too
    (`(expt 2 4294967297)` is `Infinity`, not a trap; the loop counter is an i31,
    and a 2^30-iteration loop would never get there anyway -- `.todo/849`; the JVM's
    `_pow` takes the same rule at the int range instead of narrowing with `L2I`),
    `WasmComplexCompiler` (the interpreter's complex formulas term for term,
    over the same calls; the complex `expt` takes the squaring loop only for an EXACT
    base, like `exptComplex`), `WasmInverseHypCompiler` (the interpreter's
    `asinhReal`/`acoshReal`/`atanhReal` groupings over `log1p`/`log`/`hypot`), and the
    `--simd`/`--no-gc` kernels (`WasmVecSimdRuntimeBuilder.emitScalarUnaryF64`, a
    call; `WasmLinalgSimdRuntimeBuilder`'s erf).

## Measured (2026-09-17, x86-64 Linux, Java 25.0.4, wasmtime 47.0.3, loaded 64-core box)

- Bits: `WasmFdlibmRuntimeBuilderTest` runs every function over 16,225 arguments (random
  bit patterns, the ranges each is used over, values near multiples of pi/2, 1e22-class
  trig arguments, 65 special values paired for the binary functions) and every result
  equals `StrictMath`'s (NaN payloads excepted: an engine may sign a computed NaN
  either way). The old cores were up to 4e5 ulp off (`log` near 1, trig near a zero,
  float `expt`) and matched the JVM on 12-45% of arguments; the SICP sample that printed
  differently on wasm no longer does.
- `.wasm` size (default = `--optimize` here, bytes): one `exp` site 3,977 -> 4,059 (one
  function of ~600 B against one inline core); five `exp` sites 6,414 -> 4,239; one
  `sin` site 4,081 -> 8,016 (the price of the real reduction: `k_sin`, `k_cos`,
  `rem_pio2`, `krem` and 992 B of tables); the 12 functions once each 24,991 -> 21,347.
- wasmtime, 3e6 calls in a `dotimes` (best of 3): `exp` 0.296 -> 0.204 s, `sin` 0.365 ->
  0.209 s, float `expt` 0.549 -> 0.488 s -- fdlibm on raw locals beats the inline cores,
  which boxed every intermediate in a GC struct.
- JVM, the same loops, `Math` -> `StrictMath`: `exp` 0.124 -> 0.146 s (+18%), `sin`
  0.125 -> 0.157 s (+26%), float `expt` 0.209 -> 0.551 s (+164%: `Math.pow` is a
  HotSpot intrinsic, `StrictMath.pow` fdlibm in Java; `y == 2.0`, `0.5` and `+-1` keep
  their fast paths). The interpreter pays the same per call. Squares and roots are
  unaffected, and no kernel in the repo spends its time in `pow`; the numbers are the
  cost of the invariant, not a regression to fix.

## Traps

- The 0xFD byte of a no-gc probe: fdlibm's coefficients (asin's `0x1.23de10dfdf709p-15`)
  and LEB immediates (sinh's `0x8fb9f87d`) contain it, so a "no SIMD prefix" scan must
  step over float immediates and read the user's body only
  (`NoGcWasmCompilerTest.containsSimdPrefixInUserFunction`).
- A `mayReachTrig` pre-scan that misses a path is a compile-time `IllegalStateException`,
  never a wrong number: extend the name list rather than the exception. The pre-scan
  has to be at least as wide as `dispatchableFuncIds`' arming of the trig WRAPPERS, and
  one arm of that is not a symbol mention: with a symbol BUILDER in the program
  (`RuntimeNameProducers.anySymbolBuilder`), a STRING literal spelling a function's
  name arms its wrapper (`DesignatorSpellings.of`'s framed spellings). The baked package
  table spells every cl name that way (the `closer-common-lisp` row's import redirects
  are one string per member), so `(package-nicknames :cl)` beside a computed
  `(intern s)` used to trip the exception (found 2026-09-20 when
  `package-shadowing-symbols` joined the table's users); the pre-scan now also places
  the tables for a trig name spelled as a string literal when a builder is present
  (`programSpellsStringLiteral`).
- `Math.scalb` in `Pow`'s subnormal tail is `(z * 2^-1000) * 2^(n+1000)` in the source:
  exact steps and one rounding, the same double `scalb`'s stepped multiply lands on.

## Pinning tests

`WasmFdlibmRuntimeBuilderTest` (bits against `StrictMath`), `ci-spec.yaml`'s
`transcendentals-bit-identical-cross-backend` (four backends x `--simd`, digits),
`NoGcWasmCompilerTest`'s `{expAndSign,logAndTanh,sinCosTan,arcAndHyperbolic}LowerNativelyOnNoGc`
and `scalarTranscendentalsLowerNativelyOnNoGc`/`exptOfTwoNonFloatOperandsIsCompileError`
(the coefficients present, no `v128`), `WasmLispCompilerIntegrationTest`'s
`noGcRuns*UnderBothLowerings` including `noGcRunsScalarTranscendentalsUnderBothLowerings`
(no-gc == wasm-GC) and `compileAndRunComplex*`,
`JvmLispCompilerTest#compileAndRunComplexUnaryMathMirrorsTheInterpreterArmForArm`.
