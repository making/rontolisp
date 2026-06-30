# 29 - `loop` macro follow-ups (extend the supported clause subset)

The first cut of `loop` shipped (numeric/list/`=` stepping, `with`, the seven
accumulators with `into`, `while`/`until`/`repeat`/`do`/`return`/`initially`/
`finally`, and `when`/`if`/`unless` with `else`/`end`). It lowers to the existing
core via `LispMacroExpander.expandLoop` + the private `LoopExpander` class
(parse-into-pieces then build a `%block`/`let*`/`while`). Wired in:
`LispEvaluator`, `Jvm/WasmExprCompiler`, `ScalarWasmCompiler.expandMacro`,
`FreeVarAnalyzer` (both methods). Docs: `doc/{en,ja}/reference/macros/loop.md`.

These ANSI `loop` features are still unimplemented and documented as limitations
in `loop.md`. Each item notes how it maps onto the existing `LoopExpander`.

## Quick wins (small, self-contained — close these first)

- **DONE** `for VAR across STRING`: iterates a string's characters via an index
  cursor (`LoopExpander.parseForAcross`). Tested on all four backends
  (eval/JVM/WASM P1+component) plus a `ci-spec.yaml` case; documented in
  `loop.md` and the limitations lists.

- **`with VAR = INIT and VAR2 = INIT2` parallel binding**: today `parseWith`
  treats `and` as another sequential (`let*`) binding. CL makes `and`-joined
  `with` bindings parallel. Low value (only differs when a later init references
  an earlier var), but if desired, evaluate the `and`-group's inits into temps
  first. Consider leaving as a documented divergence instead.

## Medium

- **Anaphoric `it`**: `when TEST collect it` (also `sum`/`return`/etc.) collects
  the test value. In `parseConditional`, bind the test to a gensym
  (`__loop_it`), declare it in `bindings`, emit `(setq __loop_it TEST)` before
  the `if`, use `__loop_it` as the condition, and substitute the `it` symbol with
  `__loop_it` inside the selectable clause's parsed expression (walk the
  resulting `LispVal` tree). Nesting makes the substitution scope tricky — give
  each conditional its own `it` var and only substitute within that branch.

- **`thereis` / `always` / `never`**: value-returning termination clauses.
  `always EXPR` keeps a result that starts `t` and short-circuits to `nil` (via a
  `return nil`) the first time EXPR is nil; `never EXPR` is the negation;
  `thereis EXPR` returns the first non-nil EXPR via `return`. These interact with
  the loop's default result value — `always`/`never` return `t`/`nil` on normal
  termination, so they need to override `resultExpr()`. Implement as: on a falsy
  (or truthy) test do `(return <val>)`, and set the normal-completion result.

## Harder (needs control-flow we don't have yet)

- **`while`/`until` at textual position**: currently every termination test is
  hoisted into the `while` head, so `while`/`until` fire at the top of the
  iteration regardless of where they appear. Honoring their position needs a
  mid-body jump to the epilogue (finally + result). We have no `go`/`tagbody`;
  `return` skips `finally`. Would need a "normal finish" non-local exit distinct
  from `return` (e.g. a second `%block` layer whose value flows into finally).

- **`loop-finish`**: same machinery as above — a non-local jump to the
  finally+result epilogue (NOT skipping `finally`, unlike `return`).

- **`named NAME` + `return-from NAME`**: needs named block support
  (`block`/`return-from`), which rontolisp does not have generally. Out of scope
  until block/return-from exist.

- **Parallel `and` between `for` clauses**: `for a = ... and b = ...` steps the
  group in parallel (all steps computed against the old values, then assigned).
  `LoopExpander` appends per-clause steps sequentially (do*-style). Parallel
  stepping needs the temp-swap trick `LispMacroExpander.makeStepForms` already
  does for `do` — refactor `steps` to support grouped parallel assignment.

- **Destructuring binds** (`for (a b) in pairs`, `with (x . y) = ...`): needs a
  destructuring helper shared with a future `destructuring-bind`. Out of scope.

## Tests / docs reminders

- Per CLAUDE.md: add cases to `LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`; consider a `ci-spec.yaml` case (then rebuild
  native + run `CiSpecE2eTest`). Keep `doc/en` and `doc/ja` `loop.md` in sync
  (byte-identical code fences) and update the limitations list as items land.
