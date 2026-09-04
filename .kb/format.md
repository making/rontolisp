# `format`: two renderings, one directive set

| control string | who renders it | where |
|---|---|---|
| a literal (`(format nil "~a" x)`) | `LispMacroExpander.expandFormat` — parses at COMPILE time and lowers to string pieces (`FmtParser` -> `FmtOp`s -> `%string-concat` / `princ` calls) | `macro/LispMacroExpander.java` |
| a runtime value | `%fmt-render`, a Lisp-source interpreter over the control string, injected once per program | `macro/format-render.lisp` (+ `format-render-slash.lisp` / `-stub.lisp`) + `macro/FormatRenderer.java` |

**Invariant: the same control string and arguments render to the same text whichever path renders them, on all four backends.** Pinned by `FormatRendererTest.staticAndRuntimeRenderingAgree` (a table run through BOTH paths) and ci-spec `format-runtime-control-string`. Add a directive to one path and you add a row to that table.

Two implementations is a deliberate cost: the literal path exists so an ordinary `(format t "...")` compiles to concatenation, with no control-string parsing and no renderer in the artifact.

## What reaches the runtime renderer

Six ways in, all funnelling into `(%fmt-render control arguments)`:

1. a computed control expression (cl-who's `escape-string`; cl-postgres' server message);
2. `#'format` as a value — `BuiltinFunctionWrappers.formatWrapper` (compile path) and the `format` `LispFunction` in `LispEvaluator` (interpreter) both call the renderer;
3. `~?` / `~@?` (the inner control is data by definition);
4. a literal control the static parser DECLINES (`UnsupportedOperationException` — justification `~<`, an argument-divergent `~[` nested in a composite, `~t`, `~p`): `expandFormat` falls back rather than failing the compile;
5. a condition's `format-control` slot — `%format-condition` (`.kb/error-handling.md`);
6. `(error/warn/signal/cerror <computed datum> args...)` where the datum is a STRING at run time.

## What the LITERAL path lowers to

A `t` destination lowers to print calls, a `nil` one to `%string-concat` pieces (`formatOutputForms` / `opsToPieces`). Four rules, all four backends:

- **A self-evaluating literal argument is NOT bound to a `__format_arg` temp.** The temps exist so an argument is evaluated exactly once, left to right, even when a directive reads a position twice (`~:*`, a conditional's re-parsed remainder); a literal needs neither guarantee, so `formatArgExprs` substitutes it directly. **The renderer gate calls the same function**, so predicted and built shapes cannot diverge.
- **The radix directives answer a NON-NUMBER as if by `~A`** (CLHS 22.3.2): `~D`, `~B`, `~O`, `~X`, `~R` given a non-integer print it in `~A` format and decimal base. The runtime renderer always did (`%fmt-dec` / `%fmt-radix` guards); the static expansion died inside the digit loop with `Expected integer`. `radixIntegerExpr` and `decimalExpr` now close with `(if (integerp x) DIGITS (%princ-piece x))` (`numberp` for `~:D`/`~@D`). Found through cl-unicode's `(format nil "HANGUL SYLLABLE ~X" (compute-hangul-name cp))` over a STRING.
- **Every piece renders through `%princ-piece` / `%prin1-piece`, never the public `princ-to-string` / `prin1-to-string`.** The piece names are the same print-object-routing conversion WITHOUT the mutable-result wrap the public names finish with on the compile paths (`.kb/string-write-runtime.md`); a piece is consumed by `%string-concat` or written to the destination and never reaches the program. Pinned by `LispMacroExpanderTest.anExpanderBuiltStringPieceIsTheInternalConversionNotThePublicProducer`.
- **A piece that IS a `%princ-piece` / `%prin1-piece` call prints straight to the destination.** `printPiece` emits `(princ x)` / `(prin1 x)` instead of wrapping, so no intermediate string is built. It matches the two-element call SHAPE only, so a padded or composite piece (`~10a` wraps in `padExpr`, `~:a` in an `if`) keeps building its string, as it must.

Together they put a literal in FRONT of a backend's literal fold instead of behind a variable reference (`.kb/optimize-dead-code-elimination.md`, "the print family's literal fold").

## `~F` / `~$` lower to ONE call, and must keep doing so

`decimalFloatExpr` emits exactly `(%fixed-decimal value places int-digits plus-p)`. The primitive is `compiler/FixedDecimal` in the interpreter, `_fixdec` on the JVM (`JvmNumericRuntimeBuilder`) and `_fixed_dec` on WASM (`WasmFixedDecimalRuntimeBuilder`); the runtime renderer's `%fmt-fixed` is a `numberp` guard in front of the same call, so the two paths cannot disagree about a digit.

- **Why the shape matters**: the directive used to expand INLINE into eight ordinary Lisp forms (scale by `10^d`, `round`, `princ-to-string`, punch in a point with `subseq`/`%string-concat`), which emitted every generic operation with its full i31/bignum/bigint/ratio/float ladder at every site and dragged the bignum-capable integer path into float-only programs.
- **The algorithm is a cross-backend contract**: `10^places` by repeated multiplication (WASM has no `pow`), `Math.rint` = `f64.nearest` = round half to even, and `(long)` of a double = `i64.trunc_sat_f64_s` = SATURATING — so a magnitude past `2^63` renders as `Long.MAX_VALUE`'s digits (`~,2F` of `1e30` is `92233720368547758.07`). `places` and `int-digits` are clamped to `[0, FixedDecimal.MAX_DIGITS]` so a computed `~v,vF` cannot ask for an unbounded digit buffer. Pinned by `FixedDecimalTest` and the `~f` / `~$` rows of `staticAndRuntimeRenderingAgree`.
- **A `%fixed-decimal` piece goes out through `write-string`, not `princ`.** It is a string by construction, and `princ` of an opaque-typed value keeps the generic printer (hence the float printer, several KB) reachable in a program that no longer prints a float. `write-string` tracks the output column the same way, so a following `~&` is unaffected. The `write-string` sites also skip the character-vector normalizer for an argument that CANNOT be one (`compiler/StringValuedForms.certainlyString`; `_charvec_to_str` plus `_charvec_p` is 708 bytes of WASM) — that set is closed and conservative, since answering true for something that can also be a character vector drops a needed normalization.
- **Drift it retired**: the two paths scaled by different powers of ten — the static one by a `long` `pow10` that OVERFLOWS past `10^18`, the renderer by `(expt 10.0 places)`. The agreement table now carries a large-`places` row.
- **Re-evaluation trigger**: a future `~F` case the primitive cannot express goes INTO the primitive, never back into an inline arm. `LispMacroExpanderTest.aFixedDecimalDirectiveIsOneCallAndNotAnInlinedScaleRoundSliceExpansion` fails the moment `round` / `princ-to-string` / `subseq` / `%string-concat` reappear in the lowering.

## Injection (compile path) and lazy load (interpreter)

`LispMacroExpander.expandTopLevelDefinitions` appends `FormatRenderer.defuns()` at BOTH exits (`withFormatRenderer`) — the definition-splicing one and the early "nothing to splice" fast path, which the plainest runtime-control programs take. The gate scans the PRE-EXPANSION program (expression expansion happens per form in Pass 2 and cannot add a top-level defun) for:

(a) a renderer call already present (the condition-report runtime, a spliced library); (b) `#'format`; (c) a `(format ...)` call whose control the static path will decline — decided by running the SAME `FmtParser` the expansion runs; (d) a signal designator with a computed datum and arguments after it (`signalRendersRuntimeControl`, repeating `expandSignalDesignatorInner`'s case split, because the expansion it predicts has not run yet). (d) carries the renderer for `(warn ctrl a b)`: only `error` also injects `%error-runtime`, whose body (a) would have seen.

A program with no way in carries none of the renderer (which is otherwise ~114 KB of wasm and NOT tree-shakeable — every arm is reachable from `%fmt-render`, since the control string is only known at run time). The interpreter cannot inject top-level defuns, so `LispEvaluator` evaluates the same forms into the global environment on the first resolution of a `%FMT-` name (`ensureFormatRendererLoaded`, the `%condition-report-str` pattern).

### The `~/name/` arm is injected SEPARATELY

`%fmt-user-function` and the designator resolution under it live in `format-render-slash.lisp`; `withFormatRenderer` appends either it or `format-render-slash-stub.lisp` — never both, never neither, since `%fmt-directive`'s `#\/` arm calls the name unconditionally.

- **Why**: that arm resolves a function out of a CONTROL STRING — runtime data — which is exactly the condition under which `--optimize`'s funcall-dispatch gate must keep every function dispatchable (`.kb/optimize-dead-code-elimination.md`). The renderer is spliced into every computed-control program, so ONE arm was holding the gate open for every library program.
- The arm's presence is signalled to the gate BY NAME: `FormatRenderer.FUNCTION_DESIGNATOR` (`%fmt-function-designator`, defined only by the real arm, never by the stub) is in `RuntimeNameProducers`' trigger set.
- **The gate**: `FormatRenderer.namesFunctionDesignator` — does any STRING LITERAL in the program (post-splice, post-prune) spell a `~/name/` directive — plus `--dynamic`. A control ASSEMBLED at run time out of pieces that never spell the directive gets the stub, which signals (the `.kb/clack.md` call-time-error policy). Compile-path only; the interpreter always loads the real arm.
- `FormatRenderer.functionDesignatorNames` is the ONE scanner behind both this gate and `LibraryDefunPruner`'s "a `~/name/` is a function reference" rule, so "the pruner kept this function" and "the arm that calls it was injected" cannot disagree.
- **Trap: the stub's message must contain no TILDE.** A signalled condition carries the rendered text in `format-control`, and printing the condition renders that text AGAIN as a control string (`%format-condition`), so spelling the directive in the message made reporting the error re-enter the stub, outside the `handler-case` that caught the first one. Pinned by `JvmLispCompilerTest.formatUserFunctionDirectiveSignalsWhenTheCompileNeverSawTheDirective` and its WASM twin, which read the message back through `~a`.

## Why the renderer is Lisp source

`FormatRenderer` reads `format-render.lisp` with the real `LispReader`. That is why the `macro` package sits ABOVE `reader` in the dependency order: an expander pass may BUILD the AST it injects by reading Lisp instead of assembling `LispCons` nodes in Java. The definitions are many small defuns on purpose: one emitted WASM function body must not grow without bound (`.kb/wasm-function-body-size.md`). The resource needs a `resource-config.json` entry to survive native-image (`NativeImageResourceConfigTest` enforces it).

## The argument list is a materialized VECTOR

**Invariant: inside the renderer `all` is not the argument list — it is the pair `(list <the list as given> <a vector of it, or nil>)` that `%fmt-args` builds once per rendering LEVEL, and every read goes through `%fmt-arg` / `%fmt-count`. A read the vector cannot serve falls back to the very `(nth i list)` / `(length list)` the renderer used to make, so no answer moves.**

Before it, `(nth i all)` per directive plus two `(length items)` per iteration pass made `(format nil "~{~a~}" <n-element list>)` quadratic in `n` on every path reaching the renderer.

- **A cursor was the wrong shape**: the four repositioning directives (`~*`, `~:*`, `~n@*`, plus `~?` / `~{` recursing) make the access pattern genuinely RANDOM, and a monotone cons cursor (the shape the rest of the `elt`-per-element family took, `.kb/seq-coerce-runtime.md`) cannot serve it without a re-seed costing the walk it removes.
- `%fmt-args` walks the argument once. **Only a PROPER list is materialized**, and only at 8+ elements (a cost threshold; both paths answer identically). A dotted list, a non-sequence, a string, a vector and a short list get `(list x nil)` and keep the walk.
- `%fmt-arg` consults the vector only for an index INSIDE it; a negative index and one past the end fall through to `(nth i list)`, reproducing NIL past the end. `%fmt-count` falls back to `(length x)`, so `(format nil "~{~a~}" 5)` still renders nothing.
- The pair is rebuilt per NESTED level — `~{`'s list argument, `~:{`'s and `~:@{`'s per-pass sublist, a logical block's list argument, `~?`'s argument list through `%fmt-render`.
- **The iteration directives collect their pieces and join once.** `%fmt-join` halves the piece list until one string is left: O(n log n) characters copied, no mutable buffer (the renderer has none on any backend), same `%fmt-cat` underneath. The old per-pass `(%fmt-cat acc piece)` was quadratic in the OUTPUT even after reads became O(1).
- **Re-evaluation trigger:** the pair is built by `%fmt-args` and read by `%fmt-arg` / `%fmt-count` and by NOTHING else. A new directive arm reading `all` must use those three; spelling `(nth i all)` again would read the wrapper cons rather than an argument, and the renderer never signals, so it would answer quietly wrong text. The `%fmt-cat`-per-character accumulation inside `%fmt-run` is still quadratic in the CONTROL STRING's length — irrelevant at the length control strings have.

## Deliberate divergences

- **The renderer never signals.** A malformed control, an unknown directive, an unterminated `~{`, a missing argument all render as text (`NIL` for the missing argument). The literal path signals the same problems at EXPANSION time. Reason: a runtime control usually arrives with the data being reported, and a condition report must not fail while reporting. Do not "fix" this by signalling.
- **One exception: the absent `~/name/` arm.** That stub is not bad input to a working renderer but a capability the artifact does not contain, and rendering the directive as text would drop a report's payload.
- **`~t`, `~p`, `~<...~>` and `~/name/` are renderer-only.** The static path declines them and falls back. If it ever grows them, drop them from this list, not from the renderer; `staticAndRuntimeRenderingAgree` already carries logical-block rows.
- **`~<...~>` is JUSTIFICATION, `~<...~:>` a LOGICAL BLOCK, and the closing directive decides.** The SECTION rules are real: a justification's `~;` segments consume arguments in turn; a logical block's first section is the prefix (a `~@;` separator makes it a per-line prefix, same text without line breaks) and, with three sections, the last is the suffix, neither consuming an argument; a block WITHOUT `@` takes one argument — a LIST — as its whole argument list, which is why esrap's `(format s "~2@T~<~@;~A~:>" (list line))` prints the line, not the list. What does NOT happen is the LAYOUT: no padding to `:mincol`, no wrapping at the right margin, and only the MANDATORY conditional newline (`~:@_`, gated on `*print-pretty*`) breaks a line — deciding the other three needs the stream's current column. `~i` is inert for the same reason. `.kb/pretty-printer.md`.
- **`~/name/` resolves the name as if by `find-symbol`, INTERNAL spelling first.** `:` and `::` are equivalent here (CLHS 22.3.5.4) and a library rarely exports the function it names, so `%fmt-function-designator` tries `find-symbol`'s answer, then `PKG::NAME`, then `PKG:NAME`, picking the first `fboundp`. It opens and closes its string stream by hand rather than with `with-output-to-string`: the WASM exception-handling gate scans the program for a `with-*` form, and one here would put a tag section into modules that catch nothing. **A `~/name/` is a function REFERENCE and the only trace of one**: `LibraryDefunPruner.formatFunctionNames` scans string literals for it, or the tree-shaker would delete the function the report calls.
- **`~r` without a radix prints decimal digits.** English cardinals/ordinals are not implemented on either path.
- **`~W` renders as `prin1`, and never reads `*print-escape*`.** CL defines it as `write` under the CURRENT printer variables (i.e. `princ` when `*print-escape*` is nil); both paths render `prin1-to-string` unconditionally, because the static path expands in Pass 2, AFTER the scan that decides which printer-mode `defvar`s a compiled program gets (`injectMvSpillGlobal`) — a `*print-escape*` read would need its own scan trigger the way `print-unreadable-object` and the `write-to-string` keywords have one (`LispMacroExpander.mentionsPrinterVariable` is the shape to extend). Its modifiers bind variables of which `~@W`'s two now change text (`*print-level*`/`*print-length*` nil would UNDO an enclosing truncation) but are still not bound here, so all three spellings are one call, and it takes no prefix parameters (`.todo/041` owns both reads). It DOES honor `*print-case*`. **Re-evaluation trigger**: when the printer entry points honor the control variables past `write`'s own keywords, `~W` must consult `*print-escape*`/`*print-readably*` exactly as `write` does, and the static path then owes the scan trigger. Pinned by the `~w` rows of `staticAndRuntimeRenderingAgree` and ci-spec `format-directive-write`.
- **`~&` measures the column from the text rendered so far** (an empty accumulator counts as the start of a line), because the renderer answers a string and cannot see the stream's column. The literal path's `t` destination uses the real column.
- **`~x`/`~o`/`~b`/`~r` answer UPPERCASE digits** on both paths, as CL does (the old cut-down fallback answered lowercase, which is why cl-who's `&#x~x;` changed case when the renderer landed — `ClWhoE2eTest`).
- **A signal's runtime control renders EAGERLY, into the message** — the condition a handler sees carries the rendered text in `format-control` and nil `format-arguments`. BOTH paths deviate the same way. `.kb/error-handling.md`.

## The float printer: one Schubfach selection on all four backends

Free-format float text (`print`/`princ`/`prin1`, `princ-to-string`, `format ~A`/`~S`, the array printers, JSON output through the jzon shim) is **byte-identical on all four backends**: the shortest decimal that reads back as the same IEEE value, chosen exactly as `Double.toString` chooses it (Schubfach: fewest digits that round-trip, refined to a two-digit minimum, closest to the value, ties to the even significand), with Java's notation thresholds (plain for decimal exponent -3..6, scientific otherwise) and CL's lowercase exponent marker (`1.0e10`). Authority: `am.ik.rontolisp.FloatText` (root package) — `doubleText` = `Double.toString` + the `E -> e` rewrite, `singleText` = `Float.toString` likewise.

- **Interpreter**: `LispDouble.print()` / `Environment.displayString`/`printString` call `FloatText`.
- **JVM**: the emitted `_lispToString` / `_lispToDisplayString` call `Double.toString` then `String.replace("E", "e")`.
- **WASM GC + `--no-gc`**: a hand-emitted Schubfach (`WasmSchubfachRuntimeBuilder`, shared bodies parameterized only by function indices and addresses): `_f64_dec` selects digits+exponent, `_dec_fmt` renders the spelling, with `_schub_umulhi` / `_schub_g` / `_schub_rop` beneath. The 617-entry 126-bit power table is compressed to a **~755-byte blob** (`SchubfachTables`): every 27th entry exactly, a `5^j` multiplier table, and a 2-bit correction per entry **derived at emit time by comparing a bit-exact replay of the runtime recomposition against the exact BigInteger value** — the build fails if a correction ever leaves 2 bits, so the compressed table cannot drift. `SchubfachTables` also carries a Java mirror of the precise u64 instruction sequence the wasm bodies perform; `SchubfachTablesTest` pins that mirror against `FloatText` over millions of values (exhaustive tiny denormals included — where the JDK's two-digit refinement differs from a naive shortest selection), and the per-backend corpus tests + ci-spec `ieee-float-shortest-round-trip-printing` pin the transcription.

**Single-float width**: scalars are all `double-float` (no single scalar exists), but a packed `#f(...)` array element prints at its **f32** width (`FloatText.singleText`, `_f32_dec` on wasm, a transient `Float`/`TYPE_F32BOX` box on the JVM/GC print path) so `#f(0.1)` round-trips instead of showing the widened double's 17 digits. `aref` still answers the widened double — the width lives in the array, not the scalar.

The printer runtime is ~2.7 KB of code plus the 755-byte table. The f32 half rides along whenever the generic printer is reachable; a program whose only prints are literals folds them to static text (`WasmLiteralPrint` folds floats too) and carries none of this.

**Not touched**: `~F`/`~$`/`~E` keep their own fixed-decimal renderers above (a REQUESTED digit count, not the shortest), and `read`/`parse` stay on the eisel-lemire/parseDouble side.
