# Multiple values -- syntactic tier

The `multiple-value-bind`-over-`floor`/`gethash` idioms real CL code uses, WITHOUT a runtime
multiple-value representation. A `%mv-spill` global carries the cases the syntactic tier cannot.

**Invariant (2026-09-19): the channel is EXACT -- after any form a consumer can read it behind,
it holds that form's own extra values and nothing older.** nil is one value, a fresh list the
values after the primary, `t` (`LispMacroExpander.MV_ZERO_VALUES`) no value at all. The
interpreter keeps it as a value-count register (every primitive step clears), the compile paths
by settling every function tail ("A tail settles the channel", below); the four backends agree
line for line with SBCL on the `multiple-values-single-value-contexts` ci-spec case.

## What ships
- `values` is a CL **function** (`CL_FUNCTIONS`, `Environment`, a variadic `&rest`
  `BuiltinFunctionWrappers` entry, `expandValues` in call position). NOT in `expandBuiltinMacro`,
  so `macroexpand-1` leaves it alone as in CL.
- `multiple-value-bind`, `multiple-value-list`, `multiple-value-call`, `nth-value` (CL_MACROS +
  `expandBuiltinMacro`; `multiple-value-call` as a macro deviates from CL's special operator).
- Secondary values for `floor`/`ceiling`/`round`/`truncate`, `gethash` and `subtypep`, ONLY inside
  consumers.
  Two-argument `(floor a b)` elsewhere: `expandFloorFamilyDivisor` -> `(floor (/ a b))`.

## The lowering (`LispMacroExpander`)
`lowerMvProducer` -> `MvProducer{bindings, values}`: ordered `__mv<id>` temps (`MV_COUNTER`) as
**nested single-binding lets** (`nestMvBindings`) so evaluation order holds on every backend.
`isMvProducerForm` recognizes literal `values`, the floor family, `gethash` (a runtime `(gensym)`
sentinel default plus `(eq v sentinel)` distinguishes a stored nil from a missing key), `subtypep`
(answer + valid-p, [[declarations-type-checks]]), one-argument `read-from-string` (datum + stop
index, [[read-load-streams]]), else one temp. A producer the form is NOT recognized as goes through
`spillEscapingMvProducers` FIRST, so a recognized producer in the TAIL of the `(let ...)`/`(progn
...)` the consumer was handed publishes -- the tier boundary is otherwise visible through a wrapper
nobody wrote for that purpose. Consumers: `expandMultipleValueBind` (missing -> nil, surplus evaluated and dropped),
`expandMultipleValueList`, `expandNthValue`, `expandMultipleValueCall` (fn temp FIRST, then
producers' temps, into one direct `funcall` -- static count, no runtime spreading).

## The `%mv-spill` runtime channel
`values` PUBLISHES its extras to the `%mv-spill` global as it returns its primary, for consumers
behind a call: nil for one value, a fresh list of the extras, `t` for `(values)` -- zero values
are represented (`MV_ZERO_VALUES`), and `values-list` of nil says the same.
- A consumer whose producer may pass values along (a call of a user function, a form whose tail
  is one) clears the spill, evaluates the SETTLED form, then snapshots it (`MvProducer.rest`).
  The snapshot `(prog1 %mv-spill (setq %mv-spill nil))` CLEARS the channel, so an enclosing
  consumer cannot re-read what an inner one took. A producer the tail walk proves single-valued
  (an atom, a `cl` function call, a `let`/`if`/`progn` whose every tail is one) takes the
  temp-only path: no round-trip, extra variables read nil.
- The zero-values marker is a non-list, so every read normalizes: `multiple-value-list` and a
  `multiple-value-call` segment answer `(if (eq rest t) nil (cons primary rest))`
  (`allValues`), `multiple-value-bind`/`nth-value` index `(if (eq rest t) nil rest)`
  (`restAsList`/`spillAsList`, the `handler-case :no-error` compilers included), the
  interpreter's `consumeValues` answers an empty list. `multiple-value-bind` cannot tell zero
  from one (nil either way), which is CL.
- `multiple-value-call` with any spill producer spreads at runtime via `(apply fn (append
  seg...))`, so MULTIPLE_VALUE_CALL forces the eval runtime (`usesEval`).
- Interpreter keeps the channel as a FIELD of the global `Environment` (`mvSpill`, not a
  binding: it is written on every primitive step, which a map put could not afford; the
  variable `%mv-spill` resolves to it through `lookup`/`set`); compilers call
  `LispMacroExpander.injectMvSpillGlobal` AFTER lambda-list desugaring, gated on a name scan.
  Scalar `--no-gc` keeps `expandValuesPrimary` (no reference globals).
- `values-list` is the spread operator; `parse-integer`'s stop position is a literal second
  value, so PARSE_INTEGER and VALUES_LIST are in the `injectMvSpillGlobal` scan. The `#'values`
  wrapper is `(values-list r)`, so `(funcall #'values 1 2)` and `(apply #'values '())` answer to
  a consumer as `(values 1 2)` and `(values)` do on every backend.

## An unwind-protect cleanup may not clobber the channel
**Invariant: a cleanup's values are DISCARDED and the protected form's value COUNT is restored.**
Save the channel before the cleanup sequence, write it back after, on every exit path:
`LispEvaluator.runUnwindCleanups`, `JvmUnwindProtectCompiler.compileCleanups`,
`WasmUnwindProtectCompiler.compileCleanups` (via a LOCAL, not the operand stack -- landing pads
run with the caught `exnref` / escaping value beneath them).
- The save lives in the SHARED cleanup emitter, so every exit path inherits it, including the
  copies a `return-from`/`go` inlines at an escape site (`WasmTagbodyCompiler.compileGo`).
- Two exclusions keep unaffected programs byte-identical: the `injectMvSpillGlobal` gate, and an
  `UnwindScope` whose cleanup is the compiler's own `(%hc-depth-dec)` bookkeeping (`internalOnly`).

## `handler-case`'s `:no-error` clause is a multiple-value consumer
`(handler-case expr (:no-error ([var...]) body...))` binds the variable list to the protected
form's full VALUES list -- primary first, then the spill extras, with missing values nil-padded
and surplus values dropped, the same shape `multiple-value-bind` uses. Both backends apply
`LispMacroExpander.spillEscapingMvProducers` to the protected form when a `:no-error` clause
exists (so a syntactic producer -- gethash, floor-family, find-symbol, intern,
array-displacement, subtypep -- publishes its secondary through the spill), snapshot the spill into a
local on the success path, clear the channel, and bind each variable through `(nth i spill)`,
well-defined on nil.
- The variable list is REQUIRED-ONLY here, not the full CL lambda list: `&optional`/`&rest`/
  `&key` are not accepted (signalled on parse). SBCL lowers `:no-error` to a function call
  with `multiple-value-call`, which actually requires exact arity for the required shape and
  errors otherwise; rontolisp nil-pads missing required values instead -- a deliberate
  divergence, the same one `multiple-value-bind` makes.
- The spill global exists only when the program uses a multiple-value operator OR a
  `handler-case` with a `:no-error` clause (`usesMvOperator` includes `HANDLER_CASE`), so a
  `handler-case` without `:no-error` stays byte-identical and a `:no-error` in a program
  with no other multiple-value operator still publishes through the spill. When the global
  does not exist the consumer reads nil.
- Interpreter: `LispEvaluator.evalHandlerCase`'s `noErrorClause` tail snapshots the spill
  and binds each variable in order.
- JVM: `JvmHandlerCaseCompiler.compileNoErrorClauseBody`, gated on the spill field's
  existence.
- WASM: `WasmHandlerCaseCompiler.compileNoErrorClauseBody`, gated on the spill global's
  existence.

## A syntactic producer's tail escapes through the spill
**Invariant: the tier boundary is not observable through a function return.** A recognized
producer (floor family, `gethash`, `find-symbol`, `intern`, `array-displacement`, `subtypep`) in a
value-escaping position publishes its secondary to `%mv-spill`
(`LispMacroExpander.spillEscapingMvProducers`), so `(defun f (h) (gethash "K" h))` answers two
values however many calls away, including through a `defmethod`. Wiring is SELECTIVE -- an
unconditional spill would tax the hottest built-ins on every call:
- TAIL positions only, through `progn`/`locally`/`with-standard-io-syntax`, the `let` family,
  `flet`/`labels`/`macrolet` bodies, `if`/`when`/`unless`, the last form of `and`/`or`, `cond`/`case`/`typecase` clause
  bodies (a bodyless `(test)` clause keeps primary-value-only semantics), `block` family,
  `multiple-value-bind` bodies, an `unwind-protect` protected form, `return`/`return-from`, `the`.
- The publish is a `(values primary secondary)` CALL over the lowered temps, not a
  `(progn (setq %mv-spill ...) primary)`: on the interpreter the step after a publish clears the
  channel, so the publish must be the last step.
- `injectMvSpillGlobal` applies it to every top-level `defun` body, gated on `usesMv`;
  interpreter `evalDefun`, ungated; `--no-gc` never. A producer LEXICALLY inside a consumer is
  intercepted by the consumer's expansion first. `handler-case`'s protected form and clause
  bodies are tail contexts too (`settleHandlerCase`).
- Deliberate gap: a producer tail in a bare `lambda` or a `flet`/`labels` LOCAL function body is
  not rewritten on any path -- the interpreter rewrites no lambda body, and the compile paths'
  lambda walk (`TailMode.CLEAR`, below) treats the producer as the one-value `cl` call it is
  there, so `(funcall (lambda () (gethash k h)))` answers one value everywhere. A non-tail
  `return-from`/`go` escape is not scanned; `multiple-value-prog1` needs no walk (its expansion
  ends in `values-list`).

## A tail settles the channel (compile paths)
**Invariant: once a function body's tail has run, the channel holds that body's extra values.
The channel is read only by a consumer that cleared it first and then ran a settled form, so a
publish in a non-tail position -- an argument, a `let` initform, a form before the last, a
`setq` value, an `if` test, a loop body -- never reaches a consumer.**
`LispMacroExpander.settleTail` walks a tail form and classifies it (`SettledTail.single`):
- **Passes values along, untouched**: a `values` tail; a call of a user, local or generic
  function or an internal helper (any operator that is not a `cl` function name); `funcall`,
  `apply`, `eval`, `multiple-value-call`, `multiple-value-prog1`; the `cl` functions that answer
  several values here -- `values-list`, `parse-integer`, `macroexpand(-1)` and the prelude's
  multiple-value defuns (`passesMultipleValues`, pinned against the prelude source by
  `LispPreludeLibraryTest`). `(funcall #'name ...)`/`(apply 'name ...)` over a LITERAL designator
  is classified by `name` -- the compile paths call the built-in inline there.
- **One value, cleared**: an atom, `quote`, `function`, a `lambda` expression,
  `load-time-value` get `(progn (setq %mv-spill nil) form)` (`clearBefore`: nothing in them can
  publish); a call of any other `cl` function, the assignment macros (`setq`/`setf`/`incf`/
  `push`/..., except a `(setf (values ...) ...)` place), `multiple-value-list`, `nth-value`, the
  definers, `format`/`error`/`warn`/`check-type`/... get `(let ((__mvN_v form)) (setq %mv-spill
  nil) __mvN_v)` (`clearAfter`: an argument or a callback -- `sort`'s predicate, a `print-object`
  method -- may have published), or `clearBefore` when the operator is one of the pure
  primitives in `QUIET_OPERATORS` and every argument is quiet; `while`, `tagbody` and a
  result-less `dotimes` get `(progn form (setq %mv-spill nil) nil)` (`clearAfterStatement`:
  a `return`/`go`/`throw` out of them skips the clear and keeps its own values).
- **Tails of their own, settled each**: `progn`/`let`/`let*`/`flet`/`labels`/`block`/
  `catch`/`progv`/`multiple-value-bind`/`destructuring-bind`/`with-*` bodies, `if` (a missing else
  branch is a cleared nil), `unwind-protect`'s protected form, `return`/`return-from`/`throw`/
  `the` values (a value-less `return` is a cleared nil), `handler-case`'s protected form and
  clause bodies, `typecase` clause bodies (a plain `typecase` with no `otherwise` gets one
  answering a cleared nil), a `dotimes` result form. `cond`/`case`/`and`/`or`/`when`/`unless`/
  `dolist`/`do`/`loop`/`prog1`/`prog2`/`prog`/`ignore-errors`/`time` are EXPANDED first -- both
  backends compile them through the same expansion, and only the expansion shows a `cond`'s
  fall-through nil or a `prog1`'s temporary -- while `dotimes` keeps its form (the wasm counted
  loop and the JVM typed loop read it).
- Where it runs: every top-level `defun` body (`settleDefunTails`, from `injectMvSpillGlobal`,
  gated on `usesMv` -- a consumer-free program stays byte-identical); every `lambda` body as the
  backends compile it (`Jvm`/`WasmLambdaCompiler.compileValue` and `compileCall`, gated on the
  spill global's existence) and the built-in wrappers (`settleWrapperLambdas`, gated on
  `declaresMvSpill`) -- `flet`/`labels` functions are lambdas by then; a local function the
  int-fusion inlines instead (`tryCompileLocalCall`) gets its clear at the fused call site; every
  consumer's producer form (`lowerMvProducer`); a `handler-case` protected form with a
  `:no-error` clause. Three modes (`TailMode`): `PUBLISH` (the interpreter's `evalDefun`/
  `evalHandlerCase`, `spillEscapingMvProducers`: syntactic producers publish, nothing clears, and
  a macro form keeps its shape -- the macro-time purity walks read stored bodies),
  `PUBLISH_AND_CLEAR` (`settleMvTail`: defuns, consumers, handler-case), `CLEAR`
  (`settleFunctionBody`: lambdas and wrappers).
- Residue, by construction: a program that REDEFINES a `cl` function to answer several values
  is classified by the name (one value) in tail positions and consumers; a `macrolet`
  expansion is produced after the walk and is not settled; an operator the walk does not know
  passes values along, which is the leak-safe direction (a stale publish may travel, never a
  value lost).
- Cost (2026-09-19, x86-64 Linux, Java 25, wasmtime 47; `bench-report/programs/fib.lisp`
  20 x fib 30 = 32M calls, 4 alternating process pairs): a program without a multiple-value
  operator is byte-identical (13,220 B of class, 5,479 B of wasm). With one appended, class
  14,106 -> 14,128 B (+22), wasm 7,203 -> 7,331 B (+128); JVM 167-200 -> 178-209 ms (noise),
  wasm 703-715 -> 419-425 ms -- FASTER, the `let`-temporary tail lands on the unboxed local
  path the bare `(+ ...)` branch did not. The mv-heavy probe programs grow 1.2-1.5%
  (474,800 -> 480,681 B of class, 348,373 -> 353,587 B of wasm).

## The interpreter's value-count register
**Invariant: on the interpreter the channel reflects the LAST value-producing step, exactly --
a native implementation's value-count register, kept as `Environment.mvSpill`.** Every
primitive step that is not a publish and not a call of user code clears it
(`Environment.clearSpill`, `LispEvaluator.singleValue`):
- an atom or literal as `eval` answers it (a symbol read reads the channel first, which is how
  the consumers' snapshot `%mv-spill` works);
- a closed argument list (`evalArgs`: an argument is a single-value context);
- a built-in's return (`apply` on a `LispFunction`), unless the built-in `passesValues` --
  `values`, `values-list`, `parse-integer`, `macroexpand(-1)` publish themselves, `funcall`,
  `apply`, `eval`, `uiop:symbol-call` hand on the values of the code they ran. A callback the
  built-in ran (`sort`'s predicate, `mapcar`'s function, a `print-object` method) may have
  published; the built-in's answer is one value regardless, which is what the clear after it
  says. **A new `LispFunction` that publishes or passes values must say so**, or its extra
  values die at its own return.
- a constructing special form: `quote`, `function`, `lambda`, `setq` (an assignment answers ONE
  value however many the value form produced), the definers, `while`, `tagbody`, `slot-value`,
  `await`, a value-less `return`/`return-from`/`throw`, an empty `progn`/block/`catch`/clause
  body, an `if` without an else branch taking it, a result-less `do-symbols`.
A form that merely passes a sub-form's value on (`if`, `let`, `progn`, a block, `catch`,
`handler-case` without `:no-error`, a user function's body) leaves the channel to that
sub-form: a `(values ...)` tail reaches the consumer behind any number of returns, and a
`values` whose value went into a variable or an argument never does.
`unwind-protect` saves the channel around its cleanups and `publishSpill`s it back
(`runUnwindCleanups`), `handler-case` with `:no-error` `consumeValues` it.
- Cost (2026-09-19, fib 27 + a 2M-call loop, 5 alternating process pairs): 2,280-2,350 ->
  2,254-2,344 ms, medians 2,317 -> 2,297, no measurable change. The old argument-boundary
  flag (`beginArguments`/`endArguments`) is gone: it only cleared arguments, and left a
  `let` initform, a `progn` body form and a callback's publish to leak.

## The REPL echo is a consumer
`LispEvaluator.evalValues(form) -> List<LispVal>` is the ONLY multiple-value entry point outside
the macro expander.
- A SYNTACTIC producer (`isSyntacticMultipleValueProducer`) is echoed wrapped in
  `multiple-value-list`; anything else is evaluated UNWRAPPED (`evalResolved`) with the spill
  cleared first and read back after, so a top-level `defun`/`in-package` still evaluates at top
  level. Resolution runs ONCE: package resolution is not idempotent under a `:shadow` package.
- `ReplBuffer.eval` echoes EVERY form right after it runs (as SBCL does);
  `RontoPlayground.evalLine` (`src/web/java`, also the doc site's "Run" cells) echoes the LAST.
- Diffed against SBCL 2.2.9. Remaining difference: `print` omits CL's leading newline /
  trailing space. A helper answering `(values)` echoes nothing, a built-in whose callback
  published echoes its own one value. ([[gensym-macroexpand]] for
  `macroexpand-1`/`macroexpand`, [[declarations-type-checks]] for `subtypep`'s valid-p,
  [[read-load-streams]] for `read-from-string`'s stop index.)

## Documented deviations
- Producers are recognized before user-macro expansion on the interpreter but after it on the
  compile path, so a USER MACRO expanding to `(values ...)` yields all values only when compiled.
- A program's own `defun` of a `cl` function name that answers several values is classified as
  one value on the compile paths ("A tail settles the channel", residue).
- The Scheme front end's loops: a named `let` that exits through a CALL answers that call's
  first value only ([[scheme-frontend]], "Destination-driven lowering").
- `multiple-value-call` with a builtin `#'name` inherits the wrapper arity:
  `+`/`-`/`*`/`/`/`list`/`min`/`max` are variadic, every other multi-arg builtin is fixed
  unary/binary (a mismatched funcall yields nil on JVM, traps on WASM).

## Wiring points
`LispNames`; `PackageRegistry`; `LispEvaluator.evalCons`/`eval`/`evalArgs`/`apply`
(`singleValue`); `Environment` (`mvSpill`, `publishSpill`, `clearSpill`, `spill`);
`LispFunction.passesValues`; `Jvm`/`WasmExprCompiler` (+ the floor-family branch around the
IntConv compilers, the fused-local-call clear in the FUNCALL arm); `Jvm`/`WasmLambdaCompiler`
(the lambda hooks); `Jvm`/`WasmLispCompiler` (the wrapper settle); `NoGcWasmCompiler.expandMacro`;
`FreeVarAnalyzer` both walks (expand before walking, flet precedent);
`UserMacroExpander.expandAll` + `LispMacroExpander.rewriteLocalCalls` keeping the mv-bind variable
list verbatim; `BuiltinFunctionWrappers`; `SchemeLowering.exitGuardValue` (the session echo
holds the entry's values as a list across the exit catch) and `ValueCount` (a loop with a
`(values ...)` leaf carries a list and answers `values-list`).

## Tests
`LispEvaluatorTest` (`evalValues*`, `evalMultipleValue*`, `evalNthValue`,
`evalUnwindProtectCleanupKeepsTheProtectedFormsValues`,
`evalSyntacticMvProducerTailPublishesThroughAFunctionReturn`,
`evalMultipleValueConsumerClearsTheSpillChannel`, `evalValuesAtTopLevelIgnoresValuesPassedAsAnArgument`,
`evalMultipleValueChannelIsExactInSingleValueContexts`);
`LispPreludeLibraryTest.everyPreludeDefunOfAClFunctionThatAnswersSeveralValuesIsKnownToTheTailDiscipline`;
`RontoLispCliTest.theSchemeReplEchoesThroughTheSchemePrinter` (the argument case), `ClPpcreE2eTest`
(`(scan "abc" "xyz")` is `(NIL)` on every leg);
`RontoLispCliTest.replEchoesEveryValueOnItsOwnLine`; `JvmLispCompilerTest.compileAndRun*` and
`WasmLispCompilerIntegrationTest` twins (`*MultipleValueChannelIsExactInSingleValueContexts` is the
shared matrix); ci-spec `multiple-values-core`, `multiple-values-single-value-contexts` (the
matrix, SBCL's answers), `unwind-protect-values` (adds the `--component` leg),
`mv-producer-function-return` (its `find-symbol`/`intern` rows probe a USER symbol because a
non-literal name's runtime status diverges between interpreter and compile paths),
`split-sequence-residue-features`, `rontolisp-package-introspection`; scheme-spec
`multiple-values` (zero values, a loop's values, an argument's). The unwind-protect pins run a
cleanup-shape x exit-shape matrix. Caveats: compiled `print` returns nil; JVM argument evaluation
order inside one call differs -- side-effect assertions go through `setq` in separate top-level
forms.
