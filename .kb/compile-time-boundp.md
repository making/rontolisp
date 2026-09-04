# `(boundp 'name)` is a compile-time fact (always on, both compile paths)

A compiled program is CLOSED: nothing in it can make a global appear at run time, so
"is this name a global HERE?" is decided by the top-level forms before the probe.
`compiler/GlobalVarCollector` answers in DECLARATION ORDER; `compiler/CompileTimeBoundp.fold`
replaces the probe with `t`/`nil` and collapses the `if`/`when`/`unless` it just decided.
**The interpreter is deliberately untouched** (a REPL form can define a global after the
probe was read); its `boundp` answer and error text are pinned.

Motivation -- `(unless (boundp '+k+) (defconstant +k+ v))`, the portable
redefinition-safe `defconstant` (chipz x12, yason, iterate, mgl-pax, global-vars, slime)
costs twice: `boundp` is an arm of the `usesEval` OR-chain of `JvmLispCompiler` /
`WasmLispCompiler` (emits `_eval`/`_lookup`/the global mirror and forces EVERY arity
dispatcher, `.kb/eval-runtime.md`), and the guard hides the `defconstant` from
`eval/LibraryDefunPruner`, which only drops top-level definers.

## Where it runs

- `RontoLispCli.compileRecorded` and `RontoPlayground.frontend`, before
  `LibraryDefunPruner.prune` -- packages NOT resolved; makes guarded definitions
  shakeable.
- `JvmLispCompiler.compile` / `WasmLispCompiler.compile`, after `PackageResolver` --
  packages resolved; clears the `usesEval` gate scan and keeps a direct compiler
  invocation equivalent. Idempotent after a CLI-driven fold.
- On WASM that run sits AFTER `NoWasiFilesystemStubs.rewrite`: the fold refuses a program
  that can `eval`, and clack's DEAD `(read)`/`(eval)` file loader IS such a program until
  the rewrite removes it.

**Before resolution only the "unbound" direction is answered** (`+k+` in two packages is
one string there). `packagesResolved` false makes `Names` match the unqualified member
name and withholds the `t` answer -- blocking a fold is safe, asserting a binding is not.

## Soundness gate

Unsound exactly when a global can appear at run time: `eval`, `load`, `--dynamic`,
`progv` (its lowering can bind a runtime-named symbol in the eval env mirror). The first
three force the full eval runtime anyway; `progv` does not, so its gate entry genuinely
costs the fold. `set` / `(setf (symbol-value ...))` would belong here if the language ever
gains them.

## What is decidable

`nil`, `t` and keywords fold to `t` without a lookup. For a quoted ordinary symbol:

- **`t`** when a STRICTLY EARLIER top-level form binds it unconditionally:
  `(defvar/defparameter/defconstant NAME value)`, `(setq NAME ...)`, or a `progn` of
  those. `GlobalVarCollector`'s scan is blind to lexical scope and conditionals -- right
  for refusing a fold, wrong for asserting one, so nothing deeper counts.
- **`nil`** when no earlier top-level form binds it AND the current form either does not
  bind it or the probe sits on that form's straight-line evaluation PREFIX (what makes
  the guard idiom decidable). The prefix flag is cleared by passing any definer and by
  entering a deferring or repeating head.
- Inside a **deferred body** (`lambda`/`defun`/`defmethod`/`flet`/`labels`/...) written
  position proves nothing: only the whole-program question, and only in the `nil`
  direction.

Never answered either way: a **`cl` symbol** (some are born bound -- the standard stream
variables are seeded into the `_genv` mirror); a name from a valueless **`(defvar x)`**
(proclaims special, binds nothing -- a later `let` is what makes `boundp` true); a name in
any **`special` declaration** (`declaim`/`proclaim`/`declare`) -- but a TYPE-only
`declaim` binds nothing and does not block the guard after it; a **computed designator**
(`(boundp (intern ...))`), which keeps the runtime probe and the eval runtime with it.

## What the fold leaves behind

Deciding the probe is not the whole job -- the tested form must go too, or the guard's
other arm holds the gate open via another OR-chain member.

- TOP LEVEL: the surviving branch is spliced INTO the top-level list, restoring the
  definition as a top-level definer. `(unless nil BODY...)` -> `BODY...`;
  `(when nil ...)` / `(unless t ...)` dropped; `(if t a b)` / `(if nil a b)` -> the taken
  branch.
- Elsewhere the form collapses IN PLACE, and only on a cons this pass rewrote, so it never
  becomes a general constant folder. Handled: `if` / `when` / `unless` / `not` / `null`,
  plus the FIRST argument of `and` / `or`.
- The in-place half is required by the initform spelling
  `(defconstant +k+ (if (boundp '+k+) (symbol-value '+k+) 1))` -- cl-ppcre, cl-who,
  flexi-streams, cl-base64, cl-unicode (alexandria via `not`, cl-json via `and`).
  Without the collapse the dead `(symbol-value '+k+)` arm survives, and `symbol-value` is
  its own arm of the `usesEval` chain.
- A `cond` guard is NOT collapsed; ordinary macro expansion runs after both the gate scan
  and the shaker, so that spelling still pays. Nothing in the local dist writes it.
- `ClRedefinitionWarnings` concerns FUNCTION redefinition only; the fold can only REMOVE a
  definition, never add one.
- Owes both halves of `.kb/source-positions.md`: same list back when nothing was
  decidable, rebuilds through `LispCons.rebuilt`, and every cons on the path to a folded
  probe inherits the replaced cons's position (`SourceProvenance.inherit`).

## `fboundp` is deliberately out

Same OR-chain arm, same idiom, but its answer is the FUNCTION registry, which includes
every backend built-in -- a set this pass does not have, and deciding `(fboundp 'car)`
wrongly is a miscompile. Revisit if a shared "every callable name" set ever exists.

## Pins

- `compiler/CompileTimeBoundpTest` -- every direction and refusal, the pre-resolution
  restriction, the unchanged-list identity rule.
- `JvmLispCompilerTest#theDefineConstantGuardCompilesToTheBareDefinition`,
  `WasmLispCompilerIntegrationTest#aLiteralBoundpCostsNothingWhileAComputedOneStillCarriesTheEvalRuntime`
  -- a decided probe compiles BYTE-IDENTICALLY to its answer. The size half is WASM-only
  on purpose (the JVM gate is deliberately wide and its class shaker trims the runtime, so
  gate state is not readable from class size -- `.kb/eval-runtime.md`) and is read off the
  SHAKEN module: an unshaken one carries every runtime helper (126 KB) whatever the gate
  says.
- `JvmLispCompilerTest#compileAndRunBoundp`,
  `WasmLispCompilerIntegrationTest#boundpChecksTheGlobalVariableNamespace`,
  `standardStreamVariablesAreBoundToTheirDefaultsThroughTheSymbolApi` -- runtime answers
  unchanged; the stream-variable case is `cl` symbols throughout, keeping the runtime arm
  exercised.

**`ci-spec.yaml` covers the UNFOLDED path**: the driver concatenates every case into one
program and one calls `eval`, so the gate trips and the whole spec compiles with the fold
off on all four backends. Re-evaluation trigger -- if the `eval` case ever leaves the
spec, the driver starts exercising the fold and those answers must still be identical.
