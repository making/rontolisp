# Multiple values -- syntactic tier (Phase 3 unit 3)

Origin: todo-057 (core subset of the
`.todo/032-multiple-value-system.md` wishlist; shipped 2026-07-05). Goal: the
`multiple-value-bind`-over-`floor`/`gethash` idioms that real CL library code
uses, WITHOUT paying for a runtime multiple-value representation.

## What ships

- `values` (classified as a CL **function**: `CL_FUNCTIONS`, an `Environment`
  function, a variadic `&rest` `BuiltinFunctionWrappers` entry -- the first
  variadic wrapper -- and an `expandValues` call-position expansion; NOT in
  `expandBuiltinMacro`, matching its "function-like operators are deliberately
  absent" rule, so `macroexpand-1` leaves `(values ...)` alone as in CL).
- `multiple-value-bind`, `multiple-value-list`, `multiple-value-call`,
  `nth-value` (CL_MACROS + `expandBuiltinMacro`; `multiple-value-call` as a
  macro deviates from CL's special operator, precedent: `error`).
- Secondary values for `floor`/`ceiling`/`round`/`truncate` (quotient +
  remainder) and `gethash` (value + present-p) -- ONLY inside the consumers.
- Two-argument `(floor a b)` etc. in ordinary contexts:
  `expandFloorFamilyDivisor` -> `(floor (/ a b))` (exact rational division,
  so integer quotients are exact; scalar `--no-gc` uses its float `/`, same
  rounding result). Wired as an arity-3-only branch in the evaluator (`break`
  falls through to the 1-arg built-in) and all three compilers.

## The lowering (LispMacroExpander)

`lowerMvProducer(form, prefix)` -> `MvProducer{bindings, values}`: ordered
temp bindings (`__mv<id>...`, `MV_COUNTER`-fresh like `FLET_COUNTER`) realized
as **nested single-binding lets** (`nestMvBindings`) so evaluation order is
guaranteed on every backend, plus pure value expressions over the temps.
Recognized producers (`isMvProducerForm`):

- literal `(values e...)` -> temps for each argument;
- `(floor|ceiling|round|truncate a [b])` -> `q = (op a)` / `(op (/ a b))`,
  remainder `(- a q)` / `(- a (* q b))`;
- `(gethash k h [d])` -> a runtime `(gensym)` sentinel is passed as the
  gethash default; `(eq v sentinel)` decides `(values stored-or-default
  present-p)`. Distinguishes a stored nil from a missing key on all backends
  (WASM `eq` on an i31/string vs the fresh symbol is safe -- pinned in
  `WasmLispCompilerIntegrationTest.multipleValueGethash`).
- anything else -> single temp bound to the form (single-value producer).

Consumers: `expandMultipleValueBind` (parallel inner `let` of the user vars
over the value expressions; missing -> nil, surplus values evaluated in the
bindings and dropped), `expandMultipleValueList` (`(list e...)` shortcut for a
literal `values`, `(list form)` for unknown producers), `expandNthValue`
(`(nth n <mv-list>)`; `nth` evaluates n first, CL order),
`expandMultipleValueCall` (fn temp bound FIRST, then each producer's temps ->
one direct `(funcall fnTmp v...)`; the value count is static, so no runtime
spreading and no `apply`).

`expandValues` (ordinary context): 0 args -> nil, 1 -> the form, n ->
`(prog1 ...)`.

## The %mv-spill runtime channel (added by todo-061, 2026-07-05)

split-sequence's list splitter consumes a user function's 4 values internally,
so the syntactic tier alone was not enough. `values` now PUBLISHES its extra
values to the `%mv-spill` global (a fresh list; nil when there are none) as it
returns its primary, and a consumer whose producer form is an unrecognized
CALL clears the spill, evaluates the producer, and snapshots the spill
immediately (`MvProducer.rest`): value i>0 reads `(nth i-1 rest)`. The
snapshot itself is `(prog1 %mv-spill (setq %mv-spill nil))` -- it CLEARS the
channel, so values one consumer took cannot be read a second time by an
enclosing consumer (added 2026-07-30 with the REPL echo below: without it a
function that internally consumes a callee's values looked multi-valued to its
own caller, e.g. `(defun outer () (multiple-value-bind (a b) (inner) (+ a b)))`
leaked `inner`'s second value). Atom
producers skip the spill (single-value for sure). `multiple-value-list` on a
spill producer is `(cons primary rest)`; `multiple-value-call` with any spill
producer spreads at runtime via `(apply fn (append seg...))` -- which is why
MULTIPLE_VALUE_CALL now forces the eval runtime (`usesEval` gates in both
compilers), like `apply` itself. Backends: the interpreter predefines
`%mv-spill` in `Environment.createGlobal` and its `values` function writes it;
the compilers call `LispMacroExpander.injectMvSpillGlobal` (a prepended
top-level `(setq %mv-spill nil)`, gated on a scan for the five mv operator
names) after lambda-list desugaring. The scalar `--no-gc` backend has no
reference globals and keeps the old pure expansion (`expandValuesPrimary`).
`values-list` (added for parse-number, 2026-07-05) is the spill's spread
operator: `(values-list l)` publishes `(cdr l)` and returns `(car l)`
(`expandValuesList` on the compile paths, a spill-writing Environment function
on the interpreter; classified CL_FUNCTIONS with a unary wrapper). The
`parse-integer` expansion returns its stop position as a literal second value,
so PARSE_INTEGER and VALUES_LIST are part of the `injectMvSpillGlobal` scan.

## An unwind-protect cleanup may not clobber the channel (todo-397, 2026-08-16)

**Invariant: a cleanup's values are DISCARDED, and the protected form's value
COUNT is part of what is restored.** `unwind-protect` answers the protected
form's values, all of them, whatever the cleanup forms return. The channel is a
single global, so a cleanup that reaches `values` overwrites what the protected
form published: before this, `(unwind-protect (values 1 2 3) (release))` answered
`(1)` when `release` ended in `(values)` — the idiomatic "I return nothing" — and
`(1 8)` when it ended in `(values 7 8)`. `(1 8)` is the tell: the primary value is
the form's ordinary result and survives; every SECONDARY value was read back out
of whatever the cleanup left behind. Found by the dexador spike (`.todo/396`),
whose `request` returns `(values body status headers uri stream)` out of an
`unwind-protect` whose cleanup pushes the connection back and ends in `(values)`,
so every caller saw the body and nothing else.

The fix is the same shape on all four backends: SAVE the channel before the
cleanup sequence and write it back after, on every exit path.

- Interpreter: `LispEvaluator.runUnwindCleanups` (used by both the normal and the
  error/`return` path of `evalUnwindProtect`) reads `%mv-spill` out of the global
  env and re-`define`s it after the cleanups.
- JVM: `JvmUnwindProtectCompiler.compileCleanups` brackets the sequence with
  `getstatic`/`astore` + `aload`/`putstatic` over the `%mv-spill` field.
- WASM GC: `WasmUnwindProtectCompiler.compileCleanups` does the same with
  `global.get`/`local.set` + `local.get`/`global.set`. A LOCAL, not the operand
  stack: the landing pads run with the caught `exnref` / the escaping return value
  beneath them.

Both compilers put the save in the SHARED cleanup emitter rather than in the
`unwind-protect` layout, so every exit path inherits it — including the copies a
`return`/`return-from`/`go` inlines at an escape site, where the values of a
`(return-from f (values 1 2 3))` are already in flight when the cleanup runs.
`WasmTagbodyCompiler.compileGo` was inlining cleanups with its own loop and now
calls the shared emitter for exactly that reason.

Two deliberate exclusions keep emitted output identical for programs that cannot
be affected: a program with no multiple-value operator has no `%mv-spill` global
(the `injectMvSpillGlobal` gate) and gets nothing, and an `UnwindScope` whose
cleanup is the compiler's own `(%hc-depth-dec)` bookkeeping — what
`handler-case` pushes so an escape adjusts the handler-depth counter — is
recognized by `internalOnly` and skipped, since an i32 counter cannot reach the
channel.

The sibling forms that also run code between a producer and its consumer were
checked and are NOT affected: `handler-case` (body values reach the caller),
`catch`/`throw` (a thrown `(values 1 2 3)` arrives whole), `ignore-errors`,
`restart-case` and the `with-*` macros — the last group expands to
`unwind-protect`, so it inherits the fix. One genuinely separate gap surfaced:
`handler-case`'s `:no-error` clause is not a multiple-value consumer at all
(`(handler-case (values 1 2 3) (:no-error (a b c) ...))` leaves `b`/`c` unbound),
filed as `.todo/406`.

Pinned by the cleanup-shape x exit-shape matrix (cleanup: `nil`, a call returning
zero/one/two values, a literal `(values 7 8)`, a nested `unwind-protect`; exit:
fall-through, `return-from`, `go`, a signalled unwind) in
`LispEvaluatorTest.evalUnwindProtectCleanupKeepsTheProtectedFormsValues`,
`JvmLispCompilerTest.compileAndRunUnwindProtectKeepsTheProtectedFormsValues`,
`WasmLispCompilerIntegrationTest.unwindProtectKeepsTheProtectedFormsValues` and
the `unwind-protect-values` ci-spec case (which adds the `--component` leg).

## A syntactic producer's tail escapes through the spill (todo-427 + todo-212, 2026-08-17)

**Invariant: the tier boundary is not observable through a function return.** A
recognized syntactic producer (floor family, `gethash`, `find-symbol`, `intern`,
`array-displacement` -- everything `isMvProducerForm` accepts except a literal
`values`, which already publishes) sitting in a value-escaping position of a
function body is rewritten to publish its secondary value to `%mv-spill` as it
returns its primary (`LispMacroExpander.spillEscapingMvProducers`), so
`(defun f (h) (gethash "K" h))` answers two values to any consumer, however many
calls away -- including a `defmethod`, the shape that found this (cl-mustache's
`context-get` IS a gethash: present-p died at the method boundary, every
`{{name}}` rendered empty, and nothing errored).

The wiring is SELECTIVE, not unconditional -- the reason todo-212 stalled is
that an unconditional spill would tax the hottest built-ins in the language on
every call:

- The rewrite walks TAIL positions only, through `progn`/`locally`, the `let`
  family, `flet`/`labels`/`macrolet` BODIES, `if`/`when`/`unless`, the last form
  of `and`/`or`, `cond` + `case`/`typecase`-family clause bodies (a bodyless
  `(test)` clause keeps CL's primary-value-only semantics and is left alone),
  `block`/`%block`/`%fn-block`, `multiple-value-bind` bodies, an
  `unwind-protect` protected form (its cleanups save/restore the channel,
  todo-397), `return`/`return-from` and `the`.
- Compile paths: `injectMvSpillGlobal` applies it to every top-level `defun`
  body (`defmethod`/CLOS bodies are defuns by then -- it runs after
  `expandTopLevelDefinitions`), gated on `usesMv`: a program with no
  multiple-value operator has no observer, and its emitted output stays
  byte-identical (verified against the size-report programs, none of which use
  a multiple-value operator).
- Interpreter: `evalDefun` applies the same rewrite to the block-wrapped body
  (`defmethod` arrives there as a generated defun); the spill global always
  exists on the interpreter, so no gate.
- A producer LEXICALLY inside a consumer is intercepted by the consumer's own
  expansion before this rewrite can see it (consumers take the producer form
  verbatim), so the temp-only fast path keeps emitting no spill round-trip and
  those forms stay byte-identical -- the syntactic tier survives as the
  optimization it was meant to be, not as an observable semantics boundary.
- `--no-gc` keeps the pure single-value expansion as before (no reference
  globals, no spill channel; it never calls `injectMvSpillGlobal`).

Remaining gaps, deliberate (revisit if a library trips one): a producer tail in
a bare `lambda` or in a `flet`/`labels` LOCAL function body is not rewritten on
any path (both callers would need the shape added together), a non-tail
`return-from`/`go` escape is not scanned for, and `handler-case` /
`multiple-value-prog1` are not tail contexts.

Pinned:
`LispEvaluatorTest.evalSyntacticMvProducerTailPublishesThroughAFunctionReturn`
(plus the tail-function echo cases in `evalValuesAtTopLevelYieldsEveryValue`),
`JvmLispCompilerTest.compileAndRunSyntacticMvProducerTailPublishesThroughAFunctionReturn`,
`WasmLispCompilerIntegrationTest.syntacticMvProducerTailPublishesThroughAFunctionReturn`,
and the `mv-producer-function-return` ci-spec case (all four backends, one
expectation -- which is why its `find-symbol`/`intern` rows probe a USER symbol:
the runtime status of a NON-literal name diverges between the interpreter and
the compile paths, `.todo/254`, and a user symbol answers `:internal`
everywhere).

## The REPL echo is a consumer (added 2026-07-30)

The top level of a CL REPL is a multiple-value consumer -- `(floor 10 3)` echoes
`3` then `1`, one value per line, and `(values)` echoes nothing. Ours is
`LispEvaluator.evalValues(form) -> List<LispVal>`, which routes by producer kind
and is the ONLY multiple-value entry point outside the macro expander:

- a SYNTACTIC producer (`LispMacroExpander.isSyntacticMultipleValueProducer`, the
  public face of `isMvProducerForm`: literal `values`, floor-family, `gethash`,
  `array-displacement`) is echoed by wrapping it in `(multiple-value-list ...)`,
  because those extra values only exist inside a consumer's expansion;
- anything else is evaluated UNWRAPPED (`evalResolved`) with the spill cleared
  first and read back after -- so a user function's tail `(values ...)`,
  `values-list` and `parse-integer` echo all their values, while a top-level
  `defun`/`in-package`/... still evaluates at top level. Wrapping every form in
  `multiple-value-list` would have buried definition forms inside a `let`.

Resolution runs ONCE (`resolvePackages` + `evalResolved`, not `eval`): package
resolution is not idempotent under a `:shadow` package.

Callers: `ReplBuffer.eval` (both the JLine and the piped REPL go through
it) echoes EVERY form of the buffer, right after it runs -- so a form's own output
precedes its own value and two forms on one line echo twice, which is what SBCL
does reading them one at a time. An empty buffer now echoes nothing instead of
`NIL`. `RontoPlayground.evalLine` (`src/web/java`) deliberately echoes the LAST
form only: the same entry point backs the documentation site's "Run" cells
(`doc/assets/docs.js`), whose blocks are setup-plus-expression and whose annotated
`; =>` value is the final one.

Verified against SBCL 2.2.9 (installed on the dev host) by diffing transcripts --
identical for `floor`/`values`/`values-list`/`parse-integer`/`gethash`, a user
function's tail values, `values` through `cond`/`let`/`if`, `(values)` (no echo),
an empty line, and two forms on one line. The remaining differences are all
pre-existing and each has a todo (`.todo/212` -- floor-family/gethash secondary
values did not cross a function boundary -- was closed by todo-427, see the
tail-escape section above): `.todo/213` (a non-tail `values` nobody
consumes leaks), `.todo/214` (`read-from-string`/`subtypep`/... are
single-valued -- `macroexpand-1`/`macroexpand` came off that list with todo-378,
`.kb/gensym-macroexpand.md`), `.todo/215` (`print` omits CL's leading newline / trailing space,
and the prompt-per-form difference). Re-run the diff harness in `.todo/214` after
touching this area.

## Semantics consequences (documented deviations)

- A `(values ...)` tail in a USER function now DOES reach the caller's
  consumer through the spill. Deviations: a producer that calls `values` in a
  NON-tail position with NO consumer of its own and then returns normally leaves
  a stale spill (extra vars may read leftovers instead of nil -- a consumer
  clears what it took, so only unconsumed `values` calls leak), and
  `funcall #'values` through the
  compiled first-class wrapper yields the primary only (the interpreter's
  function does spill).
- Producers are recognized before user-macro expansion on the interpreter but
  after it on the compile path (UserMacroExpander runs first), so a USER MACRO
  expanding to `(values ...)` yields all values only when compiled. Literal
  producers behave identically everywhere.
- `multiple-value-call` with a builtin `#'name` inherits the wrapper arity.
  The naturally-variadic ops (`+`/`-`/`*`/`/`/`list`/`min`/`max`) now have
  variadic `&rest` wrappers (a `reduce` fold over the rest list, todo-064
  fixed), so those take any argument count; every other multi-arg builtin
  keeps a fixed unary/binary wrapper (mismatched funcall still yields nil on
  JVM / traps on WASM). Docs steer to user fns/lambdas for those.

## Wiring points (the usual checklist)

`LispNames` (VALUES + 4); `PackageRegistry` (CL_MACROS + CL_FUNCTIONS); `LispEvaluator.evalCons` cases (+ the
floor-family arity branch); `Environment` `values` function;
`Jvm/WasmExprCompiler` cases (+ floor-family branch around the IntConv
compilers); `NoGcWasmCompiler.expandMacro` (mv forms then fail on
`list`/`lambda` as before; the floor-divisor case works); `FreeVarAnalyzer`
both walks (expand-before-walking, flet precedent -- the raw var list would
misread as a call form); `UserMacroExpander.expandAll` +
`LispMacroExpander.rewriteLocalCalls` cases keeping the mv-bind variable list
verbatim; `BuiltinFunctionWrappers` (`values`). REPL echo:
`LispEvaluator.evalValues` + `LispMacroExpander.isSyntacticMultipleValueProducer`,
consumed by `ReplBuffer.eval` and `RontoPlayground.evalLine`.

## Pinning tests

- `LispEvaluatorTest`: `evalValuesInSingleValueContext`,
  `evalMultipleValueBind`, `evalMultipleValueBindFloorFamily`,
  `evalFloorFamilyWithDivisorInSingleValueContext`,
  `evalMultipleValueBindGethash`, `evalMultipleValueList`, `evalNthValue`,
  `evalMultipleValueCall`,
  `evalMultipleValueUserFunctionTailValuesCrossTheCallBoundary`,
  `evalMultipleValueBindErrors`; the REPL-echo tier:
  `evalValuesAtTopLevelYieldsEveryValue`,
  `evalValuesAtTopLevelIgnoresValuesConsumedInsideTheForm`,
  `evalMultipleValueConsumerClearsTheSpillChannel`.
- `RontoLispCliTest`: `replEchoesEveryValueOnItsOwnLine` (the echo through the
  piped REPL, including `(values)` echoing nothing).
- `JvmLispCompilerTest`: `compileAndRunValuesInSingleValueContext`,
  `compileAndRunMultipleValueBind`, `compileAndRunMultipleValueBindGethash`,
  `compileAndRunMultipleValueListAndNthValue`,
  `compileAndRunMultipleValueCall`.
- `WasmLispCompilerIntegrationTest`: `multipleValueForms`,
  `multipleValueGethash`.
- ci-spec: `multiple-values-core` + the spill/user-function case inside
  `split-sequence-residue-features`; the `rontolisp-package-introspection`
  expectations gained the 4 macro names and 208 (all three backend test
  classes updated too).
- Test-writing caveats hit while pinning: compiled `print` returns nil
  (interpreter returns the value -- filed as todo-063), and JVM argument
  evaluation order inside one call differs (`.todo/014`) -- side-effect
  assertions go through `setq` in separate top-level forms.
