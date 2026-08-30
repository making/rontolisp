# A quoted datum is ONE shared constant, on all four backends

**Invariant: every evaluation of one `quote` site answers the SAME object, on the
interpreter, the JVM and both WASM backends. `(eq (f) (f))` for `(defun f () '(1 2 3))`
is `T` everywhere, and a write through the datum is visible to the next evaluation --
the CL-conformant constant reading (CLHS leaves writing into a literal undefined; real
CLs share and corrupt exactly like this).** Pinned by the
`quoted-datum-shared-cross-backend` ci-spec case,
`JvmLispCompilerTest.aQuotedDatumIsOneSharedConstantAcrossEvaluations`,
`WasmLispCompilerIntegrationTest.aQuotedDatumIsOneSharedConstantAcrossEvaluations`
(Preview 1 AND component) and
`LispEvaluatorTest.aQuotedDatumIsOneSharedConstantOnEveryBackend`.

The complement of `.kb/array-literals.md`: a BARE array literal is a CONSTRUCTOR
(fresh per evaluation), the same syntax under `quote` is a CONSTANT (shared). The two
rules meet exactly at the `'` and neither leaks into the other -- `#(1 2 3)` in code
position stays fresh (the property `PureBuiltinFolder`'s packed-table fold rests on,
and the fold splices its results BARE, so it is untouched), `'#(1 2 3)` is shared.

## The decision (2026-08-30), and why not "fresh everywhere"

`.todo/579` closed the divergence this file replaces: the interpreter shared (its
`evalQuote` hands back the reader's datum) while all three compile paths rebuilt the
datum at the site on every evaluation, so `(eq (ql) (ql))` was `T` / `NIL` and a write
through the constant corrupted it on one backend and vanished on the others. Two
consistent answers existed and both were measured:

- **Fresh per evaluation everywhere** (what `.todo/578` chose for BARE array literals)
  is affordable on raw cost -- a 2M-iteration interpreter loop over `'(1 2 3 4 5 6 7 8)`
  ran 418 ms against 420 ms for the equivalent `(list ...)` build, so a per-eval deep
  copy roughly doubles quote's cost and no more -- but it is structurally hazardous:
  `(quote <value>)` is ALSO the interpreter's live-value splice (`quoteValue`, four
  sites, ~15 more constructions across `eval` and `macro`), materializing in
  `evalQuote` was already tried and broke `read-sequence` outright, and the
  self-evaluating `LispInstance` arm cannot distinguish a literal from a spliced
  runtime instance at all. It is also the non-conformant direction.
- **Shared everywhere** leaves the interpreter (and the splice pattern) completely
  untouched, matches what a string literal already does on all four
  (`.kb/string-write-runtime.md`), and costs only a memoization site in each compile
  backend.

Shared won. The `%UNSPELLED-QUOTE` separation `.todo/579` listed as a prerequisite is
only a prerequisite of the FRESH answer (it is what would let `evalQuote` tell a
literal from a splice); under shared the interpreter needs no such distinction and it
was deliberately not built.

## The mechanics

Only quoted AGGREGATES are memoized -- a cons, a general array, an instance, a packed
float/int array under `quote`. An atom (number, string, symbol, character, nil, t) has
no identity a program can observe diverging and keeps its inline emission. Both
backends key the memo by the DATUM'S IDENTITY (`IdentityHashMap`), so two textually
equal quote sites stay distinct objects while a macro expansion splicing ONE template
datum into several sites shares one constant across them -- exactly the interpreter's
sharing, which hands out the template's own cons at every expansion.

- **Interpreter**: unchanged. `LispEvaluator.evalQuote` hands the datum back as is,
  and must -- see the splice constraint above.
- **JVM** (`JvmQuoteCompiler.compile` + `JvmLispCompiler.QuotePool`): one private
  static **volatile** `Object` field (`_qd$N`) per datum, built LAZILY at the site --
  `GETSTATIC; DUP; IFNONNULL end; POP; <the old build>; DUP; PUTSTATIC; end:` (~12
  bytes over the build). Volatile so the racing first evaluations of two threads each
  publish a fully-constructed datum (the JMM data-race alternative can expose a
  half-written `Object[]`); the site converges on one object immediately after.
- **WASM, Preview 1 and component** (`WasmQuoteCompiler.compile` +
  `WasmLispCompiler.QuoteGlobals`): one `(mut (ref null eq)) = null` module global per
  datum, appended AFTER every fixed-index global (nothing renumbers; the count is only
  known once every body has compiled), filled lazily at the site --
  `global.get; ref.is_null; if; <build>; global.set; end; global.get` (~10 bytes).
  Wasm is single-threaded here, so lazy is race-free. The allocator is shared into
  `WasmAsyncEmit`'s fresh contexts, so a quote site in a top-level chunk or an async
  resume body reaches the one table.

**Why the JVM build is lazy at the site and NOT a `<clinit>` initializer.** A
`<clinit>` version was built first and measured: `JvmClassShaker` runs on every build
(not just `--optimize`), the injected built-in wrapper defuns (`find-package`,
`list-all-packages`, `package-use-list`, `package-used-by-list`) each quote the
package-registry tables, and with their constants pinned by `<clinit>` a three-defun
program grew 5,898 -> 18,978 bytes -- the shaken wrappers' ~1.4 KB tables all stayed.
Lazy at the site puts the build inside the method, so the shaker drops field and build
with the wrapper. Do not "simplify" this back into the `LayoutPool`/`BigIntPool`
`<clinit>` shape without re-running that measurement.

## A BARE instance literal shares the same slot (2026-08-30)

**Invariant: a `#P"..."` / `#S(...)` in CODE position -- outside any `quote` -- is one
shared constant per site on all four backends. `(eq (fp) (fp))` for
`(defun fp () #P"a/b.txt")` is `T` everywhere.** Pinned by the
`instance-literal-shared-cross-backend` ci-spec case,
`LispEvaluatorTest.anInstanceLiteralIsOneSharedConstantOnEveryBackend`,
`JvmLispCompilerTest.aBareInstanceLiteralIsOneSharedConstantAcrossEvaluations` and
`WasmLispCompilerIntegrationTest.aBareInstanceLiteralIsOneSharedConstantAcrossEvaluations`
(Preview 1 AND component).

This is the one literal family that does NOT follow `.kb/array-literals.md`'s
fresh-per-evaluation rule, and the reason is the same constraint that decided `quote`
above, one level sharper. An instance is self-evaluating (CLHS 3.1.2.1.3), so the
interpreter answers it from `LispEvaluator.eval`'s `LispInstance` arm -- which hands
the reader's own object back. That arm ALSO carries every live instance the evaluator
splices back through `(quote <value>)`, and cannot tell one from the other, so a
`LiteralArrays`-style materialization there is off the table: the interpreter cannot
move, and the compile side has to meet it. `.todo/579` left this as the remaining step
and `.todo/581` took it.

The mechanics are the memo above, verbatim -- `JvmQuoteCompiler.emitSharedConstant` and
`WasmQuoteCompiler.emitSharedConstant` are the extracted wrappers, called by `compile`
for a quoted aggregate and by `compileLiteralInstance` for a bare one, so both paths
share the `_qd$N` pool and the module-global table. Nothing else changed: an instance
NESTED inside quoted data was already covered by its enclosing datum's single slot.

Cost, measured 2026-08-30 (old jar vs new, same tree otherwise). A program with no bare
instance literal is BYTE-IDENTICAL, because the wrapper is emitted only at such a site:

| artifact | old | new | delta |
|---|---:|---:|---:|
| a 2-defun program, one `#P` + one `#S` (`.class`) | 4,805 | 4,938 | +133 |
| the same program, `.wasm --optimize` | 10,790 | 10,820 | +30 |
| `hello_world.class` / `hello_world.wasm --optimize` | 2,231 / 578 | 2,231 / 578 | 0 |
| `pi_approx.class` / `pi_approx.wasm --optimize` | 6,925 / 3,299 | 6,925 / 3,299 | 0 |
| `zlib.class` / `zlib.wasm --optimize` | 155,132 / 124,081 | 155,132 / 124,081 | 0 |

## Cost, measured 2026-08-30 (old jar vs new, same tree otherwise)

| artifact | old | new | delta |
|---|---:|---:|---:|
| `q579.class` (3 defuns) | 5,898 | 6,087 | +189 |
| `zlib.class` | 154,818 | 155,133 | +315 |
| `hello_world.wasm --optimize` | 538 | 578 | +40 |
| `pi_approx.wasm --optimize` | 3,259 | 3,299 | +40 |
| `webgl-cube --no-wasi --optimize` | 34,618 | 34,688 | +70 |
| `zlib.wasm --optimize` | 123,961 | 124,081 | +120 |

The wasm floor of +40 bytes is the shaken wrappers' orphaned null globals (~7 bytes
each; `WasmTreeShaker` does not drop globals). Interpreter: zero change.

## What this deliberately does NOT cover

- Two DIFFERENT quote sites spelling the same text are not `eq` to each other on the
  compile paths (one field per datum identity), and generally not on the interpreter
  either (two reads are two conses). CLHS permits but does not require coalescing.
- `--no-gc` is scalar-only (no cons/array values at all, `.kb/no-gc-scalar-wasm.md`),
  so the topic does not arise there.

## Where to look when this changes

- `codegen/jvm/JvmQuoteCompiler.compile` / `.emitSharedConstant` /
  `.compileLiteralInstance` + `JvmLispCompiler.QuotePool`.
- `codegen/wasm/WasmQuoteCompiler.compile` / `.emitSharedConstant` /
  `.compileLiteralInstance` + `WasmLispCompiler.QuoteGlobals` (+ the global-section
  append and `WasmAsyncEmit`'s context copy).
- `LispEvaluator.evalQuote` -- the interpreter's half, which must keep handing the
  datum back verbatim -- and `eval`'s `LispInstance` arm, which must do the same for a
  bare instance literal and for the same reason.
- `.kb/array-literals.md` -- the bare-literal freshness rule this one complements.
