# Multiple values -- syntactic tier

The `multiple-value-bind`-over-`floor`/`gethash` idioms real CL code uses, WITHOUT a runtime
multiple-value representation. A `%mv-spill` global carries the cases the syntactic tier cannot.

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
behind a call.
- A consumer whose producer is an unrecognized CALL clears the spill, evaluates, then snapshots
  it (`MvProducer.rest`). The snapshot `(prog1 %mv-spill (setq %mv-spill nil))` CLEARS the
  channel, so an enclosing consumer cannot re-read what an inner one took.
- `multiple-value-call` with any spill producer spreads at runtime via `(apply fn (append
  seg...))`, so MULTIPLE_VALUE_CALL forces the eval runtime (`usesEval`).
- Interpreter predefines `%mv-spill` in `Environment.createGlobal`; compilers call
  `LispMacroExpander.injectMvSpillGlobal` AFTER lambda-list desugaring, gated on a name scan.
  Scalar `--no-gc` keeps `expandValuesPrimary` (no reference globals).
- `values-list` is the spread operator; `parse-integer`'s stop position is a literal second
  value, so PARSE_INTEGER and VALUES_LIST are in the `injectMvSpillGlobal` scan.

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
- `injectMvSpillGlobal` applies it to every top-level `defun` body, gated on `usesMv`;
  interpreter `evalDefun`, ungated; `--no-gc` never. A producer LEXICALLY inside a consumer is
  intercepted by the consumer's expansion first.
- Deliberate gaps: a producer tail in a bare `lambda` or a `flet`/`labels` LOCAL function body is
  not rewritten on any path; a non-tail `return-from`/`go` escape is not scanned; `handler-case`
  and `multiple-value-prog1` are not tail contexts.

## An argument is a single-value context (interpreter)
**Invariant: what an ARGUMENT published is discarded before the callee runs; what was published
before the argument list is not touched.** `LispEvaluator.evalArgs` brackets the arguments with
`Environment.beginArguments` / `endArguments`, so `(list (f))`, `(+ 1 (values 5 6))` and a
callee whose tail is `(list (values x 2))` answer one value, `(values (f) 7)` still answers
`5 7` (the callee publishes after the clear), and a tail's values survive a later all-quiet
call on their way out -- the Scheme session guard's `(if (eq code done) value ...)` and the
syntactic producers' `(progn (setq %mv-spill ...) (if (eq v s) d v))` depend on that.
- Mechanism: a flag on the global `Environment` (`spillPublished`), saved and zeroed at
  `beginArguments`, set by every Java publisher (`publishSpill`: `values`, `values-list`,
  `parse-integer`, the unwind-protect restore, `macroexpand-1`'s flag) and by a Lisp `setq` of
  the global to non-nil (`Environment.set`). `endArguments` clears the channel if it is set,
  else restores the saved flag. A new Java writer of the channel must go through
  `publishSpill`, or its values survive the next argument boundary (the old leak, not a crash).
- Cost (2026-09-18, fib 27 + a 2M-call loop, 8 alternating process pairs x 4 steady-state
  iterations, loaded host): a map probe per call measured +5-9%; the flag, 2168 -> 2163 ms
  median, no measurable change. A non-local exit out of an argument leaves the flag zeroed,
  which only means less clearing.
- Compiled backends do NOT clear here yet: `(multiple-value-list (+ 1 (values 5 6)))` answers
  `(6 6)` on JVM and both WASM targets, and cl-ppcre's `(scan "abc" "xyz")` answers
  `(NIL NIL)` there against `(NIL)` on the interpreter and SBCL (`ClPpcreE2eTest` pins both).

## The REPL echo is a consumer
`LispEvaluator.evalValues(form) -> List<LispVal>` is the ONLY multiple-value entry point outside
the macro expander.
- A SYNTACTIC producer (`isSyntacticMultipleValueProducer`) is echoed wrapped in
  `multiple-value-list`; anything else is evaluated UNWRAPPED (`evalResolved`) with the spill
  cleared first and read back after, so a top-level `defun`/`in-package` still evaluates at top
  level. Resolution runs ONCE: package resolution is not idempotent under a `:shadow` package.
- `ReplBuffer.eval` echoes EVERY form right after it runs (as SBCL does);
  `RontoPlayground.evalLine` (`src/web/java`, also the doc site's "Run" cells) echoes the LAST.
- Diffed against SBCL 2.2.9. Remaining differences: a `values` in a non-tail, non-argument
  position (a `progn` body form, a `let` init) nobody consumes leaks;
  `print` omits CL's leading newline / trailing space. ([[gensym-macroexpand]] for
  `macroexpand-1`/`macroexpand`, [[declarations-type-checks]] for `subtypep`'s valid-p,
  [[read-load-streams]] for `read-from-string`'s stop index.)

## Documented deviations
- A `values` in a NON-tail position with no consumer leaves a stale spill -- on the interpreter
  only outside argument positions (above), on the compiled backends everywhere.
- Zero values are not represented: `(values)` publishes nil, so a consumer behind a call reads
  ONE nil -- `(multiple-value-list (g))` with `(defun g () (values))` answers `(NIL)` and Scheme
  `(call-with-values (lambda () (values)) list)` answers `(())` on every backend (R7RS/gosh:
  `()`). A distinct zero-count spill value would make any stale one erase the primary of a
  later consumer (a `(values)`-returning helper called in a loop body would blank the REPL echo
  of the function around it), so it waits on the non-tail clears above. `funcall #'values`
  through the compiled wrapper yields the primary only; the interpreter spills.
- Producers are recognized before user-macro expansion on the interpreter but after it on the
  compile path, so a USER MACRO expanding to `(values ...)` yields all values only when compiled.
- `multiple-value-call` with a builtin `#'name` inherits the wrapper arity:
  `+`/`-`/`*`/`/`/`list`/`min`/`max` are variadic, every other multi-arg builtin is fixed
  unary/binary (a mismatched funcall yields nil on JVM, traps on WASM).

## Wiring points
`LispNames`; `PackageRegistry`; `LispEvaluator.evalCons`; `Environment`; `Jvm`/`WasmExprCompiler`
(+ the floor-family branch around the IntConv compilers); `NoGcWasmCompiler.expandMacro`;
`FreeVarAnalyzer` both walks (expand before walking, flet precedent);
`UserMacroExpander.expandAll` + `LispMacroExpander.rewriteLocalCalls` keeping the mv-bind variable
list verbatim; `BuiltinFunctionWrappers`.

## Tests
`LispEvaluatorTest` (`evalValues*`, `evalMultipleValue*`, `evalNthValue`,
`evalUnwindProtectCleanupKeepsTheProtectedFormsValues`,
`evalSyntacticMvProducerTailPublishesThroughAFunctionReturn`,
`evalMultipleValueConsumerClearsTheSpillChannel`, `evalValuesAtTopLevelIgnoresValuesPassedAsAnArgument`);
`RontoLispCliTest.theSchemeReplEchoesThroughTheSchemePrinter` (the argument case),
`ClPpcreE2eTest.expectedOnTheInterpreter`;
`RontoLispCliTest.replEchoesEveryValueOnItsOwnLine`; `JvmLispCompilerTest.compileAndRun*` and
`WasmLispCompilerIntegrationTest` twins; ci-spec `multiple-values-core`,
`unwind-protect-values` (adds the `--component` leg), `mv-producer-function-return` (its
`find-symbol`/`intern` rows probe a USER symbol because a non-literal name's runtime status
diverges between interpreter and compile paths), `split-sequence-residue-features`,
`rontolisp-package-introspection`. The unwind-protect pins run a cleanup-shape x exit-shape
matrix. Caveats: compiled `print` returns nil; JVM argument evaluation order inside one call
differs -- side-effect assertions go through `setq` in separate top-level forms.
