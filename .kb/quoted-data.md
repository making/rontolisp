# A quoted datum is ONE shared constant, on all four backends

**Invariant: every evaluation of one `quote` site answers the SAME object on the
interpreter, the JVM and both WASM backends** -- `(eq (f) (f))` is `T` for
`(defun f () '(1 2 3))`, and a write through the datum is visible next evaluation (CLHS
leaves writing into a literal undefined). Complement of `.kb/array-literals.md`: a BARE
array literal is a CONSTRUCTOR (fresh per evaluation), the same syntax under `quote` a
CONSTANT.

- Only quoted AGGREGATES are memoized (cons, general array, instance, packed float/int
  array); atoms keep inline emission. Both compile paths key the memo by the DATUM'S
  IDENTITY (`IdentityHashMap`), so textually equal quote sites stay distinct while a macro
  splicing ONE template datum into several sites shares one constant.
- **Interpreter**: `LispEvaluator.evalQuote` and `eval`'s `LispInstance` arm hand the
  datum back verbatim and MUST -- `(quote <value>)` is also the live-value splice
  (`quoteValue`). Fresh-per-evaluation is rejected, do not re-attempt: it breaks
  `read-sequence`.
- **JVM** (`JvmQuoteCompiler` + `JvmLispCompiler.QuotePool`): one private static
  **volatile** `Object` field `_qd$N` per datum, built lazily at the site (~12 bytes over
  the build). Volatile so racing first evaluations cannot expose a half-written
  `Object[]`.
- **WASM, Preview 1 and component** (`WasmQuoteCompiler` +
  `WasmLispCompiler.QuoteGlobals`): one `(mut (ref null eq)) = null` global per datum,
  appended AFTER every fixed-index global, filled lazily (~10 bytes). The allocator is
  shared into `WasmAsyncEmit`'s fresh contexts so async resume bodies reach the one
  table.
- **Trap: the JVM build must stay lazy at the site, not a `<clinit>` initializer.**
  `JvmClassShaker` runs on every build; with the injected wrapper defuns' package-registry
  constants pinned by `<clinit>` a three-defun program grew 5,898 -> 18,978 bytes. Do not
  "simplify" this into the `LayoutPool`/`BigIntPool` `<clinit>` shape.

## A BARE instance literal shares the same slot
**Invariant: a `#P"..."` / `#S(...)` in CODE position -- outside any `quote` -- is one
shared constant per site on all four backends.** The one literal family not following
`.kb/array-literals.md`'s freshness rule: an instance is self-evaluating (CLHS 3.1.2.1.3),
so the compile side meets the interpreter. Same memo, via
`Jvm/WasmQuoteCompiler.emitSharedConstant`, called from `compile` (quoted aggregate) and
`compileLiteralInstance` (bare). Costs ~+40 bytes on wasm (orphaned null globals;
`WasmTreeShaker` does not drop globals), zero on the interpreter.

## Not covered
Two DIFFERENT quote sites spelling the same text are not `eq` (CLHS permits but does not
require coalescing). `--no-gc` is scalar-only (`.kb/no-gc-scalar-wasm.md`).

## Tests
ci-spec `quoted-datum-shared-cross-backend`, `instance-literal-shared-cross-backend`;
`LispEvaluatorTest.{aQuotedDatum,anInstanceLiteral}IsOneSharedConstantOnEveryBackend`;
`{aQuotedDatum,aBareInstanceLiteral}IsOneSharedConstantAcrossEvaluations` in
`JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` (Preview 1 AND component).
