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
- `setf` — kept per site under a stamp of what it reads instead ("The setf memo" below); a
  `(setf (macro-function ...))` and a `(setf (find-class ...))` act as they expand, at each evaluation
- `print`, `princ`, `prin1`, `princ-to-string`, `prin1-to-string`, `write-to-string`,
  `%princ-piece`, `%prin1-piece` — `print-object` routing, live `*print-case*`
- `flet`, `labels` (`preExpandLocalMacros`), `symbol-macrolet`, `with-slots`, `with-accessors` — live
  user-macro table (they memoize in `userMacroExpansions` under the table's epoch: `expandedPerSite`)
- `read`, `floor`/`ceiling`/`round`/`truncate`, `reduce`, `sort` — partial (nullable) lowerings
- the multiple-value consumers (`multiple-value-bind`/`-list`/`-call`/`-setq`/`-prog1`,
  `nth-value`) and lambda tails, ONLY once the program methods a producer — the methoded set
  (`.kb/clos.md`, "Interpreter, the call side"); memoized as before while it is empty

Two per-EVALUATION costs of every call, not memos, found in profiling: a
package-qualified operator asked the two uiop probes through two `splitQualified` splits
(now `LispEvaluator.isUiopOperator`, a per-name answer), and
`normalizeBindingList` rebuilt every `let`'s binding list (now it returns the list
itself unless an entry is a bare symbol or `(x)`).

Place-writing macros (`push`, `pop`, `incf`, `decf`, `pushnew`, `remf`, `psetf`, `rotatef`,
`shiftf`) ARE memoized: they lower to `(setf place ...)`, whose own memo is below.

## The setf memo (`LispEvaluator.setfExpansion`, since 2026-11-06)
A `setf` site's expansion is kept on its head (`PlaceNote`) and in `placeExpansions`, under
`placeEpoch`: the sum of counters that only grow, so it is what it was only while none moved.
What the expansion reads, listed from `expandSetf`'s call graph and the evaluator's arm:
- the user macros a place may call -- `callSiteEpoch`;
- the setf expanders (`defsetf`, `define-setf-expander`) and the struct accessors and setf
  functions (`structAccessors`, `(defun (setf f) ...)` included): `CountedMap`s, counting writes;
- the class registry's classes, layouts, struct accessors' owners, slot-access protocol switch and
  generic names (a `slot-missing` generic) -- `ClosRegistry.placeVersion`, moved after each write;
- the prelude, uiop and usocket place loads: a site's first expansion runs them, and what they
  define moves the counters above.

Not inputs: a global symbol macro (a symbol place is a `setq`, which resolves it when it runs; a
`setq` through one makes its `setf` form once per value form, `assignSymbolMacro`), `*package*`.
Expanded at each evaluation: a `find-class` place (it registers the alias as it expands) and a
place whose macro expands per scope (`expandsPerScope`). A user setf expander runs once per site,
as a macro does -- the stated semantic change, as on the compile paths.

Measured (JFR of coalton's load up to `library/math/real.lisp`, 2026-11-06, after the compiled
bodies): the expansion was 7.3-7.6% of the samples (a slot place's three walks of every class or
layout 3.9%, the setf expanders' scan of every entry 1.2%), 0.07% with the memo. The window, four
pairs side by side (load 7-14): 126.2, 134.8, 138.0, 135.6 s against 126.0, 110.8, 117.3, 120.0.

`make-instance` stays expanded at each evaluation: 0.26% of the same samples (2.9% before
`initializationGeneric` stopped walking every generic), its `ensure*` loads 0.27%. Its expansion
reads the generics' methods (`make-instance`, `allocate-instance`, the initialization protocol),
the default initargs, the instance tracking and the protocol switches: a stamp would move with
every `defmethod`, a blast radius its share does not pay for.

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

## The call-site note (`LispSymbol.evaluatorNote`, since 2026-11-06)
What `evalCons` worked out about a form is kept on the form's HEAD SYMBOL object: one slot per
`LispSymbol`, opaque outside the interpreter. A fact about a name holds for every object of it, so
a symbol several forms share keeps one note for all.
- `CallNote`: an ordinary call -- no arm of the three operator tables (`NO_OPERATOR_ARM`), no user
  or compiler macro, no uiop operator -- with the name's cell in the global function namespace
  (`FunctionCell`, by index), the built-in's arity shape, and the noting form and its length. That
  form skips its length walk, the switch and the classification; any other form of the same head
  object skips the classification. A `FunctionCell`'s binding carries what a call reads of the
  function: the slot a `defstruct` reader's body `(%obj-ref p N failure)` reads (a call on an
  instance answers it without the body; anything else runs the body and its failure form) and
  the body's declared specials.
- `ExpansionNote`: a memoized macro call's expansion for its form, answered before the length
  walk: the built-in arms that do nothing per evaluation but expand (the ones that load a
  runtime or proclaim first pass `noted=false`) and user macros with no `&environment`.
- `LetNote`: a `let`'s normalized bindings and its body's declared specials, lexical until the
  next proclamation. `SpecialNote`: whether a variable's name is proclaimed special.
- Validity: the evaluator's token (`noteOwner`) -- a note names its cell by index, so a symbol
  of a form every evaluator runs (a parse cache's) pins no evaluator; `callSiteEpoch`, moved by
  every change of the user macros (a walk's local ones too), the compiler macros and the
  methoded built-ins and producers; the proclaimed specials' epoch (`CountedNames`).
- A head object many forms share (`ReaderSymbols`' backquote heads) keeps its first current
  note (`mayNote`) instead of each form overwriting the last.
- Why the symbol and not a side table: the per-form `FormMemo` probes miss the cache on a
  table of a million entries. A call-site memo keyed by the form cost 6% of coalton's load in
  its probes alone; the built-in expansion note took `library/math/real.lisp`'s arrival from
  302 to 237 s (pairs run side by side, 2026-11-06). The slot is free in the native image (a
  symbol stays 16 bytes) and 8 bytes on HotSpot's default layout; a cons field was rejected
  for every data cons (`LocatedCons`).
- `funcall`/`apply` of a generic function's value (`functionValue`) runs its current
  dispatcher in the frame, as a closure's value does (`closureCalled`).
- The body compiler reads all of these ([interpreter-compiled-bodies.md](interpreter-compiled-bodies.md)),
  and the built-in memo's entry says whether its arm is the expansion alone
  (`BuiltinExpansion.expandsOnly`): a note is per head object, so a form whose head another form
  noted first has only its memo to say what it expands to.

## Traps
- The memo keys on mutable `LispCons` identity: rewriting a macro FORM ITSELF between
  evaluations keeps the first expansion. A mutation reaching a subform's VALUE through
  shared structure is not frozen (`.kb/quoted-data.md`). A noted call keeps its length too.
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
- `LispEvaluatorTest.aCallSiteFollowsWhatItsOperatorBecomes` -- the notes against a function
  redefined, a function name made a macro, a compiler macro defined later, a generic's value
  after a new method, a `let`'s variable proclaimed special, a struct reader on a non-instance
  and redefined; a setf site, walked and compiled, against a place given a setf expander or made
  a macro, a struct and a class redefined, a `slot-missing` method, and its expander run once
- `LispEvaluatorHotMethodSizeTest` — arms must not push any of the three operator-table
  methods (`evalCons`, `rareOperatorExpansion`, `evalConsRareOperator`) over the
  8000-bytecode cliff (`.kb/hot-path-method-size.md`); an arm's expansion REPLACES the form
  in `evalCons`'s loop rather than being evaluated in a frame of its own
  (`.kb/interpreter-tail-calls.md`)
