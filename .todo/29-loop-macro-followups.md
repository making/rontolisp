> **Update 2026-07-05 (split-sequence e2e, .todo/54 Phase 3):** loop keywords
> now match by package-stripped symbol name (`:for`, `pkg::into` spellings),
> `of-type` is parsed and discarded (for/with/accumulation positions, also
> after `into`), and `into` list accumulation is in-order via a tail cursor
> (a mid-loop `return` reading the into variable used to see the reversed
> list).

# 29 - `loop` macro follow-ups (extend the supported clause subset)

The first cut of `loop` shipped (numeric/list/`=` stepping, `with`, the seven
accumulators with `into`, `while`/`until`/`repeat`/`do`/`return`/`initially`/
`finally`, and `when`/`if`/`unless` with `else`/`end`). It lowers to the existing
core via `LispMacroExpander.expandLoop` + the private `LoopExpander` class
(parse-into-pieces then build a `%block`/`let*`/`while`). Wired in:
`LispEvaluator`, `Jvm/WasmExprCompiler`, `ScalarWasmCompiler.expandMacro`,
`FreeVarAnalyzer` (both methods). Docs: `doc/{en,ja}/reference/macros/loop.md`.

## Done (second cut, 2026-07)

- **DONE** `for VAR across SEQ` (`LoopExpander.parseForAcross`): strings and
  vectors — the element accessor is a runtime `(if (stringp seq) (char ...)
  (aref ...))` branch because the sequence type is unknown at expansion time.
- **DONE** (related, outside loop) multi-pair `setf`: `(setf p1 v1 p2 v2 ...)`
  now expands to a `progn` of single setfs (`LispMacroExpander.expandSetf`);
  previously the extra pairs were SILENTLY dropped. An even argument count
  throws.
- **DONE** CL-faithful driver sequencing: an `in`/`across` variable holds its
  first element already at BINDING time (so a later sequential clause's init
  sees it — `for x in xs for a = (f x)`), is re-synced from its cursor at the
  END of the step forms (`ForPiece.postSteps`; no more top-of-body preBody),
  and stepping stops at the first exhausted driver (each later for/repeat
  group's steps are guarded by the earlier drivers' `driverEndTests`, exiting
  through the inline epilogue). This is what makes the fold-left idiom
  (`for x in xs for a = (funcall fn init x) then (funcall fn a x)`) work; the
  guarded `across` element read returns nil past the end.
- **DONE** `with VAR = INIT and VAR2 = INIT2` parallel binding: `and`-joined
  `with` inits are evaluated into temps before any variable binds.
- **DONE** Anaphoric `it`: each `when`/`if`/`unless` that references `it` in a
  branch stores its raw test value in a per-conditional gensym and substitutes
  `it` within that conditional's branches (nested conditionals substitute first,
  so `it` binds to the nearest test; the walk does not descend into `quote` or
  nested `loop` forms).
- **DONE** `thereis`/`always`/`never`: early exit is a `return` (skips
  `finally`, like CL); `always`/`never` override the normal-completion result to
  `t` via `terminationT`. Combining with implicit accumulation throws.
- **DONE** `while`/`until` at textual position: a test before any body clause
  (and with no `preBody`, i.e. no `in`/`on`/`across` var assignment) is still
  hoisted into the `while` head; otherwise it becomes a body statement that
  exits through an inline copy of the epilogue (`postLoop` + `finally` +
  `(return result)` — the `return` targets the loop's own `%block`). This also
  fixed `for x in l while (p x)`, which previously evaluated the hoisted test
  before `x` was assigned.
- **DONE** `loop-finish`: `(loop-finish)` inside `initially`/body forms is
  substituted with the same inline epilogue exit at build time (so `finally`
  runs, unlike `return`). The substitution skips `quote`/`function`/`lambda`/
  `defun`/nested `loop`/`do`/`do*`/`dolist`/`dotimes`/`%block` — inside those a
  `loop-finish` is left alone and surfaces as an undefined function. Must be in
  statement position (compilers cannot `return` mid-expression).
- **DONE** Parallel `and` between `for` clauses: `parseFor` collects an
  `and`-group of `ForPiece`s; `flushForGroup` defers every user-variable binding
  behind temps (so a later init sees outer bindings) and merges all step pairs
  through `makeStepForms` (the same temp-swap `do` uses) for parallel stepping.
- **DONE** Destructuring binds (`for (a b) in pairs`, `with (x y) = ...`,
  `for (a b) = ... then ...`, `for (x) on ...`): the shared
  `LispMacroExpander.destructurePairs` walker (lifted out of `LoopExpander`
  when `destructuring-bind` landed; also its keyword-free fast path) walks a
  pattern into car/cdr accessor chains; `for` patterns bind vars to nil
  and re-destructure in `preBody` each iteration. Patterns are proper lists only
  — the reader rejects dotted-pair syntax, so `(a . b)` cannot even be written;
  lambda-list keywords are not recognized in loop patterns (use
  `destructuring-bind` in the body for those).

Tests: `LispEvaluatorTest#evalLoop*`, `JvmLispCompilerTest#compileAndRunLoop*`,
`WasmLispCompilerIntegrationTest#loopExtendedClausesCompileAndRun`,
`ci-spec.yaml` case `loop-macro-extended-clauses`.

## Still out of scope

- **`named NAME` + `return-from NAME`**: needs named block support
  (`block`/`return-from`), which rontolisp does not have generally. Out of scope
  until block/return-from exist.

- **`being` (hash-table/package iteration)**: needs a way to enumerate hash
  keys/values as a driver; revisit if a `hash-table-keys`-style primitive lands.

- **Dotted destructuring patterns**: blocked on the reader (no dotted-pair
  syntax).
