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

- A BARE `#P"..."` / `#S(...)` instance literal in code position is still rebuilt per
  evaluation on the compile paths while the interpreter's self-evaluating arm shares
  it -- the last `eq` divergence of this family, owned by `.todo/581` (nothing writes
  into one today, so it is latent). The memoization machinery here is the shape a fix
  would reuse; the instance arm ALSO carries live spliced instances on the
  interpreter, so 581 must not materialize there either.
- Two DIFFERENT quote sites spelling the same text are not `eq` to each other on the
  compile paths (one field per datum identity), and generally not on the interpreter
  either (two reads are two conses). CLHS permits but does not require coalescing.
- `--no-gc` is scalar-only (no cons/array values at all, `.kb/no-gc-scalar-wasm.md`),
  so the topic does not arise there.

## Where to look when this changes

- `codegen/jvm/JvmQuoteCompiler.compile` + `JvmLispCompiler.QuotePool`.
- `codegen/wasm/WasmQuoteCompiler.compile` + `WasmLispCompiler.QuoteGlobals` (+ the
  global-section append and `WasmAsyncEmit`'s context copy).
- `LispEvaluator.evalQuote` -- the interpreter's half, which must keep handing the
  datum back verbatim.
- `.kb/array-literals.md` -- the bare-literal freshness rule this one complements.
