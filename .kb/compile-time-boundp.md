# `(boundp 'name)` is a compile-time fact (always on, both compile paths)

A compiled program is CLOSED: nothing in it can make a global appear at run time, so
"is this name a global HERE?" is decided by the top-level forms before the probe.
`compiler/GlobalVarCollector` answers in DECLARATION ORDER; `compiler/CompileTimeBoundp.fold`
replaces the probe with `t`/`nil` and collapses the `if`/`when`/`unless` it just decided.
**The interpreter is deliberately untouched** (a REPL form can define a global after the
probe was read); its `boundp` answer and error text are pinned.

Motivation: `(unless (boundp '+k+) (defconstant +k+ v))`, the portable redefinition-safe
`defconstant`, costs twice -- `boundp` is an arm of the `usesEval` OR-chain
(`.kb/eval-runtime.md`), and the guard hides the `defconstant` from
`eval/LibraryDefunPruner`, which only drops top-level definers.

## Where it runs
- `RontoLispCli.compileRecorded` / `RontoPlayground.frontend`, before
  `LibraryDefunPruner.prune`, packages NOT resolved.
- `JvmLispCompiler.compile` / `WasmLispCompiler.compile`, after `PackageResolver`;
  idempotent after a CLI-driven fold. On WASM it must run AFTER
  `NoWasiFilesystemStubs.rewrite` -- the fold refuses a program that can `eval`, and
  clack's dead `(read)`/`(eval)` loader IS one until the rewrite removes it.
- **Before resolution only the "unbound" direction is answered** (`packagesResolved`
  false makes `Names` match the unqualified member name): blocking a fold is safe,
  asserting a binding is not.

## Soundness gate
Unsound exactly when a global can appear at run time: `eval`, `load`, `--dynamic`,
`progv`. The first three force the eval runtime anyway; `progv` does not, so its gate
entry genuinely costs the fold. `set` joins them (`.todo/852`): `(set name value)` --
and `(setf (symbol-value name) value)`, which lowers to it per expression, after this
gate, so the raw place shape is scanned too -- creates the binding when the name is
unbound.

## What is decidable
`nil`, `t` and keywords fold to `t`. For a quoted ordinary symbol:
- **`t`** when a STRICTLY EARLIER top-level form binds it unconditionally
  (`defvar`/`defparameter`/`defconstant` with a value, `setq`, or a `progn` of those).
  `GlobalVarCollector` is blind to lexical scope and conditionals, so nothing deeper counts.
- **`nil`** when no earlier top-level form binds it AND the probe sits on the current
  form's straight-line evaluation PREFIX (what makes the guard idiom decidable); the
  prefix flag is cleared by passing any definer or entering a deferring/repeating head.
- Inside a **deferred body** (`lambda`/`defun`/`flet`/...) only the `nil` direction.
- Never answered: a **`cl` symbol** (some are born bound), a valueless **`(defvar x)`**, a
  name in any **`special` declaration** (a TYPE-only `declaim` does not block) -- such a
  special's variable answers at run time (`.kb/dynamic-special-variables.md`, "Bound-ness of a
  special without a value") -- and a **computed designator** (`(boundp (intern ...))`), which
  keeps the eval runtime.
- Never answered either: a name assigned only inside a deferred body. That
  assignment poisons the name, so the probe is left to the run time ("A probe the fold
  leaves open" below). A name no top-level form assigns is a global too when a function
  body assigns it with no lexical binding in scope (`.kb/core-representation.md`); before
  2026-10-04 that store was a function local and the probe answered NIL.
- The poison scan reads assignments through `GlobalVarCollector.assignedPlaces`, the one
  recognition of `setq`/`setf`/`psetq`/`psetf`/`multiple-value-setq` shared with the
  function-body collector (`isAssignmentHead` feeds the prefix flag). Before 2026-10-04 the
  scan knew `setq`/`setf` only: a `psetq`/`psetf`/`multiple-value-setq` in a function body was
  missed and a later probe folded to NIL, where SBCL and the interpreter answer T. A top-level
  one now binds the name for later probes as a `setq` does.
- Measured 2026-10-04: `(defun pp () (psetq *pa* 1 *pb* 2)) (pp) (print (list (boundp '*pa*)
  *pa*))` plus the `multiple-value-setq` twin printed `(NIL 1)` / `(NIL 3)` on the JVM and both
  WASM, now `(T 1)` / `(T 3)` as SBCL (JVM 11,795 -> 12,026, P1 4,658 -> 6,279, component
  5,823 -> 7,446: the variable the answer needs). examples, size-report, bench-report: byte-identical
  (P1 and JVM; size-report and bench-report also `--optimize=size` and component); ci-spec
  program P1 identical but for the build-info string. Pins: `CompileTimeBoundpTest`,
  `ProbedUnboundGlobalFixture` (`psetq`, `multiple-value-setq`, `psetf` in a body).

## A probe the fold leaves open (all four backends, 2026-10-04)

**A literal probe the fold cannot answer reads the variable, not the eval mirror, in a
program without the mirror.** The globals (`GlobalVarCollector.collectProbedUnbound`): a
literal `(boundp 'G)` names G, G is a global (special or not), no `defvar` with a value /
`defparameter` / `defconstant` names it, not a `cl` symbol, and the program calls no
`progv` (whose lowering binds a non-special name in the mirror). They join the tracked set
of `.kb/dynamic-special-variables.md` ("Bound-ness of a special without a value"): the
variable starts as the UNBOUND marker, a store overwrites it, a read answers nil for it,
the probe is `(%global-boundp 'G)`.

- Only where the mirror is absent: a program carrying the eval runtime anyway (`eval`, a
  computed probe, `symbol-value`, ...) keeps the mirror probe, which answers the same, so it
  compiles byte-identically. The gate reads a SUBSET of the final set off the program
  before injection (`collectProbedUnboundBeforeInjection`: the specials, `collect` of the
  non-defun forms, nested defuns, function-body free assignments);
  `requireBoundpOffMirror` checks the final set.
- JVM: the marker is `JvmDynVarRuntimeBuilder.unboundMarker` (`_unbound`, seeded in
  `<clinit>`), independent of the ThreadLocal runtime. A global without a `_d$` field reads
  `getstatic` + an inline marker test and probes by comparing its field with the marker; a
  bound special keeps `_dget`/`_dbound`. Excluded from `JvmRawGlobals` (the raw shadow would
  hand out the marker). Wasm: `Ctx.unboundGlobals`, the same sentinel, read and probe as for
  a special.
- Measured 2026-10-04 (before -> after, `--class-name P` / P1 / component bytes).
  `(defun s () (setq *z* 1)) (s) (print (boundp '*z*))`: JVM 47,211 -> 6,192, P1 2,364 ->
  1,821, component 3,535 -> 2,979 (reading `(if *z* t nil)` instead: 6,294 / 1,807 / 2,965).
  `ProbedUnboundGlobalFixture`: JVM 51,752 -> 17,190, P1 15,777 -> 10,649. examples,
  size-report, bench-report and the ci-spec program: byte-identical (P1, `--optimize=size`,
  component, JVM; 1,938 outputs), as are the d01 fixtures. 200M reads of a tracked global in
  a loop: JVM unchanged (~0.26 s), wasm 1.18 -> 1.26 s.
- Pins: `ProbedUnboundGlobalFixture` on
  `aLiteralBoundpOfAGlobalWithoutAValueAnswersTheStoresMadeSoFar` (`LispEvaluatorTest`,
  `JvmLispCompilerTest` with the no-eval-runtime check, `WasmLispCompilerIntegrationTest` P1 +
  component), `WasmLispCompilerTest#aProgramWhoseEveryBoundpReadsAVariableCarriesNoEvalRuntime`,
  `GlobalVarCollectorTest`. ci-spec cannot pin it: its program calls `eval`.

## What the fold leaves behind
- TOP LEVEL: the surviving branch is spliced INTO the top-level list, restoring the
  definition as a top-level definer.
- Elsewhere the form collapses IN PLACE, and only on a cons this pass rewrote, so it never
  becomes a general constant folder. Handled: `if`/`when`/`unless`/`not`/`null` and the
  FIRST argument of `and`/`or` -- required by the initform spelling
  `(defconstant +k+ (if (boundp '+k+) (symbol-value '+k+) 1))`, whose dead `symbol-value`
  arm is otherwise its own `usesEval` arm.
- A `cond` guard is NOT collapsed (macro expansion runs after the gate scan and shaker).
- Owes both halves of `.kb/source-positions.md`: same list back when nothing was
  decidable, folded conses inherit positions via `SourceProvenance.inherit`.

**`fboundp` is deliberately out**: same OR-chain arm and idiom, but its answer is the
FUNCTION registry including every backend built-in -- a set this pass does not have.

## Pins
- `compiler/CompileTimeBoundpTest` -- every direction and refusal.
- `JvmLispCompilerTest#theDefineConstantGuardCompilesToTheBareDefinition`,
  `WasmLispCompilerIntegrationTest#aLiteralBoundpCostsNothingWhileAComputedOneStillCarriesTheEvalRuntime`
  -- byte-identical to the answer; the size half is WASM-only on purpose and read off the
  SHAKEN module.
- Runtime answers: `JvmLispCompilerTest#compileAndRunBoundp`,
  `WasmLispCompilerIntegrationTest#boundpChecksTheGlobalVariableNamespace`;
  a probe of a lambda-assigned global:
  `JvmLispCompilerTest#compileAndRunBoundpSeesAnAssignmentMadeInsideALambda`,
  `WasmLispCompilerIntegrationTest#boundpSeesAnAssignmentMadeInsideALambda` (+ `--component`
  twin), `LispEvaluatorTest#boundpChecksTheGlobalVariableNamespace`, the
  `symbol-runtime-api` ci-spec case (b78).
- **`ci-spec.yaml` covers the UNFOLDED path**: one case calls `eval`, so the concatenated
  program trips the gate on all four backends. If that case ever leaves the spec, the
  driver starts exercising the fold and those answers must still be identical.
