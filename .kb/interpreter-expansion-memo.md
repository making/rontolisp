# The interpreter expands a built-in macro once per call site

**Invariant: the interpreter's built-in macro arms expand a form ONCE per source
occurrence (memoized by cons identity), like `defmacro` calls (`userMacroExpansions`) and
like the three compile backends. The arms below read evaluator state and stay re-expanded;
an arm not listed may join the memo only if its expander takes nothing but the form plus
compile-time-constant flags.**

## Arms that MUST re-expand (state read)
- `error`, `cerror`, `warn`, `signal` — `restartRuntimeLoaded`, `closRegistry`
- `make-instance`, `change-class`, `make-condition`, `define-condition`, `handler-bind`,
  `typep`, `typecase`, `etypecase`, `ctypecase`, `check-type`, `coerce` — `closRegistry` (`streamp` is no
  arm at all: the function value reads the registry after its argument ran, `.kb/gray-streams.md`)
- `setf` — user `defsetf`/`define-setf-expander`, user macro places, `(setf (macro-function ...))`
- `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string`, `write-to-string`,
  `%princ-piece`, `%prin1-piece` — `print-object` routing, live `*print-case*`
- `flet`, `labels` (`preExpandLocalMacros`), `symbol-macrolet`, `with-slots`, `with-accessors` — live
  user-macro table (they memoize in `userMacroExpansions` under the table's epoch: `expandedPerSite`)
- `read`, `floor`/`ceiling`/`round`/`truncate`, `reduce`, `sort` — partial (nullable) lowerings
- the multiple-value consumers (`multiple-value-bind`/`-list`/`-call`/`-setq`/`-prog1`,
  `nth-value`) and lambda tails, ONLY once the program methods a producer — the methoded set
  (`.kb/clos.md`, "Interpreter, the call side"); memoized as before while it is empty

Re-expanding `setf` on every evaluation is a real cost, but a memo
would need a version bumped by every state read above; not done.

Two per-EVALUATION costs of every call, not memos, found in profiling: a
package-qualified operator asked the two uiop probes through two `splitQualified` splits
(now `LispEvaluator.isUiopOperator`, a per-name answer), and
`normalizeBindingList` rebuilt every `let`'s binding list (now it returns the list
itself unless an entry is a bare symbol or `(x)`).

Place-writing macros (`push`, `pop`, `incf`, `decf`, `pushnew`, `remf`, `psetf`, `rotatef`,
`shiftf`) ARE memoized: they lower to `(setf place ...)` and that arm re-expands.

## The memos (`eval/FormMemo`, since 2026-11-05)
Every per-site memo (built-in and user macro expansions, compiler macros, `load-time-value`,
lambda tails, `java:` sites) is a `FormMemo`: an identity table holding its form WEAKLY, so an
entry lives exactly as long as its form can be evaluated again. A read takes no lock (a writer
publishes with release semantics). Until then they were `IdentityHashMap`s under a monitor,
bounded at 20,000 entries (the guard against a program `eval`ing fresh forms forever), and past
the bound every site expanded at every evaluation. coalton's load reaches 558,000 built-in sites:
the bound made it expand `and` 97 million times (measured with counters, 2026-11-05), and a user
macro's per-site `load-time-value` would have run again each time.

What an entry stays valid for:
- A user macro site keeps the `UserMacro` that expanded it (`SiteExpansion.macro`): it holds
  while that macro is the name's, so a redefinition expands that macro's sites again and no
  other's. A macro that reads another macro or a global while expanding keeps its first answer,
  as before.
- A walk of a whole form (`expandedPerSite`: `flet`/`labels`, the slot macros) holds while the
  macro table is as it was: `macroTableEpoch` moves on a `defmacro`, a run-time `macrolet` and an
  `fmakunbound`, not on the bindings a walk makes for the extent of a `macrolet` body it expands
  (`pushLocalMacro`): what the walk answers already holds wherever it is reached. While such a walk
  is under way (`withLocalMacros`, `localMacroDepth`) a walk reached keeps nothing.
- Until 2026-11-05 every change of the table dropped the whole user memo. concrete-syntax-tree's
  `with-bounded-recursion` expands to a `macrolet` under an `flet`, so each of its sites' walks
  dropped every other's: 24,460 drops in coalton's first 17 minutes.

## Traps
- The memo keys on mutable `LispCons` identity: rewriting a macro FORM ITSELF between
  evaluations keeps the first expansion. A mutation reaching a subform's VALUE through
  shared structure is not frozen (`.kb/quoted-data.md`).
- **Nothing is held across the evaluation of an expansion, hit path as much as miss path.**
  An expansion can hand over to another thread (`am.ik.objc.MainThread.sync`, a blocking
  socket read) which then cannot take a lock; one held deadlocked `SceneOffscreenRenderTest`.
- An expansion holding its own form keeps its entry alive (a weak key with a strong value,
  as `WeakHashMap`): only the wrong-count report embeds its call. Racing threads both expand,
  last write wins.

## Tests
- `LispEvaluatorTest.aRewrittenBuiltinMacroFormKeepsItsFirstExpansion`,
  `LispEvaluatorTest.aMemoizedBuiltinMacroDoesNotHoldTheMemoWhileItsExpansionRuns`,
  `LispEvaluatorTest.userMacroExpandsOncePerCallSite` (a walk and another macro's definition
  leave a site alone; 25,000 sites of one function expand once), `FormMemoTest`
- `LispEvaluatorHotMethodSizeTest` — arms must not push any of the three operator-table
  methods (`evalCons`, `rareOperatorExpansion`, `evalConsRareOperator`) over the
  8000-bytecode cliff (`.kb/hot-path-method-size.md`); an arm's expansion REPLACES the form
  in `evalCons`'s loop rather than being evaluated in a frame of its own
  (`.kb/interpreter-tail-calls.md`)
