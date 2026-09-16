# 819: imagpart of a real float keeps the sign of zero (IMAGPART.4)

Difficulty: Low

CLHS `imagpart`: "if number is real, imagpart returns `(* 0 number)`".
SBCL: `(imagpart -1.0)` => `-0.0`, `(imagpart 1.0)` => `0.0`.
rontolisp answers constant `+0.0` on interpreter/JVM/WASM-GC, so
`IMAGPART.4` (`(eql (* 0 x) (imagpart x))` over `*reals*`) fails for
negative floats.

NOT an `eql` change (first framing was wrong, 2026-09-16): CLHS `eql`
says "(eql 0.0 -0.0) returns false" when zeros are distinct, SBCL
answers NIL, and `.kb/linalg-simd.md` pins `(eql nz pz)` = NIL
everywhere. `eql`/hash untouched.

Fix (3 backends; --no-gc refuses complex ops outright, unchanged):
- `eval/Environment` IMAGPART: `0.0 * value` instead of constant `0.0`
- `codegen/jvm/JvmComplexCompiler.emitZeroForReal`: DMUL the unboxed
  double by `0.0` instead of constant
- `codegen/wasm/WasmComplexCompiler.compileImagpart`: unbox, `f64.mul`
  by `0.0`, re-box

Pins: `LispEvaluatorTest#evalImagpartSignedZero`,
`JvmLispCompilerTest#compileAndRunImagpartSignedZero`,
`WasmLispCompilerIntegrationTest#imagpartSignedZero`.
Effect measured as diff of failing ANSI test NAMES (numbers/misc)
before/after per `.todo/715` "How to count".
