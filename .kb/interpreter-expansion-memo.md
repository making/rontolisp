# The interpreter expands a built-in macro once per call site

**Invariant: the interpreter's built-in macro arms expand a form ONCE per source
occurrence (memoized by cons identity), like `defmacro` calls (`userMacroExpansions`) and
like the three compile backends. The arms below read evaluator state and stay re-expanded;
an arm not listed may join the memo only if its expander takes nothing but the form plus
compile-time-constant flags.**

## Arms that MUST re-expand (state read)
- `error`, `cerror`, `warn`, `signal` — `restartRuntimeLoaded`, `closRegistry`
- `make-instance`, `change-class`, `make-condition`, `define-condition`, `handler-bind`,
  `typep`, `typecase`, `etypecase`, `ctypecase`, `streamp`, `coerce` — `closRegistry`
- `setf` — user `defsetf`/`define-setf-expander`, user macro places, `(setf (macro-function ...))`
- `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string`, `write-to-string`,
  `%princ-piece`, `%prin1-piece` — `print-object` routing, live `*print-case*`
- `flet`, `labels` (`preExpandLocalMacros`), `symbol-macrolet` — live user-macro table
- `read`, `floor`/`ceiling`/`round`/`truncate`, `reduce`, `sort` — partial (nullable) lowerings

Place-writing macros (`push`, `pop`, `incf`, `decf`, `pushnew`, `remf`, `psetf`, `rotatef`,
`shiftf`) ARE memoized: they lower to `(setf place ...)` and that arm re-expands.

## Traps
- The memo keys on mutable `LispCons` identity: rewriting a macro FORM ITSELF between
  evaluations keeps the first expansion. A mutation reaching a subform's VALUE through
  shared structure is not frozen (`.kb/quoted-data.md`).
- **The monitor covers the map read, never the evaluation — hit path as much as miss path.**
  An expansion can hand over to another thread (`am.ik.objc.MainThread.sync`, a blocking
  socket read) which then cannot take it; this deadlocked `SceneOffscreenRenderTest`.
- Bounded by `EXPANSION_MEMO_LIMIT`; past it the arm recomputes. Racing threads both expand,
  last write wins.

## Tests
- `LispEvaluatorTest.aRewrittenBuiltinMacroFormKeepsItsFirstExpansion`,
  `LispEvaluatorTest.aMemoizedBuiltinMacroDoesNotHoldTheMemoWhileItsExpansionRuns`
- `LispEvaluatorHotMethodSizeTest` — arms must not push either `evalCons` half over the
  8000-bytecode cliff (`.kb/hot-path-method-size.md`)
