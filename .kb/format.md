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

`--no-gc` has neither path: its value model cannot hold the temporaries and type tests of the literal
one. `NoGcFormatLowering` spells a literal control of `~a`, `~d`, `~%` and `~~`, to `t` or `nil`, as
`princ` or as `concatenate` over `princ-to-string`, which gives the same text because `~a` and `~d`
of what that backend holds are `princ`. It runs on the defuns before any scan, because `collectLiterals`,
`usesStringOp` and the print scans read the forms as written. Anything else is refused by name.

## What reaches the runtime renderer
Six ways into `(%fmt-render control arguments)`: a computed control expression; `#'format` as a
value (`BuiltinFunctionWrappers.formatWrapper`, or the interpreter's `format` `LispFunction`);
`~?`/`~@?`; a literal control the static parser DECLINES (`UnsupportedOperationException` --
justification `~<`, an argument-divergent `~[` nested in a composite (clauses ending at different
positions; without `~:;` the no-clause outcome is one more, consuming nothing), `~t`, `~p`, or a
read past the call's own arguments (below) -- so `expandFormat` falls back rather than failing the
compile); a condition's `format-control` slot via `%format-condition`
([error-handling.md](error-handling.md)); and
`(error/warn/signal/cerror <computed datum> args...)` with a run-time STRING datum.

A FUNCTION control (CLHS 22.2.1.3) is called with a string output stream and the arguments
(`%fmt-render`'s arm), so `~?` and a computed control take one. `formatter` expands to
`#'(lambda (stream &rest args) (%fmt-apply stream "control" args))`: the renderer's `%fmt-run`, whose
cursor gives the arguments left over, which the function answers. A program spelling `formatter`
gets the renderer (`reachesFormatRenderer`; `FormatRenderer.isUsed` knows `%fmt-apply`). Consumer:
spinneret's `fast-format` compiler macro. ci-spec `format-runtime-control-string`.

## A literal control that cannot render never fails a compile
Two exceptions out of `FmtParser`, the same answer from `expandFormat` and `formatMessagePieces`
(`error`/`warn`/`signal`/`cerror`), so on all four backends, and each wrapped in
`(%compile-warning "text; checked when the call runs" form)`, which only JVM and WASM read
(`CompileWarnings.warnedForm`: placed at the located form around it, counted by
`--warnings-as-errors`; only compiled code warns, so a dropped branch says nothing):

- **`FmtShortOfArguments`** (`FmtArgs.next`/`move` past a static source; extends the declining
  exception) -> the renderer answers at the call. It has to: the parser walks every clause, so
  `(format nil "~[~a~;b~]" 1)` is short only on a clause the selector skips (SBCL 2.2.9: `"b"`), and
  the text is the renderer's. The gate needs nothing new, its `catch` already answers true. A
  divergent `~[` whose remainder is short on SOME clauses still folds those clauses to a signal
  (`clauseExprs`), the others static.
- **`IllegalArgumentException`** (ends with `~`, misplaced `~;`, missing close, `~:[` clause count,
  `~e` precision) -> `(error <the text, tilde escaped>)` after the arguments, no renderer: it would
  render this as text (below). SBCL signals at the call too and compiles the file (2026-11-04).

Before 2026-11-04 the literal path threw at expansion: the compilers refused the program (clingon's
`~:*` before any argument, on a branch only command aliases reach), the interpreter signalled at
the call without evaluating the arguments, and a short clause the selector skips signalled there
too. A clause's fold spelled `(error "...~a")`, so the signal was itself refused. Pinned by ci-spec
`format-runtime-control-signals-a-missing-argument` and
`RontoLispCliStreamsTest.warningsAsErrorsPrintsEveryWarningExitsOneAndWritesNothingOnEveryCompileBackend`.

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
- `~D` prints in decimal without a radix whatever `*print-base*`/`*print-radix*` say (CLHS
  22.3.2.2; SBCL binds both): both paths print it through the leaf `%decimal-piece` (stream twin
  `%decimal-princ`, what `printPiece` makes of it), which `expandDecimalLeaf` lowers to the
  `%decimal-text` prelude defun (binds the two) where the program carries it -- spliced only
  for a program naming a printer-control variable -- and to `%princ-piece`/`princ` elsewhere,
  so neither the expansion nor `format-render.lisp` names a variable (naming one pulls the
  case-aware renderer, [pretty-printer.md](pretty-printer.md)). The interpreter takes the
  defun while either variable is off its default. `~B`/`~O`/`~X`/`~R` build their own digits;
  their non-integer arm still reads the variables (SBCL binds the base to the radix there).
  ci-spec `format-tilde-d-prints-in-decimal-under-any-print-base`.
- Every piece renders through `%princ-piece`/`%prin1-piece`, never the public
  `princ-to-string`/`prin1-to-string` -- same print-object routing without the mutable-result
  wrap ([string-write-runtime.md](string-write-runtime.md)). Pinned by
  `LispMacroExpanderTest.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer`.
- A piece that IS such a call prints straight to the destination (`printPiece` emits
  `(princ x)`/`(prin1 x)`), matching the two-element call SHAPE only, so a padded or composite
  piece keeps building its string.

A `~&` that opens the control asks the destination, whichever path renders it (SBCL 2.2.9,
2026-11-05, every row of ci-spec `format-opening-fresh-line-asks-the-destination`): the
text is built from column 0, so `~&` is silent in it, and `(fresh-line d)` runs after the arguments and
before the write (`LispMacroExpander.formatStartsFresh`: `~&`, `~n&` with a nonzero `n`). Every destination
has a line start to ask ([standard-output-redirect.md](standard-output-redirect.md), "A stream's line start");
until it did, a `%STREAM` that was no string stream was not asked, or `(format *error-output* "~&x~%")`, the usual
spelling, would have opened every message with a blank line (uiop's fatal report did). A literal control decides
at expansion, so one with no such `~&` lowers as it did; a computed one is held in a temp and asks
`%fmt-starts-fresh-p` (`format-render.lisp`, there because a computed control means the renderer is),
at each write site: `formatDestinationDispatch`, `runtimeRenderFallback`'s `t`, the Gray rewrite, the
`#'format` wrappers, `%format-write` and the interpreter's function value. A `~&` anywhere else keeps the
text's approximation, a value piece assumed not to end a line. Before 2026-11-05 `(format s "~&x")` and
`(format t ctrl)` lost it: `(princ "a") (format *standard-output* "~&b")` printed `ab`, on all four.

`(format D (if test a b) ...)` distributes over the branches (`distributeFormat`, also for the Gray
rewrite and `formatThroughDestination`, so each branch keeps its literal control). A computed `D` is bound
first: until 2026-11-05 `test` ran before it.

Together these put a literal in FRONT of a backend's literal fold
([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)).

A `~{` body binds its items eagerly (`letItems`, `(car (nthcdr k base))`), so a read past the
list cannot be caught at the binding: a `~^` or a clause may never reach it. Instead
`FmtArgs` tracks how many elements of the pass's list are KNOWN present (`guaranteed`: 1 for a
flat pass, whose loop tests `consp`; 0 for a sublist and for a pass `~:}` forces), and a read or
`~*` beyond it adds an `FmtNeed` that `opsToPieces` wraps around the next piece as
`(if (nthcdr k base) piece (error ...))`. A non-firing `~^` and a clause set every clause covers
raise the guarantee, so `~{~a~^, ~}` and `~{~a~^ ~a~}` carry no check. Cost, measured
2026-10-30: +24 B P1 and +282 B JVM class for one `~{~a=~a~%~}`; +212 B P1 for that plus a
three-item `~:{` (four checks) under EH mode. Before it the body read NIL past the end and
ignored `~:}` (the renderer already signalled and forced).

## A string with a fill pointer
**Invariant: `(format d ...)` appends to a string with a fill pointer on all four backends, growing
it past its capacity, and answers nil** (SBCL 2.2.9, 2026-11-05: the same, for an `:adjustable` one and
a `base-char` one too). `(%format-write (%format-destination D) (format nil C ARGS...))`: two prelude
defuns, `%format-destination` the check that passes such a string ahead of `%check-stream`,
`%format-write` the dispatch on nil, a string, `t` and a stream. `StreamOperandChecks.formatThroughDestination`
builds it; the destination is still evaluated and checked first.

- Gate: both defuns are in the program where `referencedBySurfaceForm` says, the program spelling
  `:fill-pointer` and either a `format` whose destination takes a check or a `#'format` / `'format`;
  a `:fill-pointer` only in a dead library defun does not count (`LibraryDefunPruner.SYNTHESIZED_ENTRIES`).
  `formatTakesStrings` reads that one fact, after the splice, for each of the three writers:
  `StreamOperandChecks`' site rewrite, `GrayStreamsLibrary`'s `format` rewrite (which runs first; the
  `write-string`s in `%format-write` are ordinary sites of its walk, so a Gray instance still reaches its
  dispatch) and the `#'format` wrapper (`BuiltinFunctionWrappers.formatThroughDestinationWrapper`, over the
  Gray twin). Every other program lowers as it did, one `stringp` arm spared per computed destination.
- A keyword built at run time (`(intern "FILL-POINTER" :keyword)` handed to `#'make-array`) defeats the
  gate: a compile path refuses that string where the interpreter appends. `--no-gc` refuses `:fill-pointer`
  outright.
- The interpreter has no gate: `case FORMAT` takes the same rewrite for every computed destination, the
  defuns load on first call, and `#'format` writes through `%format-write`
  (`installStreamOperandChecks` lets the string past its check).
- **A string with no fill pointer stays `FORMAT: The value X is not of type STREAM`**, where SBCL signals a
  `simple-error` `X is not a string with a fill-pointer`. CLHS leaves it undefined, a handler for `error`
  takes both, and SBCL's condition on all four would cost an arm in `%check-stream-runtime` of every program
  with a computed destination, not only the gated ones (an interpreter and a compile path must not answer
  differently by what else the program spells).
- The text is written when the arguments are done, as for a stream. Pinned by ci-spec
  `format-to-a-string-with-a-fill-pointer` (standalone, no Gray) and
  `format-to-a-string-with-a-fill-pointer-beside-the-gray-dispatch`.

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

## `~E`'s scale factor
`decimalExpExpr` and `%fmt-exp` share one rule (CLHS 22.3.3.2): `k > 0` puts `k` digits before
the point, `k <= 0` puts `-k` zeros after it; the exponent counter STARTS at `1 - k`, so the
normalization loop leaves the printed exponent. The point is always printed (`~,0e` of 2.0 is
`2.e+0`, as SBCL). Not matched: SBCL's own deviations when rounding carries
(`~,3,,0E` of -9.9999 is SBCL `-1.000e+1`, here `-0.100e+2`). Pinned by ci-spec
`format-directive-exponential`.

## Injection (compile path) and lazy load (interpreter)
`LispMacroExpander.expandTopLevelDefinitions` appends `FormatRenderer.defuns()` at BOTH exits
(`withFormatRenderer`) -- including the early "nothing to splice" fast path that the plainest
runtime-control programs take. The gate scans the PRE-EXPANSION program for (a) a renderer call
already present; (b) `#'format`; (c) a `(format ...)` whose control the static path will
decline, decided by running the SAME `FmtParser`; (d) a signal designator with a computed datum
and arguments after it, or with a LITERAL control whose message `formatMessagePieces` builds as a
renderer call (`~p` and the other declined directives) -- `signalRendersRuntimeControl`, repeating
`expandSignalDesignatorInner`'s case split because the expansion it predicts has not run yet, and
calling `formatMessagePieces` itself for the literal. (d) carries the renderer for
`(warn ctrl a b)`; only `error` also injects `%error-runtime`. Until 2026-09-29 the literal half was
missing: `(error "~d arg~:p" n)` in a program with no `handler-case` compiled to a call of the
undefined `%fmt-render` (`JvmLispCompilerTest.anUncaughtErrorWhoseLiteralControlOnlyTheRendererCanLowerReportsItsMessage`,
`WasmLispCompilerIntegrationTest.warnWithALiteralControlOnlyTheRendererCanLowerRendersIt`). (e) The
registry's condition `:report`s are scanned beside the program, as `mayCreateInstances` and
`mayCreateComplex` scan them: a typed signal of a class with a report of its own inlines that
report at the site (`expandTypedSignal`), and `define-condition` left it in the registry. Only the
routed report runtime carried it into the scan before, so under `SignalMessages.LAZY` (wasm-GC
outside EH mode) `uiop:not-implemented-error`'s `(apply #'format ...)` report compiled to a call
of an undefined `%fmt-render` and warned; the run itself was the silent trap every uncaught signal
is there (`WasmLispCompilerIntegrationTest.anUncaughtConditionOutsideEhModeStaysSilent`, now under
`UndefinedWarnings.forbid`). The cost, measured 2026-10-23: the dead-code pass drops the renderer
such a module never reaches, but the `*print-pretty*` initialization it spells stays (+48 B on
`(uiop:chdir "/tmp")`, +171 B on a two-form toy). A program with no way in carries
none of the renderer (NOT tree-shakeable -- every arm is reachable from
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
- `(%fmt-arg all i d)` reads index `i` for directive `d` and SIGNALS past either end
  (`%fmt-no-arg`, below); a non-list keeps `(nth i x)`'s answer. `%fmt-count` falls back to
  `(length x)`, so `(format nil "~{~a~}" 5)` is `LENGTH`'s `SEQUENCE` type-error (it rendered
  nothing until 2026-09-26). `%fmt-shape` scans with a nil `all`, so `%fmt-param` reads a `v`
  only when `all` is non-nil.
- The pair is rebuilt per NESTED level: `~{`'s list argument, `~:{`/`~:@{`'s per-pass sublist, a
  logical block's list argument, `~?`'s list through `%fmt-render`.
- The iteration directives collect their pieces and join once: `%fmt-join` halves the piece list
  until one string is left, O(n log n) characters copied, no mutable buffer.
- **Trap: a new directive arm reading `all` must use those three functions.** `(nth i all)` reads
  the wrapper cons and answers quietly wrong text instead of signalling past the end.
  `%fmt-cat`-per-character inside `%fmt-run` stays quadratic in the CONTROL STRING.

## Deliberate divergences
- **A malformed control renders as text**: an unknown directive, an unterminated `~{`, a trailing
  `~`; a literal control the static parser rejects signals at the call instead (above; an unknown
  directive it declines, so it renders there too). Exceptions: the absent `~/name/` arm,
  a missing capability rather than bad input, and the range errors of the no-radix `~r` below.
- **A read past the argument list SIGNALS** a `simple-error` (`format:
  not enough arguments for directive ~a`, `format: ~* moves outside the argument list`) -- every
  directive SBCL 2.2.9 rejects with `No more arguments` / `No previous argument` / `Index out of
  bounds`, measured directive by directive 2026-10-30: the argument directives, `v`, `~[` (all three
  forms), `~{`, `~?`/`~@?`, a block `~<...~:>`, `~/name/`, `~:p` at the first argument, and `~*`
  / `~:*` / `~n@*` leaving `[0, count]`. `~@{`, `~^`, `#` and `~t` still render; a forced `~:{...~:}`
  over no sublist runs against an empty one (`%fmt-sublist`), so its first read signals, as SBCL's
  does. Until 2026-10-30 the missing argument rendered `NIL` ("a report must not fail while
  reporting"); a rendered message now enters a condition only as its text control
  ([error-handling.md](error-handling.md)), so only a report whose own `:format-control` lacks its
  arguments signals while printing, as SBCL's does. Pinned by ci-spec
  `format-runtime-control-signals-a-missing-argument`, whose `ci-fmt-both` rows run a `~{` body
  through BOTH paths. A `~^` ends only the current pass of a `~:{`/`~:@{` (CLHS 22.3.9.2); until
  2026-10-30 the renderer ended the whole iteration there.
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
- `~r` without a radix (none, or `v` reading NIL) is renderer-only (the static parser declines it) and
  spells the integer: cardinal, `~:r` ordinal, `~@r` Roman 1..3999, `~:@r` old Roman 1..4999, every other
  parameter ignored; `|n| >= 10^66` (cardinal, ordinal) and the Roman ranges SIGNAL `simple-error` with
  SBCL's text -- signalled on data like a missing argument. The
  ordinal's error names `|n|` with its last two digits dropped, as SBCL does. A non-integer argument prints
  as `~a` (CLHS 22.3.2; SBCL signals a `format-error` instead). Pinned by `FormatSpelledNumbersFixture` on
  all four backends and ci-spec `format-r-without-a-radix-spells-the-number`; SBCL 2.2.9 answered all 1,312
  rows of a 0..10^100 sweep identically on the interpreter, JVM, P1 and component, literal and runtime
  control (2026-10-07).
- `~x`/`~o`/`~b`/`~r` answer UPPERCASE digits on both paths (`ClWhoE2eTest`); `~&` measures the
  column from the text rendered so far, the literal path's `t` destination using the real one and
  an opening `~&` asking the destination ("What the LITERAL path lowers to").
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
- The shortest renderers cost a small body plus the table; a program whose only prints are
  literals folds them to static
  text (`WasmLiteralPrint`) and carries none of it. Not touched: `~F`/`~$`/`~E` keep their
  fixed-decimal renderers (a REQUESTED digit count), and `read`/`parse` stay on the
  eisel-lemire/parseDouble side.
