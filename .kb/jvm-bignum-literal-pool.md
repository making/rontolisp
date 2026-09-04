# JVM bignum literals: one instance per value, built in `<clinit>`

**Invariant: a `java.math.BigInteger` literal is constructed ONCE per compilation per
distinct value, in `<clinit>`; every use site is a `GETSTATIC` of `_bi$N`.** Identity is
unobservable (`_equal`/`_hash`/`eql` go through `BigInteger.equals`/`compareTo`).

- `JvmLispCompiler.BigIntPool` (shaped like `LayoutPool`), shared across every `Ctx` of a
  compilation via the single `Ctx.Builder`; `intern(cp, className, value)` keys on the value,
  `emitClinitInit` drains it at class assembly.
- A bignum literal is a reason to emit `<clinit>`, and the last fallback for the `<clinit>`
  name/descriptor Utf8 constants.
- Its initializers go FIRST inside `<clinit>`, before layouts, stream seeds and the top-level
  runner.
- `max_stack` 3 per initializer -- `StackMapAugmenter` copies the declared maximum verbatim,
  so an under-declaration is a `VerifyError` at class load, not a compile error.
- Plain `private static`, no `ACC_FINAL`+`ConstantValue`: `JvmClassShaker` rejects field
  attributes.
- `compileRatio`'s `BigInteger[2]` stays per-use (mutable array); its elements are pooled.
- `runtime` gains no class (`.kb/jvm-export.md`); a bignum-free program is byte-identical to
  pre-pool output. WASM literals are separate (`.kb/wasm-bignum.md`).

## Tests
`JvmLispCompilerTest#aBignumLiteralIsBuiltOnceAndLoadedFromAField`,
`#aProgramWithoutABignumLiteralGetsNoPoolAndNoClassInitializer`, `IroncladE2eTest`.
