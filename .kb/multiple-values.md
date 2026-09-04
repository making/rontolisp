# Multiple values -- syntactic tier

The `multiple-value-bind`-over-`floor`/`gethash` idioms real CL code uses, WITHOUT a runtime multiple-value representation. A `%mv-spill` global carries the cases the syntactic tier cannot.

## What ships
- `values`: a CL **function** (`CL_FUNCTIONS`, an `Environment` function, a variadic `&rest` `BuiltinFunctionWrappers` entry, an `expandValues` call-position expansion). NOT in `expandBuiltinMacro`, so `macroexpand-1` leaves `(values ...)` alone as in CL.
- `multiple-value-bind`, `multiple-value-list`, `multiple-value-call`, `nth-value` (CL_MACROS + `expandBuiltinMacro`; `multiple-value-call` as a macro deviates from CL's special operator — precedent `error`).
- Secondary values for `floor`/`ceiling`/`round`/`truncate` (quotient + remainder) and `gethash` (value + present-p), ONLY inside the consumers.
- Two-argument `(floor a b)` in ordinary contexts: `expandFloorFamilyDivisor` -> `(floor (/ a b))` (exact rational division; scalar `--no-gc` uses its float `/`, same rounding). An arity-3-only branch in the evaluator (`break` falls through to the 1-arg built-in) and all three compilers.

## The lowering (`LispMacroExpander`)
`lowerMvProducer(form, prefix)` -> `MvProducer{bindings, values}`: ordered temps (`__mv<id>...`, `MV_COUNTER`-fresh like `FLET_COUNTER`) realized as **nested single-binding lets** (`nestMvBindings`) so evaluation order is guaranteed on every backend, plus pure value expressions over the temps. `isMvProducerForm` recognizes:
- literal `(values e...)` -> a temp per argument;
- `(floor|ceiling|round|truncate a [b])` -> `q = (op a)` / `(op (/ a b))`, remainder `(- a q)` / `(- a (* q b))`;
- `(gethash k h [d])` -> a runtime `(gensym)` sentinel as the gethash default; `(eq v sentinel)` decides `(values stored-or-default present-p)`, distinguishing a stored nil from a missing key on all backends (WASM `eq` on an i31/string vs the fresh symbol is safe);
- anything else -> one temp bound to the form.

Consumers: `expandMultipleValueBind` (parallel inner `let` of the user vars; missing -> nil, surplus values evaluated in the bindings and dropped), `expandMultipleValueList` (`(list e...)` for a literal `values`, `(list form)` otherwise), `expandNthValue` (`(nth n <mv-list>)`; `nth` evaluates n first, CL order), `expandMultipleValueCall` (fn temp bound FIRST, then each producer's temps -> one direct `(funcall fnTmp v...)`; count is static, no runtime spreading, no `apply`). `expandValues` in ordinary context: 0 -> nil, 1 -> the form, n -> `(prog1 ...)`.

## The `%mv-spill` runtime channel
`values` PUBLISHES its extra values to the `%mv-spill` global (a fresh list; nil when none) as it returns its primary, for consumers that sit behind a call.
- A consumer whose producer is an unrecognized CALL clears the spill, evaluates the producer, snapshots the spill immediately (`MvProducer.rest`); value i>0 reads `(nth i-1 rest)`. The snapshot `(prog1 %mv-spill (setq %mv-spill nil))` CLEARS the channel, so an enclosing consumer cannot re-read values an inner one took (else `(defun outer () (multiple-value-bind (a b) (inner) (+ a b)))` leaks `inner`'s second value).
- Atom producers skip the spill. `multiple-value-list` on a spill producer is `(cons primary rest)`. `multiple-value-call` with any spill producer spreads at runtime via `(apply fn (append seg...))`, so MULTIPLE_VALUE_CALL forces the eval runtime (`usesEval` gates in both compilers), like `apply`.
- Backends: interpreter predefines `%mv-spill` in `Environment.createGlobal` and its `values` writes it; compilers call `LispMacroExpander.injectMvSpillGlobal` (prepended top-level `(setq %mv-spill nil)`, gated on a scan for the five mv operator names) AFTER lambda-list desugaring. Scalar `--no-gc` has no reference globals and keeps `expandValuesPrimary`.
- `values-list` is the spread operator: `(values-list l)` publishes `(cdr l)` and returns `(car l)` (`expandValuesList` on compile paths, a spill-writing Environment function on the interpreter; CL_FUNCTIONS + unary wrapper). `parse-integer` returns its stop position as a literal second value, so PARSE_INTEGER and VALUES_LIST are in the `injectMvSpillGlobal` scan.

## An unwind-protect cleanup may not clobber the channel
**Invariant: a cleanup's values are DISCARDED, and the protected form's value COUNT is restored.** One global channel means a cleanup reaching `values` overwrites what the protected form published (`(unwind-protect (values 1 2 3) (release))` answered `(1)` for a `(values)` cleanup and `(1 8)` for a `(values 7 8)` one).

Fix, same shape on all four backends: SAVE the channel before the cleanup sequence, write it back after, on every exit path.
- Interpreter `LispEvaluator.runUnwindCleanups` (both the normal and error/`return` path of `evalUnwindProtect`) re-`define`s `%mv-spill` after the cleanups.
- JVM `JvmUnwindProtectCompiler.compileCleanups`: `getstatic`/`astore` + `aload`/`putstatic` over the `%mv-spill` field.
- WASM GC `WasmUnwindProtectCompiler.compileCleanups`: `global.get`/`local.set` + `local.get`/`global.set` — a LOCAL, not the operand stack, because landing pads run with the caught `exnref` / escaping return value beneath them.
- The save lives in the SHARED cleanup emitter, not the `unwind-protect` layout, so every exit path inherits it including the copies a `return`/`return-from`/`go` inlines at an escape site, where the values of a `(return-from f (values 1 2 3))` are already in flight when the cleanup runs; `WasmTagbodyCompiler.compileGo` calls the shared emitter for that reason.
- Two exclusions keep unaffected programs byte-identical: no mv operator means no `%mv-spill` global (`injectMvSpillGlobal` gate), and an `UnwindScope` whose cleanup is the compiler's own `(%hc-depth-dec)` handler-depth bookkeeping is skipped via `internalOnly`.
- Not affected (checked): `handler-case`, `catch`/`throw`, `ignore-errors`, `restart-case`, the `with-*` macros (they expand to `unwind-protect`). Open gap: `handler-case`'s `:no-error` clause is not a multiple-value consumer at all — `(handler-case (values 1 2 3) (:no-error (a b c) ...))` leaves `b`/`c` unbound.

## A syntactic producer's tail escapes through the spill
**Invariant: the tier boundary is not observable through a function return.** A recognized syntactic producer (floor family, `gethash`, `find-symbol`, `intern`, `array-displacement` — all `isMvProducerForm` accepts except a literal `values`, which already publishes) in a value-escaping position of a function body publishes its secondary value to `%mv-spill` as it returns its primary (`LispMacroExpander.spillEscapingMvProducers`). So `(defun f (h) (gethash "K" h))` answers two values however many calls away, including through a `defmethod` — the shape that found this (cl-mustache's `context-get` IS a gethash: present-p died at the method boundary, every `{{name}}` rendered empty, and nothing errored).

Wiring is SELECTIVE — an unconditional spill would tax the hottest built-ins on every call:
- TAIL positions only, through `progn`/`locally`, the `let` family, `flet`/`labels`/`macrolet` BODIES, `if`/`when`/`unless`, the last form of `and`/`or`, `cond` + `case`/`typecase`-family clause bodies (a bodyless `(test)` clause keeps CL's primary-value-only semantics), `block`/`%block`/`%fn-block`, `multiple-value-bind` bodies, an `unwind-protect` protected form, `return`/`return-from`, `the`.
- Compile paths: `injectMvSpillGlobal` applies it to every top-level `defun` body (CLOS bodies are defuns by then — it runs after `expandTopLevelDefinitions`), gated on `usesMv`, so a program with no mv operator stays byte-identical. Interpreter: `evalDefun` does the same to the block-wrapped body, ungated.
- A producer LEXICALLY inside a consumer is intercepted by the consumer's own expansion first, so the temp-only fast path emits no spill round-trip.
- `--no-gc` never calls `injectMvSpillGlobal`.
- Deliberate gaps: a producer tail in a bare `lambda` or a `flet`/`labels` LOCAL function body is not rewritten on any path (both callers would need the shape added together); a non-tail `return-from`/`go` escape is not scanned; `handler-case` and `multiple-value-prog1` are not tail contexts.

## The REPL echo is a consumer
`LispEvaluator.evalValues(form) -> List<LispVal>` is the ONLY multiple-value entry point outside the macro expander. `(floor 10 3)` echoes `3` then `1`, one value per line; `(values)` echoes nothing.
- A SYNTACTIC producer (`LispMacroExpander.isSyntacticMultipleValueProducer`, public face of `isMvProducerForm`: literal `values`, floor-family, `gethash`, `array-displacement`) is echoed wrapped in `(multiple-value-list ...)`.
- Anything else is evaluated UNWRAPPED (`evalResolved`) with the spill cleared first and read back after, so tail `(values ...)`, `values-list` and `parse-integer` echo everything while a top-level `defun`/`in-package` still evaluates at top level. Wrapping every form would bury definition forms inside a `let`.
- Resolution runs ONCE (`resolvePackages` + `evalResolved`, not `eval`): package resolution is not idempotent under a `:shadow` package.
- `ReplBuffer.eval` (JLine and piped REPL) echoes EVERY form right after it runs, so output precedes value and two forms on one line echo twice, as SBCL does; an empty buffer echoes nothing. `RontoPlayground.evalLine` (`src/web/java`) echoes the LAST form only — it also backs the doc site's "Run" cells (`doc/assets/docs.js`), whose blocks are setup-plus-expression with a final `; =>` value.
- Diffed against SBCL 2.2.9. Known remaining differences: a non-tail `values` nobody consumes leaks; `read-from-string`/`subtypep`/... are single-valued (`macroexpand-1`/`macroexpand` no longer are, `.kb/gensym-macroexpand.md`); `print` omits CL's leading newline / trailing space; prompt-per-form.

## Documented deviations
- A producer calling `values` in a NON-tail position with no consumer of its own, then returning normally, leaves a stale spill (extra vars read leftovers instead of nil — a consumer clears what it took, so only unconsumed `values` calls leak). `funcall #'values` through the compiled first-class wrapper yields the primary only; the interpreter's function does spill.
- Producers are recognized before user-macro expansion on the interpreter but after it on the compile path (`UserMacroExpander` runs first), so a USER MACRO expanding to `(values ...)` yields all values only when compiled.
- `multiple-value-call` with a builtin `#'name` inherits the wrapper arity. `+`/`-`/`*`/`/`/`list`/`min`/`max` have variadic `&rest` wrappers (a `reduce` fold) and take any count; every other multi-arg builtin keeps a fixed unary/binary wrapper (a mismatched funcall yields nil on JVM / traps on WASM).

## Wiring points
`LispNames` (VALUES + 4); `PackageRegistry` (CL_MACROS + CL_FUNCTIONS); `LispEvaluator.evalCons` cases (+ floor-family arity branch); `Environment` `values` function; `Jvm/WasmExprCompiler` cases (+ floor-family branch around the IntConv compilers); `NoGcWasmCompiler.expandMacro` (mv forms fail on `list`/`lambda`; the floor-divisor case works); `FreeVarAnalyzer` both walks (expand-before-walking, flet precedent — the raw var list would misread as a call form); `UserMacroExpander.expandAll` + `LispMacroExpander.rewriteLocalCalls` cases keeping the mv-bind variable list verbatim; `BuiltinFunctionWrappers` (`values`). REPL echo: `LispEvaluator.evalValues` + `isSyntacticMultipleValueProducer`, consumed by `ReplBuffer.eval` and `RontoPlayground.evalLine`.

## Pinning tests
- `LispEvaluatorTest`: `evalValuesInSingleValueContext`, `evalMultipleValueBind`, `evalMultipleValueBindFloorFamily`, `evalFloorFamilyWithDivisorInSingleValueContext`, `evalMultipleValueBindGethash`, `evalMultipleValueList`, `evalNthValue`, `evalMultipleValueCall`, `evalMultipleValueUserFunctionTailValuesCrossTheCallBoundary`, `evalMultipleValueBindErrors`, `evalUnwindProtectCleanupKeepsTheProtectedFormsValues`, `evalSyntacticMvProducerTailPublishesThroughAFunctionReturn`; echo tier: `evalValuesAtTopLevelYieldsEveryValue`, `evalValuesAtTopLevelIgnoresValuesConsumedInsideTheForm`, `evalMultipleValueConsumerClearsTheSpillChannel`.
- `RontoLispCliTest.replEchoesEveryValueOnItsOwnLine`.
- `JvmLispCompilerTest`: `compileAndRunValuesInSingleValueContext`, `compileAndRunMultipleValueBind`, `compileAndRunMultipleValueBindGethash`, `compileAndRunMultipleValueListAndNthValue`, `compileAndRunMultipleValueCall`, `compileAndRunUnwindProtectKeepsTheProtectedFormsValues`, `compileAndRunSyntacticMvProducerTailPublishesThroughAFunctionReturn`.
- `WasmLispCompilerIntegrationTest`: `multipleValueForms`, `multipleValueGethash`, `unwindProtectKeepsTheProtectedFormsValues`, `syntacticMvProducerTailPublishesThroughAFunctionReturn`.
- ci-spec: `multiple-values-core`, `unwind-protect-values` (adds the `--component` leg), `mv-producer-function-return` (all four backends, one expectation — its `find-symbol`/`intern` rows probe a USER symbol because a NON-literal name's runtime status diverges between interpreter and compile paths, and a user symbol answers `:internal` everywhere), plus the spill/user-function case inside `split-sequence-residue-features`; `rontolisp-package-introspection` carries the 4 macro names and 208.
- The unwind-protect pins run a cleanup-shape x exit-shape matrix (cleanup: `nil`, a call returning zero/one/two values, a literal `(values 7 8)`, a nested `unwind-protect`; exit: fall-through, `return-from`, `go`, a signalled unwind).
- Test-writing caveats: compiled `print` returns nil (the interpreter returns the value); JVM argument evaluation order inside one call differs — side-effect assertions go through `setq` in separate top-level forms.
