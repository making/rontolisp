# Multiple values -- syntactic tier

The `multiple-value-bind`-over-`floor`/`gethash` idioms real CL code uses, WITHOUT a runtime
multiple-value representation. A `%mv-spill` global carries the cases the syntactic tier cannot.

## What ships
- `values` is a CL **function** (`CL_FUNCTIONS`, `Environment`, a variadic `&rest`
  `BuiltinFunctionWrappers` entry, `expandValues` in call position). NOT in `expandBuiltinMacro`,
  so `macroexpand-1` leaves it alone as in CL.
- `multiple-value-bind`, `multiple-value-list`, `multiple-value-call`, `nth-value` (CL_MACROS +
  `expandBuiltinMacro`; `multiple-value-call` as a macro deviates from CL's special operator).
- Secondary values for `floor`/`ceiling`/`round`/`truncate` and `gethash`, ONLY inside consumers.
  Two-argument `(floor a b)` elsewhere: `expandFloorFamilyDivisor` -> `(floor (/ a b))`.

## The lowering (`LispMacroExpander`)
`lowerMvProducer` -> `MvProducer{bindings, values}`: ordered `__mv<id>` temps (`MV_COUNTER`) as
**nested single-binding lets** (`nestMvBindings`) so evaluation order holds on every backend.
`isMvProducerForm` recognizes literal `values`, the floor family, `gethash` (a runtime `(gensym)`
sentinel default plus `(eq v sentinel)` distinguishes a stored nil from a missing key), else one
temp. Consumers: `expandMultipleValueBind` (missing -> nil, surplus evaluated and dropped),
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
- Open gap: `handler-case`'s `:no-error` clause is not a multiple-value consumer at all --
  `(handler-case (values 1 2 3) (:no-error (a b c) ...))` leaves `b`/`c` unbound.

## A syntactic producer's tail escapes through the spill
**Invariant: the tier boundary is not observable through a function return.** A recognized
producer (floor family, `gethash`, `find-symbol`, `intern`, `array-displacement`) in a
value-escaping position publishes its secondary to `%mv-spill`
(`LispMacroExpander.spillEscapingMvProducers`), so `(defun f (h) (gethash "K" h))` answers two
values however many calls away, including through a `defmethod`. Wiring is SELECTIVE -- an
unconditional spill would tax the hottest built-ins on every call:
- TAIL positions only, through `progn`/`locally`, the `let` family, `flet`/`labels`/`macrolet`
  bodies, `if`/`when`/`unless`, the last form of `and`/`or`, `cond`/`case`/`typecase` clause
  bodies (a bodyless `(test)` clause keeps primary-value-only semantics), `block` family,
  `multiple-value-bind` bodies, an `unwind-protect` protected form, `return`/`return-from`, `the`.
- `injectMvSpillGlobal` applies it to every top-level `defun` body, gated on `usesMv`;
  interpreter `evalDefun`, ungated; `--no-gc` never. A producer LEXICALLY inside a consumer is
  intercepted by the consumer's expansion first.
- Deliberate gaps: a producer tail in a bare `lambda` or a `flet`/`labels` LOCAL function body is
  not rewritten on any path; a non-tail `return-from`/`go` escape is not scanned; `handler-case`
  and `multiple-value-prog1` are not tail contexts.

## The REPL echo is a consumer
`LispEvaluator.evalValues(form) -> List<LispVal>` is the ONLY multiple-value entry point outside
the macro expander.
- A SYNTACTIC producer (`isSyntacticMultipleValueProducer`) is echoed wrapped in
  `multiple-value-list`; anything else is evaluated UNWRAPPED (`evalResolved`) with the spill
  cleared first and read back after, so a top-level `defun`/`in-package` still evaluates at top
  level. Resolution runs ONCE: package resolution is not idempotent under a `:shadow` package.
- `ReplBuffer.eval` echoes EVERY form right after it runs (as SBCL does);
  `RontoPlayground.evalLine` (`src/web/java`, also the doc site's "Run" cells) echoes the LAST.
- Diffed against SBCL 2.2.9. Remaining differences: a non-tail `values` nobody consumes leaks;
  `read-from-string`/`subtypep` are single-valued ([[gensym-macroexpand]] for
  `macroexpand-1`/`macroexpand`); `print` omits CL's leading newline / trailing space.

## Documented deviations
- A `values` in a NON-tail position with no consumer leaves a stale spill. `funcall #'values`
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
`evalMultipleValueConsumerClearsTheSpillChannel`);
`RontoLispCliTest.replEchoesEveryValueOnItsOwnLine`; `JvmLispCompilerTest.compileAndRun*` and
`WasmLispCompilerIntegrationTest` twins; ci-spec `multiple-values-core`,
`unwind-protect-values` (adds the `--component` leg), `mv-producer-function-return` (its
`find-symbol`/`intern` rows probe a USER symbol because a non-literal name's runtime status
diverges between interpreter and compile paths), `split-sequence-residue-features`,
`rontolisp-package-introspection`. The unwind-protect pins run a cleanup-shape x exit-shape
matrix. Caveats: compiled `print` returns nil; JVM argument evaluation order inside one call
differs -- side-effect assertions go through `setq` in separate top-level forms.
