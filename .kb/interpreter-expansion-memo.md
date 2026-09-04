# The interpreter expands a built-in macro once per call site

**Invariant: the interpreter's built-in macro arms expand a form ONCE per source
occurrence (memoized by cons identity), like `defmacro` calls (`userMacroExpansions`)
and like the three compile backends. The arms below read evaluator state and stay
re-expanded every evaluation; an arm not listed may only join the memo if its expander
takes nothing but the form plus compile-time-constant flags.**

Safe because `LispMacroExpander` holds NO mutable static state (every field
`static final`; generated variables are fixed names like `__cond`, not gensyms).

## Arms that MUST re-expand (state read)

- `error`, `cerror`, `warn`, `signal` — `restartRuntimeLoaded` (re-expansion is what
  makes a signal AFTER the restart runtime loads see the handler hook); `closRegistry`
- `make-instance`, `change-class`, `make-condition`, `define-condition` —
  `closRegistry`; `change-class` also resolves its designator in the live env
- `handler-bind`, `typep`, `typecase`, `etypecase`, `ctypecase`, `streamp`, `coerce`
  (packed lowering) — `closRegistry`
- `setf` — user `defsetf`/`define-setf-expander` expanders, user macro places,
  `(setf (macro-function ...))` aliasing, lazy prelude setf places
- `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string`, `write-to-string`,
  `%princ-piece`, `%prin1-piece` — per-call `print-object` routing (`closRegistry`) and
  the live `*print-case*` gate
- `flet`, `labels` (`preExpandLocalMacros`), `symbol-macrolet` — the live user-macro table
- `read`, `floor`/`ceiling`/`round`/`truncate`, `reduce`, `sort` — partial (nullable)
  lowerings falling through to the ordinary call; the probe is one cheap shape check

Place-writing macros (`push`, `pop`, `incf`, `decf`, `pushnew`, `remf`, `psetf`,
`rotatef`, `shiftf`) ARE memoized: they lower to `(setf place ...)` and the `setf` arm
re-expands, so a later user setf expander is still seen.

## Semantic change

The memo keys on mutable `LispCons` identity, so a program that rewrites a macro FORM
ITSELF between evaluations keeps the first expansion. NOT frozen: a mutation reaching a
subform's VALUE through shared structure (the expansion splices the original subform
objects — `.kb/quoted-data.md`).

## Threading

`userMacroExpansions`' stance verbatim: own monitor, never held across an expansion; two
threads racing both expand, last write wins. Bounded by `EXPANSION_MEMO_LIMIT`; past the
bound the arm recomputes.

**Trap: the monitor covers the map read, never the evaluation — on the hit path as much
as the miss path.** `expandUserMacro` returns the expansion for its caller to evaluate;
`evalBuiltinMacro` evaluates for its caller and must release the monitor itself. An
expansion can hand over to another thread (`objc:`/`appkit:`/`metal:`/`scene:` via
`am.ik.objc.MainThread.sync`; a blocking socket read), which then cannot take the
monitor — both park, symptom is a test class hung with every thread in `park`. Holding
it across the hit-path `eval` deadlocked `SceneOffscreenRenderTest`.

Win scales with expansion SIZE (`case`/`loop` ~1.6-2.3x; `when` is noise).

## Tests

- `LispEvaluatorTest.aRewrittenBuiltinMacroFormKeepsItsFirstExpansion`
- `LispEvaluatorTest.aMemoizedBuiltinMacroDoesNotHoldTheMemoWhileItsExpansionRuns`
- `LispEvaluatorHotMethodSizeTest` — the arms must not push either `evalCons` half over
  the 8000-bytecode cliff (`.kb/hot-path-method-size.md`)
