# A top-level form is a STATEMENT: nothing may be emitted only to be dropped

Scope: both compile paths (`codegen.wasm`, `codegen.jvm`); the interpreter is unaffected.
`_start` (wasm) and `main` (JVM) drop every top-level form's value, so a value produced only
because a form has to produce something is code no run can observe.
`am.ik.rontolisp.compiler.ToplevelStatements` recognizes the two such shapes once, for both
backends:

- **the form IS a constant** -- `'CHIPZ` (what `PackageResolver` leaves for `defpackage` and the
  `%push-package` marker), `nil` (an unselected `eval-when`, a `declaim`), a stray docstring, a
  keyword: `ToplevelStatements.prune` deletes the form. (`in-package` leaves
  `(setq *package* :CHIPZ)`, dropped wholesale by `LispMacroExpander.injectMvSpillGlobal` when
  nothing reads `*package*` -- [[packages]].)
- **name-valued definer** -- `defvar`/`defparameter`/`defconstant`: the top-level emitter OFFERS
  the form the chance to emit no name; the defvar compiler takes it.

## The offer protocol
- The definer still compiles through the ordinary `compileExpr`, keeping source-position
  attribution (`SourceProvenance.noteFailure`) and, on wasm, async-spine handling.
- `Ctx.definerNameDropped` is set to the form itself just before the call;
  `Wasm`/`JvmDefvarCompiler` clears it and skips the name; the emitter reads it back and emits
  `drop`/`pop` only if the offer was NOT taken.
- **The read-back is load-bearing.** The offer is keyed on the operator name while special-form
  dispatch switches on the RAW symbol name, so a spelling that passes the offer's test but is
  not routed to the defvar compiler (package-qualified, a future user-macro interception) leaves
  its value on the stack. Getting it wrong emits an INVALID MODULE, not a mis-optimization.
- The identity key is `== cons`, so a definer nested in an init expression cannot take its
  parent's offer.

## Rules
- **Not behind `--optimize`**: neither shape is a trade-off. Same standing as [[pure-builtin-fold]].
- **Only constants are pruned.** A bare non-keyword symbol can signal unbound-variable, and
  signalling is an effect; a call with literal arguments is `PureBuiltinFolder`'s question and
  arrives here already constant.
- `prune` only DELETES, so [[source-positions]]' cons-identity rule holds trivially.
- A NEW definer whose value is nothing but the name it bound belongs in `isNameValuedDefiner`,
  and its backend compiler must take the offer or the name gets built and dropped.
- A top-level `defvar`'s staging tee is emitted only when `WasmSetqCompiler.mirrorsTopLevelGlobal(ctx)`
  says `mirrorTopLevelGlobal` will read it back. `setq` keeps its tee unconditionally -- there the
  staged value is the form's own result.

## User-visible corollary: a run's EXIT CODE
The last top-level form's value is dropped on every backend, so `(rove:run-suite *package*)` at
the end of a file says nothing to the shell and a red suite exits 0 -- as `sbcl --script`
behaves, deliberately kept. A program wanting status calls `uiop:quit`; a rove suite gets one
from the `rontolisp test` subcommand ([[asdf]]).

## Tests
`ToplevelStatementsTest`; `WasmLispCompilerTest.aTopLevelFormThatIsNothingButAConstantEmitsNothing`,
`.aTopLevelDefinerDoesNotBuildTheNameSymbolItReturns`;
`JvmLispCompilerTest.aDefinerWhoseValueIsReadStillYieldsTheNameSymbol`.

## Related
[[pure-builtin-fold]], [[optimize-dead-code-elimination]], [[wasm-gc-strings]],
[[wasm-function-body-size]], [[packed-integer-vectors]].
