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
`progv`. The first three force the full eval runtime anyway; `progv` does not, so its gate
entry genuinely costs the fold.

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
  name in any **`special` declaration** (a TYPE-only `declaim` does not block), a
  **computed designator** (`(boundp (intern ...))`), which keeps the eval runtime.

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
  `WasmLispCompilerIntegrationTest#boundpChecksTheGlobalVariableNamespace`.
- **`ci-spec.yaml` covers the UNFOLDED path**: one case calls `eval`, so the concatenated
  program trips the gate on all four backends. If that case ever leaves the spec, the
  driver starts exercising the fold and those answers must still be identical.
