# A quoted datum is ONE shared constant, on all four backends

**Invariant: every evaluation of one `quote` site answers the SAME object on the
interpreter, the JVM and both WASM backends.** `(eq (f) (f))` for
`(defun f () '(1 2 3))` is `T` everywhere; a write through the datum is visible to the
next evaluation (CLHS leaves writing into a literal undefined).

Complement of `.kb/array-literals.md`: a BARE array literal is a CONSTRUCTOR (fresh per
evaluation), the same syntax under `quote` a CONSTANT (shared). `#(1 2 3)` in code
position stays fresh -- the property `PureBuiltinFolder`'s packed-table fold rests on,
since the fold splices its results BARE; `'#(1 2 3)` is shared.

Rejected, do not re-attempt: fresh-per-evaluation. `(quote <value>)` is ALSO the
interpreter's live-value splice (`quoteValue`, four sites, ~15 more constructions across
`eval` and `macro`); materializing in `evalQuote` breaks `read-sequence`, and the
self-evaluating `LispInstance` arm cannot tell a literal from a spliced runtime instance.

## Mechanics

Only quoted AGGREGATES are memoized -- cons, general array, instance, packed float/int
array under `quote`. Atoms keep inline emission. Both backends key the memo by the
DATUM'S IDENTITY (`IdentityHashMap`), so two textually equal quote sites stay distinct
while a macro expansion splicing ONE template datum into several sites shares one
constant -- matching the interpreter, which hands out the template's own cons at every
expansion.

- **Interpreter**: unchanged. `LispEvaluator.evalQuote` hands the datum back as is, and
  must (splice constraint above).
- **JVM** (`JvmQuoteCompiler.compile` + `JvmLispCompiler.QuotePool`): one private static
  **volatile** `Object` field (`_qd$N`) per datum, built LAZILY at the site --
  `GETSTATIC; DUP; IFNONNULL end; POP; <build>; DUP; PUTSTATIC; end:` (~12 bytes over the
  build). Volatile so racing first evaluations each publish a fully-constructed datum (a
  data race can expose a half-written `Object[]`).
- **WASM, Preview 1 and component** (`WasmQuoteCompiler.compile` +
  `WasmLispCompiler.QuoteGlobals`): one `(mut (ref null eq)) = null` module global per
  datum, appended AFTER every fixed-index global (nothing renumbers; the count is known
  only once every body has compiled), filled lazily --
  `global.get; ref.is_null; if; <build>; global.set; end; global.get` (~10 bytes). Single
  threaded, so race-free. The allocator is shared into `WasmAsyncEmit`'s fresh contexts,
  so quote sites in top-level chunks and async resume bodies reach the one table.

**Trap: the JVM build must stay lazy at the site, not a `<clinit>` initializer.**
`JvmClassShaker` runs on every build (not just `--optimize`); the injected wrapper defuns
(`find-package`, `list-all-packages`, `package-use-list`, `package-used-by-list`) each
quote the package-registry tables, and with their constants pinned by `<clinit>` a
three-defun program grew 5,898 -> 18,978 bytes. Lazy at the site lets the shaker drop
field and build with the wrapper. Do not "simplify" this into the
`LayoutPool`/`BigIntPool` `<clinit>` shape.

## A BARE instance literal shares the same slot

**Invariant: a `#P"..."` / `#S(...)` in CODE position -- outside any `quote` -- is one
shared constant per site on all four backends.** `(eq (fp) (fp))` for
`(defun fp () #P"a/b.txt")` is `T`.

The one literal family NOT following `.kb/array-literals.md`'s freshness rule: an
instance is self-evaluating (CLHS 3.1.2.1.3), so the interpreter answers it from
`LispEvaluator.eval`'s `LispInstance` arm, which also carries spliced live instances and
cannot tell them apart -- no `LiteralArrays`-style materialization is possible there, so
the compile side meets the interpreter.

Mechanics = the memo above verbatim: `JvmQuoteCompiler.emitSharedConstant` and
`WasmQuoteCompiler.emitSharedConstant` are the extracted wrappers, called by `compile`
for a quoted aggregate and by `compileLiteralInstance` for a bare one, sharing the
`_qd$N` pool and the module-global table. An instance NESTED inside quoted data was
already covered by its enclosing datum's slot; a program with no bare instance literal is
byte-identical to before.

Size cost: ~+40 byte floor on wasm (shaken wrappers' orphaned null globals, ~7 bytes
each; `WasmTreeShaker` does not drop globals); a few hundred bytes on large `.class`
outputs. Interpreter: zero.

## Not covered

- Two DIFFERENT quote sites spelling the same text are not `eq` to each other on the
  compile paths (one field per datum identity), nor generally on the interpreter. CLHS
  permits but does not require coalescing.
- `--no-gc` is scalar-only (`.kb/no-gc-scalar-wasm.md`), so the topic does not arise.

## Where to look

- `codegen/jvm/JvmQuoteCompiler.{compile,emitSharedConstant,compileLiteralInstance}` +
  `JvmLispCompiler.QuotePool`.
- `codegen/wasm/WasmQuoteCompiler` (same three) + `WasmLispCompiler.QuoteGlobals`, the
  global-section append, `WasmAsyncEmit`'s context copy.
- `LispEvaluator.evalQuote` and `eval`'s `LispInstance` arm -- both must keep handing the
  datum back verbatim.

## Tests

- ci-spec `quoted-datum-shared-cross-backend`, `instance-literal-shared-cross-backend`
- `LispEvaluatorTest.aQuotedDatumIsOneSharedConstantOnEveryBackend`,
  `.anInstanceLiteralIsOneSharedConstantOnEveryBackend`
- `JvmLispCompilerTest.aQuotedDatumIsOneSharedConstantAcrossEvaluations`,
  `.aBareInstanceLiteralIsOneSharedConstantAcrossEvaluations`
- `WasmLispCompilerIntegrationTest` (same two names, Preview 1 AND component)
