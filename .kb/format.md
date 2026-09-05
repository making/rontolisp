# `format`: two renderings, one directive set

A LITERAL control string is parsed at COMPILE time by `LispMacroExpander.expandFormat`
(`FmtParser` -> `FmtOp`s -> `%string-concat` / `princ` calls); a RUNTIME control value goes to
`%fmt-render`, a Lisp-source interpreter injected once per program (`macro/format-render.lisp`,
`format-render-slash.lisp`, `-stub.lisp`, `macro/FormatRenderer.java`).

**Invariant: the same control string and arguments render to the same text whichever path
renders them, on all four backends.** Pinned by
`FormatRendererTest.staticAndRuntimeRenderingAgree` (a table run through BOTH paths) and
ci-spec `format-runtime-control-string`; add a directive to one path and you add a row. Two
implementations is a deliberate cost: an ordinary `(format t "...")` compiles to concatenation
with no parser and no renderer in the artifact.

## What reaches the runtime renderer
Six ways into `(%fmt-render control arguments)`: a computed control expression; `#'format` as a
value (`BuiltinFunctionWrappers.formatWrapper`, or the interpreter's `format` `LispFunction`);
`~?`/`~@?`; a literal control the static parser DECLINES (`UnsupportedOperationException` --
justification `~<`, an argument-divergent `~[` nested in a composite, `~t`, `~p` -- so
`expandFormat` falls back rather than failing the compile); a condition's `format-control` slot
via `%format-condition` ([error-handling.md](error-handling.md)); and
`(error/warn/signal/cerror <computed datum> args...)` with a run-time STRING datum.

## What the LITERAL path lowers to
A `t` destination lowers to print calls, a `nil` one to `%string-concat` pieces
(`formatOutputForms` / `opsToPieces`). Four rules, all four backends:

- A self-evaluating literal argument is NOT bound to a `__format_arg` temp (`formatArgExprs`);
  the temps exist so an argument is evaluated once, left to right, even when a directive reads
  a position twice (`~:*`). **The renderer gate calls the same function**, so predicted and
  built shapes cannot diverge.
- The radix directives answer a NON-NUMBER as if by `~A` (CLHS 22.3.2): `radixIntegerExpr` and
  `decimalExpr` close with `(if (integerp x) DIGITS (%princ-piece x))`, `numberp` for
  `~:D`/`~@D`.
- Every piece renders through `%princ-piece`/`%prin1-piece`, never the public
  `princ-to-string`/`prin1-to-string` -- same print-object routing without the mutable-result
  wrap ([string-write-runtime.md](string-write-runtime.md)). Pinned by
  `LispMacroExpanderTest.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer`.
- A piece that IS such a call prints straight to the destination (`printPiece` emits
  `(princ x)`/`(prin1 x)`), matching the two-element call SHAPE only, so a padded or composite
  piece keeps building its string.

Together these put a literal in FRONT of a backend's literal fold
([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)).

## `~F` / `~$` lower to ONE call, and must keep doing so
`decimalFloatExpr` emits exactly `(%fixed-decimal value places int-digits plus-p)` --
`compiler/FixedDecimal` in the interpreter, `_fixdec` on the JVM (`JvmNumericRuntimeBuilder`),
`_fixed_dec` on WASM (`WasmFixedDecimalRuntimeBuilder`); the renderer's `%fmt-fixed` is a
`numberp` guard in front of the same call.

- Cross-backend contract: `10^places` by repeated multiplication (WASM has no `pow`),
  `Math.rint` = `f64.nearest` = round half to even, `(long)` of a double =
  `i64.trunc_sat_f64_s` = SATURATING, so `~,2F` of `1e30` is `92233720368547758.07`. `places`
  and `int-digits` clamp to `[0, FixedDecimal.MAX_DIGITS]`. `FixedDecimalTest`.
- The piece goes out through `write-string`, not `princ`: it is a string by construction, and
  `princ` of an opaque-typed value keeps the generic float printer reachable. `write-string`
  tracks the column the same way. These sites skip the character-vector normalizer via
  `compiler/StringValuedForms.certainlyString` -- a closed, conservative set, since a false
  true drops a needed normalization.
- **A future `~F` case the primitive cannot express goes INTO the primitive, never back into an
  inline arm**; `LispMacroExpanderTest.aFixedDecimalDirectiveIsOneCallAndNotAnInlinedScaleRoundSliceExpansion`
  fails the moment `round`/`princ-to-string`/`subseq`/`%string-concat` reappear.

## Injection (compile path) and lazy load (interpreter)
`LispMacroExpander.expandTopLevelDefinitions` appends `FormatRenderer.defuns()` at BOTH exits
(`withFormatRenderer`) -- including the early "nothing to splice" fast path that the plainest
runtime-control programs take. The gate scans the PRE-EXPANSION program for (a) a renderer call
already present; (b) `#'format`; (c) a `(format ...)` whose control the static path will
decline, decided by running the SAME `FmtParser`; (d) a signal designator with a computed datum
and arguments after it (`signalRendersRuntimeControl`, repeating `expandSignalDesignatorInner`'s
case split because the expansion it predicts has not run yet). (d) carries the renderer for
`(warn ctrl a b)`; only `error` also injects `%error-runtime`. A program with no way in carries
none of the renderer (~114 KB of wasm, NOT tree-shakeable -- every arm is reachable from
`%fmt-render`). The interpreter cannot inject top-level defuns, so
`LispEvaluator.ensureFormatRendererLoaded` evaluates the same forms on the first resolution of a
`%FMT-` name.

### The `~/name/` arm is injected SEPARATELY
`%fmt-user-function` lives in `format-render-slash.lisp`; `withFormatRenderer` appends either it
or `format-render-slash-stub.lisp` -- never both, never neither, since `%fmt-directive`'s `#\/`
arm calls the name unconditionally. That arm resolves a function out of runtime data, exactly
the condition under which `--optimize`'s funcall-dispatch gate must keep every function
dispatchable ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)).

- Presence is signalled BY NAME: `FormatRenderer.FUNCTION_DESIGNATOR`
  (`%fmt-function-designator`, defined only by the real arm) is in `RuntimeNameProducers`'
  trigger set. The gate is `FormatRenderer.namesFunctionDesignator` -- does any STRING LITERAL
  (post-splice, post-prune) spell a `~/name/` -- plus `--dynamic`. Compile-path only; the
  interpreter always loads the real arm.
- `FormatRenderer.functionDesignatorNames` is the ONE scanner behind both this gate and
  `LibraryDefunPruner`'s "a `~/name/` is a function reference" rule.
- **Trap: the stub's message must contain no TILDE.** A signalled condition carries the rendered
  text in `format-control` and printing it renders that text AGAIN as a control string, so a
  directive in the message re-enters the stub outside the `handler-case` that caught the first
  one. Pinned by
  `JvmLispCompilerTest.formatUserFunctionDirectiveSignalsWhenTheCompileNeverSawTheDirective`
  and its WASM twin.

## Why the renderer is Lisp source
`FormatRenderer` reads `format-render.lisp` with the real `LispReader` -- the reason `macro`
sits ABOVE `reader` in the dependency order. Many small defuns on purpose
([wasm-function-body-size.md](wasm-function-body-size.md)); the resource needs a
`resource-config.json` entry to survive native-image (`NativeImageResourceConfigTest`).

## The argument list is a materialized VECTOR
**Invariant: inside the renderer `all` is not the argument list — it is the pair
`(list <the list as given> <a vector of it, or nil>)` that `%fmt-args` builds once per rendering
LEVEL, and every read goes through `%fmt-arg` / `%fmt-count`.** A read the vector cannot serve
falls back to the very `(nth i list)` / `(length list)` the renderer used to make, so no answer
moves; before it, `(format nil "~{~a~}" <n-element list>)` was quadratic in `n`. A monotone cons
cursor cannot serve it -- `~*`, `~:*`, `~n@*` and `~?`/`~{` recursion make the access RANDOM.

- Only a PROPER list is materialized, and only at 8+ elements; a dotted list, a non-sequence, a
  string, a vector and a short list get `(list x nil)`.
- `%fmt-arg` consults the vector only for an index INSIDE it (negative or past the end falls
  through to `(nth i list)`, reproducing NIL past the end); `%fmt-count` falls back to
  `(length x)`, so `(format nil "~{~a~}" 5)` still renders nothing.
- The pair is rebuilt per NESTED level: `~{`'s list argument, `~:{`/`~:@{`'s per-pass sublist, a
  logical block's list argument, `~?`'s list through `%fmt-render`.
- The iteration directives collect their pieces and join once: `%fmt-join` halves the piece list
  until one string is left, O(n log n) characters copied, no mutable buffer.
- **Trap: a new directive arm reading `all` must use those three functions.** `(nth i all)` reads
  the wrapper cons, and the renderer never signals, so it would answer quietly wrong text.
  `%fmt-cat`-per-character inside `%fmt-run` stays quadratic in the CONTROL STRING.

## Deliberate divergences
- **The renderer never signals**: a malformed control, an unknown directive, an unterminated
  `~{`, a missing argument all render as text (`NIL` for the missing argument); the literal path
  signals the same at EXPANSION time. A condition report must not fail while reporting -- do not
  "fix" this. One exception: the absent `~/name/` arm, a missing capability rather than bad input.
- **`~t`, `~p`, `~<...~>` and `~/name/` are renderer-only.** If the static path grows them, drop
  them from this list, not from the renderer.
- **`~<...~>` is JUSTIFICATION, `~<...~:>` a LOGICAL BLOCK, and the closing directive decides.**
  A justification's `~;` segments consume arguments in turn; a logical block's first section is
  the prefix (a `~@;` separator makes it per-line) and, with three sections, the last is the
  suffix, neither consuming an argument; a block WITHOUT `@` takes one LIST argument as its
  whole argument list. The LAYOUT does not happen -- no `:mincol` padding, no right-margin
  wrapping, only the mandatory `~:@_` (gated on `*print-pretty*`) breaks a line, `~i` inert --
  all for want of the stream's column. [pretty-printer.md](pretty-printer.md).
- **`~/name/` resolves as if by `find-symbol`, INTERNAL spelling first** (`:` and `::` equivalent,
  CLHS 22.3.5.4): `find-symbol`'s answer, then `PKG::NAME`, then `PKG:NAME`, first `fboundp`
  wins. It opens and closes its string stream by hand, never `with-output-to-string`, because the
  WASM EH gate scans for a `with-*` form and would put a tag section into modules that catch
  nothing.
- **`~W` renders as `prin1` and never reads `*print-escape*`** (the static path expands in Pass 2,
  AFTER `injectMvSpillGlobal` decides which printer-mode `defvar`s the program gets; a read would
  need its own scan trigger, `LispMacroExpander.mentionsPrinterVariable` being the shape to
  extend). All three spellings are one call, no prefix parameters; it DOES honor `*print-case*`.
  `.todo/041` owns the remaining reads. **Trigger: when the printer entry points honor the control
  variables past `write`'s own keywords, `~W` must consult `*print-escape*`/`*print-readably*` as
  `write` does.** ci-spec `format-directive-write`.
- `~r` without a radix prints decimal digits (English cardinals/ordinals unimplemented);
  `~x`/`~o`/`~b`/`~r` answer UPPERCASE digits on both paths (`ClWhoE2eTest`); `~&` measures the
  column from the text rendered so far, the literal path's `t` destination using the real one.
- **A signal's runtime control renders EAGERLY, into the message** -- the condition carries the
  rendered text in `format-control` and nil `format-arguments`, on BOTH paths
  ([error-handling.md](error-handling.md)).

## The float printer: one Schubfach selection on all four backends
Free-format float text (`print`/`princ`/`prin1`, `princ-to-string`, `format ~A`/`~S`, the array
printers, jzon JSON output) is **byte-identical on all four backends**: the shortest decimal that
reads back as the same IEEE value, chosen exactly as `Double.toString` chooses it (Schubfach;
fewest round-tripping digits, two-digit minimum, closest to the value, ties to the even
significand), with Java's notation thresholds (plain for decimal exponent -3..6) and CL's
lowercase exponent marker. Authority: `am.ik.rontolisp.FloatText` (`doubleText` =
`Double.toString` + the `E -> e` rewrite, `singleText` likewise).

- Interpreter `LispDouble.print()` / `Environment.displayString`/`printString`; JVM
  `_lispToString`/`_lispToDisplayString`; WASM GC and `--no-gc` a hand-emitted Schubfach
  (`WasmSchubfachRuntimeBuilder`: `_f64_dec` selects digits+exponent, `_dec_fmt` renders, with
  `_schub_umulhi`/`_schub_g`/`_schub_rop` beneath).
- The 617-entry 126-bit power table compresses to a **~755-byte blob** (`SchubfachTables`): every
  27th entry exactly, a `5^j` multiplier table, and a 2-bit correction per entry derived at emit
  time by comparing a bit-exact replay of the runtime recomposition against the exact BigInteger
  value -- **the build fails if a correction ever leaves 2 bits**, so the table cannot drift.
  `SchubfachTables` also mirrors the u64 instruction sequence the wasm bodies perform, pinned by
  `SchubfachTablesTest` against `FloatText` over millions of values (exhaustive tiny denormals
  included), plus ci-spec `ieee-float-shortest-round-trip-printing`.
- **Single-float width**: scalars are all `double-float`, but a packed `#f(...)` element prints at
  its **f32** width (`FloatText.singleText`, `_f32_dec` on wasm, a transient `Float`/`TYPE_F32BOX`
  box on the JVM/GC print path) so `#f(0.1)` round-trips; `aref` still answers the widened double.
- ~2.7 KB of code plus the table; a program whose only prints are literals folds them to static
  text (`WasmLiteralPrint`) and carries none of it. Not touched: `~F`/`~$`/`~E` keep their
  fixed-decimal renderers (a REQUESTED digit count), and `read`/`parse` stay on the
  eisel-lemire/parseDouble side.
