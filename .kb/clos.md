# CLOS static subset — defclass / defgeneric / defmethod / make-instance / slot-value

User docs: `doc/en/reference/special-forms/{defclass,defgeneric,defmethod}.md`,
`doc/en/reference/macros/{make-instance,slot-value}.md`. Dispatch, standard method
combination and multiple inheritance are DONE.

Everything expands to plain defuns via `LispMacroExpander` (no backend codegen).
`ClosRegistry` (in `am.ik.rontolisp`) holds classes, generics and `slotPositions` (slot base
name -> 1-based position, `-1` when unrelated classes disagree — `slot-value` then errors
"use the accessor"). One per evaluator (`LispEvaluator.closRegistry`) and per compilation
(`Jvm/WasmLispCompiler.Ctx`, threaded via `Ctx.Builder` beside `structAccessors`).

## `expandDefclass` / `registerDefgeneric` / `expandDefmethod`
- `expandDefclass` registers a `ClassInfo`, generates the keyword constructor `%make-<name>`
  (`&key ((:initarg slot) initform)...` over the FULL slot list) and one synthesized
  `(defmethod R ((__obj C)) ...)` per `:reader`/`:accessor` — a METHOD, not a defun, since
  several classes may declare one reader name over DIFFERENT slot positions and a user
  `defmethod` must merge, not shadow. An `:accessor`'s write half is a `%setf-<name>` writer
  GENERIC (new value first) registered in `structAccessors` under `SETF_FUNCTION_MARKER`.
- An instance is `(%obj-new '%class-<name> v...)` (`.kb/instance-syntax.md`), NOT a list.
  Layout = superclass slots (inheritance order) + own slots, so single inheritance keeps
  indexes stable in descendants; accessor bodies are `(%obj-ref __obj i)` / `(%obj-set ...)`.
- A position-ambiguous `slot-value`/`with-slots` name falls back to the reader generic; its
  setf re-dispatches through the writer.
- `expandDefmethod` records a `MethodInfo` keyed by `qualifier + specializer`
  (same-qualifier-same-specializer redefinition replaces) as a defun `%<generic>--m<i>`.
  Qualifier `:before`/`:after`/`:around` precedes the lambda list (`""` = primary). EVERY
  method-body defun gains a leading `%next-method` thunk parameter; `rewriteNextMethod` turns
  `(call-next-method args...)` into a `funcall` of it and `(next-method-p)` into a null test,
  matched by package-stripped name (NOT in `CL_SYMBOLS`) and rewritten away before any
  backend sees them; `(apply #'call-next-method ...)` / `(funcall #'call-next-method ...)`
  are rewritten too, not only head position. `MethodInfo.usesNext` records the mention.
- `defgeneric` inline `(:method [qualifier] (params) body...)` clauses register like separate
  defmethods. Their keys are `GenericInfo.inlineMethodKeys`: evaluating the `defgeneric` again
  removes them first (CLHS defgeneric).

## Lambda-list congruence (CLHS 7.6.4)
`ClosRegistry.LambdaListShape` (required / optional counts, `&rest`, `&key`, keyword names,
`&allow-other-keys`) is what is compared; `incongruenceWith` answers SBCL's sentence or null.
`GenericInfo.lambdaListShape` is the `defgeneric`'s (keywords declared), else the first
method's (CLHS 7.6.5: `&key` without names), else `standardGenericShape` for the init trio and
`print-object`; `methodShapes` keeps each `defmethod`'s for the `defgeneric`-after check.
- **The refusal is the EXPANSION**: `expandDefmethod` registers nothing and answers
  `programErrorForm` (`DEFMETHOD G: the method has fewer optional arguments than the generic
  function`), so it is a `program-error` where the form runs on all four backends, with the
  static-program-error compile warning; a later call dispatches over what was added.
  `registerDefgeneric` appends the same signal to `methodDefuns` (`DEFGENERIC G: the lambda
  list is incompatible with an existing method: ...`), judging counts and `&rest`/`&key` only
  (SBCL accepts `(defgeneric g (a &key x))` over a method `(a &key y)`).
- Checked against the method's lambda list AS WRITTEN, before the lite `&allow-other-keys`
  append and the `%method-args` injection.
- The macro-time evaluator (`UserMacroExpander.replayDefinition`) swallows that program-error
  from a defmethod/defgeneric replay: the compiled program signals it at the same form.
- A `defmethod` under a top-level `let` (even inside a `lambda`/`handler-case` there) is
  expanded in place on the compile paths, so a handler sees the refusal everywhere -- what the
  pins use.
- **Compile-path residual**: a second `defgeneric` changing the required count (legal once its
  old methods are gone, the interpreter redefines in place) is a compile-time
  `UnsupportedOperationException` -- one dispatcher per generic.
- Deviation: SBCL signals a plain `error` (`find-method-length-mismatch`, or a `simple-error`
  for `defgeneric`) when the required count differs from a generic that HAS methods, a
  `program-error` otherwise; rontolisp signals `program-error` throughout. Measured
  2026-10-06 on SBCL 2.2.9 over 30 `defmethod` and 8 `defgeneric` shapes: which definitions
  are refused and which added agree on every one, all four backends byte-identical.
- Before (2026-10-06): only a required-count mismatch was refused (interpreter: a
  program-error; compile paths: the COMPILE failed); `(defgeneric g (a b &optional c))` +
  `(defmethod g ((a integer) b))` was accepted everywhere and `(g 1 2 3)` failed "Function
  expects 3 arguments, got 4" on the interpreter and JVM and silently dropped the argument on
  both WASM backends. A redefinition `(defgeneric g (a))` -> `(defgeneric g (a b))` was refused
  even with no methods.
- Blast radius measured 2026-10-06 by a static scan (every `defgeneric`/`defmethod` form, any
  depth, grouped per system directory, against gray.lisp's and the standard generics' lambda
  lists) of `src/main/resources`, `src/test/resources`, `examples/`, `ci-spec.yaml`, the doc
  fences, the Java test programs and the local Quicklisp cache (1356 files): no congruence
  violation outside the pins (the hits were same-named generics in different packages and
  usocket's iolib-only backend).

## A forward-referenced superclass
`(defclass a (b) ...)` before `b` exists (ANSI allows it; cxml's `xmlns-normalizer` before `sax-proxy`).
Both paths run the same `expandDefclass`; `ClosRegistry.ForwardClass` is what waits.
- **The name becomes a class that waits**: an unregistered superclass name is expanded as
  `(defclass b () ())` with `named` set and no form (the name was only written), so `find-class`,
  a `defmethod` on `b`, `subtypep 'a 'b` and `typep` have a class to read. `a` then waits too, and
  so does a subclass of a waiting class. A later `(defclass b ...)` replaces the record.
- **What a waiting class emits** (`waitingClassForms`): `(setq %CLASS-UNFINALIZED-a% t)`, its
  class-allocated cells (CLHS: once, where the class is defined) and a constructor that refuses. No
  accessors, no metaclass driver: an accessor bakes an index the layout does not have yet, and a
  provisional shifted-slot method could survive into the final layout with a wrong one. Its layout
  takes no `registerSlotPosition`, which would mark the name position-ambiguous.
- **Finalizing** (`ClosRegistry.nextFinalizableClass`, oldest first, once nothing it waits for is
  left) expands the stored form again: the redefinition path, so the constructor replaces the
  refusing one (`addExpandedClass` drops the earlier defun; a `define-condition` goes through it
  too, since it may be the last superclass). `finalizedClassForms` puts `(setq flag nil)` first, as
  the metaclass driver allocates, and drops the cells and every generated method a primary method
  defined since has the key of (`noteMethodOnWaitingClass`; SBCL: the reader came first, and
  `:USER-RU` wins). The interpreter finalizes from `evalDefclass`, in the environment the waiting
  class was defined in (`waitingClassEnvs`); the compile walk from `addExpandedClass`.
- **The refusal is at the allocation seam** (`tracked`, so the constructor, the chain fill and
  `%ALLOC-INST`): only a class that ever waited tests its flag, so every other program is
  byte-identical. The interpreter's native `allocate-instance` asks `isUnfinalized`. The compile
  paths hoist one `defvar` per such class, as a class has its last layout from the start and only
  the flag keeps the refusal in program order. `unfinalizedClassMessage` differs from SBCL's text; the
  ci-spec pins `:refused`. An `&key` default runs before the refusal.
- **Refused at the form**: its own superclass (`self`), a cycle through a waiting class (the check
  reads the `ancestors` a placeholder gives), a `defstruct` or built-in type name (SBCL's
  `INVALID-SUPERCLASS`; `findStructTag`/`isSupportedTypeSpecializer`). As for an inconsistent
  precedence, on the compile paths that fails the compile. `define-condition` keeps refusing an
  unknown first parent (SBCL: `Class not yet defined`): `defineConditionToDefclass` checks it.
- **Never defined**: the compile walk marks the waiting class's flag `(%compile-warning ...)`
  located at its defclass (`warnAboutNeverDefinedSuperclasses`), so a typo'd superclass, once a
  failed compile, is a counted warning and a class that refuses instances.
- Residuals, all of them in the window before the last superclass: `find-class` of `b` answers a
  `standard-class`, not `forward-referenced-class`; its metaobject's `finalized-p` slot says `t`
  and SBCL's `class-precedence-list` error is an answer; a reader is not yet `fboundp` on the interpreter; a
  `find-class` object taken in the window is not `eq` to the finalized class (a redefinition makes
  a new one); a class finalized before and then redefined over a forward reference leaves its
  subclasses alone (redefinition does not propagate); a `:metaclass` class that waited is not
  widened to what its protocol injects (`widenedMetaclassDefclassOrNull`); an `(setf find-class)`
  alias does not stand in for the name.
- Pinned by ci-spec `defclass-of-a-forward-referenced-superclass` (agrees with SBCL 2.2.9),
  `LispEvaluatorTest.aForwardReferenceCannotCloseACycleOrNameANonClass` and the warning in
  `RontoLispCliStreamsTest.aReferenceToAnUndefinedFunctionWarnsAtTheReferenceOnEveryBackend`.

## `generateDispatcher(name, registry[, builtinFallback])`
ONE dispatcher defun per generic: a nested-if chain, most specific first. An ordinary defun,
so `#'name`/`funcall`/`mapcar` work with no `BuiltinFunctionWrappers` entry.
- An OPTIMIZING compile passes a `DispatchNarrower` (`compiler/GenericDispatchNarrowing`)
  omitting branches no call site can select (`.kb/optimize-dead-code-elimination.md`); every
  other caller passes null and the dispatcher is byte-identical.
- Specializers may sit on ANY required parameter. `specializerRank`, compared leftmost-first:
  eql 0, classes 10..99 by descending ancestor-set size, built-in types 200s with subtypes
  (`null`/`package`/`keyword`/`integer`) before `symbol`/`number`/`list`, default 1000; a
  stable sort keeps definition order within a rank. **`package` ranks 205, ahead of `keyword`
  (210) and `symbol` (220)**, because a package IS a keyword in this value model
  (`.kb/symbol-runtime-api.md`); misordered, rove's `find-suite` recurses forever.
  `simple-bit-vector` (226) and `bit-vector` (228) rank ahead of `vector` (230), `array` (235) and
  `sequence` (240) -- cl-slice's `canonical-representation` pairs a `bit-vector` method with a
  `vector` one (ci-spec `clos-defmethod-bit-vector-specializer`).
- **The TYPE specializers `defmethod` accepts (`isSupportedTypeSpecializer`) and the types
  `makeTypeTest` builds are ONE definition, not two lists that drift.**
  `makeEqlSpecializerTest`: symbols/keywords compare with `equal` (content-safe on WASM),
  numbers/characters with `eql`. A class test is `(%obj-is x '%class-C ...)` over the
  statically-known descendant tags.
- A generic whose lambda list continues past the required params gets a VARIADIC dispatcher
  forwarding the tail via `apply` — the compile backends' ALIGNED fast path
  (`Jvm/WasmApplyCompiler`, ~80-300 B saved each) when a literal `#'m` target's required
  parameters are all covered by the leading arguments.
- The last resort is ONE call of the shared `%no-applicable-method` defun, the per-generic
  message riding as a literal prefix argument; re-inlining the error tail cost over a KB EACH
  across a library's synthesized accessors. `expandTopLevelDefinitions` appends it once (a
  `referencesFunction` scan just before the format-renderer scan, which must see its error
  form); the interpreter defines it before its first dispatcher
  (`LispEvaluator.defineDispatcher`). The dispatcher AST is therefore ONE shape everywhere,
  which `ShadowedBuiltins`' structural dead-dispatcher comparison relies on.
- An `(eql form)` specializer's form is EVALUATED at method definition (CLHS 7.6.2); the only
  such form a static walk can evaluate is a constant name, so `ClosRegistry` carries a
  `defconstant` name -> literal value table (`registerConstant`/`findConstant`) filled in
  DEFINITION ORDER. `parseEqlSpecializerValue` consults it for a BARE symbol only; `(eql 'x)`
  is the symbol. A bare symbol naming no constant stood for itself until 2026-11-05; it is a variable
  now, an evaluated form like the ones below (McCLIM's `(eql *builtin-t-class*)`). A keyword stays literal.
  Census 2026-11-05 of the cached Quicklisp systems: every bare name in such a specializer is a
  variable or a constant (SBCL signals `unbound-variable` for any other).
  `(eql (find-class 'c))` (and `(eql #<class>)`) stays the `(find-class 'c)` form and
  `makeEqlSpecializerTest` emits `(eq arg (find-class 'c))` -- find-class is memoized, so
  re-evaluating it per call compares against the definition-time object. `c` must resolve at
  definition time; the reader's `t` (not a symbol) names the class T (contextl's base layer).
- **Any other `(eql form)` is evaluated ONCE, where the method is defined** (2026-10-31,
  contextl's `(eql (lf-definer-name '...))`; a string too): `parseEqlSpecializerValue` answers an
  `%EQL-SPECIALIZER-FORM` placeholder, `expandDefmethod` binds it to a per-generic CELL
  (`GenericInfo.eqlSpecializerCell`, `%<generic>--eqlN`, one per form TEXT so a same-form
  redefinition keeps its key) and puts `(setq cell form)` ahead of the method defun
  (`withEqlCellAssignments`); the specializer value is `(the t cell)` and the test `(eql arg
  (the t cell))`. The compile paths prepend `(defvar cell (list nil))` per cell after the
  dispatchers are placed, so a dispatcher running before the defmethod form sees a cons no
  argument is `eql` to -- the interpreter's answer (the method does not exist yet). Before, such a
  form was an `UnsupportedOperationException` at expansion. ci-spec
  `clos-defmethod-eql-specializer-over-a-constant`.
- **Metaclass specializers**: `parseSpecializer` seeds the MOP classes for `class` /
  `standard-class` / `built-in-class` (`ensureMopClassesSeededFor`, like a type specifier) so they
  become CLASS specializers: `class` matches every class metaobject, the other two theirs;
  `structure-class` is a TYPE specializer with the empty test. A class
  OBJECT in specializer position (serapeum's `#.(class-of ...)`) is read as its slot-0 name
  (`classMetaobjectName`; the literal comes from the macro-time evaluator, so the tag is
  checked by name, not only via this registry). ci-spec: `clos-slot-options-and-metaobject-types`.

### The two dispatch bodies
- `simpleDispatchBody` (one call per branch) when the generic has NO qualifier and NO
  `call-next-method` usage; otherwise `combinedDispatchBody` = one branch per distinct
  specializer (`specKeyOf`, qualifier-independent) plus the default fallback, each branch's
  value `effectiveMethod(branchRep, ...)`.
- `applicableMethods` filters by `appliesToBranch`: default methods always apply; a class
  method applies to a class branch whose class has it as an ancestor; a STRUCT method applies
  to a branch struct that `:include`-descends from it (`descendantStructTags`); a built-in type
  method to a branch of a type below it in `SUBTYPEP_PARENTS` (2026-11-05: until then `(call-next-method)`
  from `integer` to `number` had no next method and a `:before` on `number` did not run; `REAL`
  ranks ahead of `NUMBER` for it); `standard-object` to a class branch, `structure-object` to a struct
  branch; an eql method to its exact-same branch.
- **Plus one branch per position-wise MEET of two INCOMPARABLE specializer vectors**
  (`addMeetBranches`/`specializerMeet`) — one branch per method suffices only while the
  vectors form a chain, because `appliesToBranch` is a SUBSET test. Quadratic but run to
  fixpoint only over compatible AND incomparable pairs, so a generic with none is
  byte-identical. **Trigger**: the JVM 64 KB method ceiling
  (`.kb/jvm-method-size-limits.md`) if a library ever has many incomparable methods.
- Composition: `:around` (most specific first) wrap a `coreThunk`; the core runs `:before`
  (msf, for effect), the primary chain (msf, EVERY value kept by a `multiple-value-prog1` --
  a `let` until 2026-10-31 dropped the secondary values on all four backends, contextl's
  `(values context cacheablep)` under an `:after`), then
  `:after` (LEAST specific first). `buildNextChain` builds nested
  `(lambda (params) (%m next params))` literals — NO free-variable capture; base next = `nil`
  for the innermost primary, the `coreThunk` for the innermost around.

### The interpreter's chain memo (`DispatchChain`, since 2026-11-05)
The interpreter walks a dispatcher's chain the way it walks any `if`: one test per branch until
one holds. fset's `compare` has some 350 branches and a symbol pair's comes late, and coalton's
typechecker compares symbols in every `fset:image` of its environment: measured 2026-11-05, the
walk was 74% of `library/math/num.lisp`'s time (11 minutes; 2 with the memo).
- `LispEvaluator.defineDispatcher` hands the generated defun to `DispatchChain.memoizing`, which
  makes the chain's first `if` a `DispatchChain` (a `LispCons` subclass, like `LocatedCons`: car
  and cdr unchanged, so every walker still sees the chain; a pass that rebuilds the form gets a
  plain cons and only loses the speed). The compile paths never see one.
- `evalCons`' `if` arm takes it by a type test: the arguments' SHAPES (an instance's layout, a
  keyword or another symbol, else the value's Java class) key a table of `Selection`s, and a miss
  works one out by evaluating, once, every test the shapes decide (`SHAPE_TESTS` over a parameter,
  under `and`/`or`/`not`/`if`). What no shape decides -- an `eql` specializer's test, a nested
  method's guard, `package`'s `find-package`, an array's rank -- stays a test for every call, kept
  in chain order, and only for the shapes it is still open for. Tests have no effect, so dropping
  one that cannot hold is unobservable.
- Under the generic-function protocol or with classes made at run time the chain stands inside the
  dynamic arm's `let`s, whose test is one more open one. coalton's `derive` asks
  `compute-applicable-methods`, which turns the protocol on and regenerates every dispatcher: before
  the memo reached under the arm, an integer `compare` went from 5 to 200 us (measured on fset).
- A dispatcher generated again is a new chain with an empty table, so nothing is invalidated.
- Pinned by `DispatchChainTest` and `LispEvaluatorTest.aGenericAnswersEachCallByItsArguments`.

## Method definitions take effect in program order
`macro/MethodGenerations`, since 2026-11-05. A dispatcher is generated after the whole walk, so
until then a compiled program answered every call with its LAST method table: a call running
before a `defmethod` form saw that method, and a redefined method's first body never ran
(measured on JVM, P1 and the component; the interpreter and SBCL 2.2.9 agree with each other).
The interpreter needs nothing, it regenerates at each form.
- **A generation** (`ClosRegistry.MethodGeneration`) is an earlier table a call can observe. A flag
  is a hoisted `(defvar %g--genN nil)` plus a `(setq %g--genN t)` right ahead of the defining form,
  so that form's value stays the top level's. The flags are set in program order: the state is how
  many are set, and "state k or later" is one read of flag k-1.
- **One chain over every table** (`macro/DispatchBranches`, 2026-11-06): each table's chain is built
  as without generations (`dispatchChain` over the generic's view of the table, so combination,
  `call-next-method` and the narrower need nothing per generation), then merged: each distinct
  branch (specializer key and test) once, in an order every table's keeps, guarded by the states
  whose table has it, its value switched by state only where the tables' effective methods differ;
  the base likewise. The dynamic arm is built once, each test and method entry guarded by its
  states. A table that combines makes every table's chain the combined one. A call after the last
  form reads a flag on the branch it takes. Before, the dispatcher was one whole `dispatchBody` per
  generation, newest first: fset's `compare` had 40 generations of up to 426 methods (its
  `define-cross-type-compare-methods` interleaves running forms), a 5.5 MB defun (915,783 nodes,
  9,972 method references) past the JVM's method limit after outlining; now 47,262 nodes, and the
  program over it 14.9 MB -> 5.6 MB on wasm (measured 2026-11-06). Its JVM compile then needed the
  outliner's binding-value and `list` cuts for the protocol's arm and `%gf-static-methods`
  (`.kb/hot-path-method-size.md`). Residual: two tables ordering two branches apart (a key a second
  `defgeneric` removed and added back moves to the end of its table) get the last table's order,
  which can matter only between branches of one rank.
- **`compute-applicable-methods` answers the table of its time**: `staticMethodsForm` lists each
  method once, guarded by its states (before: the last table, so a call ahead of a `defmethod`
  listed that method, where the interpreter and SBCL do not).
- **The log**: the walk tells the registry its output index (`definitionPosition`) and every table
  write records a `MethodChange`, a removal by a second `defgeneric` included
  (`ClosRegistry.changeMethod`). `methodsBefore(p)` replays it. A method under a top-level `let` is
  an ordinary change at the let's position, and its assignment guard covers the let's own body. A
  redefinition already mints a new `%g--mN`, so the old body stays callable.
- **A system default is no definition** (`putSystemDefault`): it is in every table, wherever the
  form that synthesized it stands. A program's own default primary on a system generic with no
  synthesized one stays visible from the start (residual: no generation is made before it).
- **Which changes split** (`generationsOf`), so that every other generic stays byte-identical: a
  running form (anything but `defun`/`defmacro`) must be able to reach the generic since the last
  split, and the method must be able to apply to that call. A method specializing on a type whose
  first definition comes after the last such form cannot (`cannotApplyBefore`,
  `ClosRegistry.typeFirstDefined`), which keeps the usual class-then-methods-then-use shape in one
  table however many `print-object` methods a program has.
- **The reach is by mention**, on package-less names: forward through `defun` bodies and from a
  generic to its methods' bodies; `IMPLICIT_CALLS` for operators lowered later (`make-instance` to
  the init generics, the printers to `print-object`, slot reads to `slot-unbound`/`slot-missing`); a
  class name to its constructor (its initforms); a place to its `%setf-` writer. A form reaching
  `funcall`/`apply`/`mapcar`/... counts for every generic an earlier form mentioned, which may have
  been captured. Wider than the truth costs a generation and nothing else.
- **Before anything made the generic exist** (no method, no `defgeneric`), a generation signals
  `undefined-function`, as the interpreter does, unless a built-in it shadows answers.
- Residuals, each keeping the last table as every call had before: a call by a name computed at
  run time that no form mentions, an operator calling a generic not in `IMPLICIT_CALLS` (a Gray
  generic behind `write-char`, a `reader` behind a position-ambiguous `slot-value`), an instance
  made before its class's `defclass` form (an error on the interpreter), the literal `fboundp` of a
  generic. A plain `defun` keeps its own whole-program rule (`.kb/core-representation.md`).
- Size, measured 2026-11-05 on the ci-spec corpus as one program: 49 generics of its method-heavy
  cases split, `print-object` with 3 generations over 19 methods. The one chain took that program
  from 22.36 to 22.20 MB of classes and from 15.02 to 14.98 MB of wasm (2026-11-06).
- Pinned by ci-spec `clos-methods-take-effect-where-their-forms-run` (agrees with SBCL 2.2.9),
  standalone `generic-function-protocol` (`gfp-out-cmp`) and
  `LispMacroExpanderTest.aGenericKeepsOneMethodTableUnlessAnEarlierFormCanCallIt` (each method in
  the dispatcher once).

## Multiple inheritance
`ClassInfo` carries `superclasses` (local precedence order), `cpl` (CLHS 4.3.5 topological
sort in `computeCpl`; inconsistent local orders are an `IllegalArgumentException`) and
`directSlots`, beside the effective `slots` and the ancestor SET.
- **Layout rule**: the FIRST superclass's effective slots keep their indexes (the first
  descending from `class` for a metaclass, the metaobject index contract), each other
  superclass appends its not-yet-present slot names, a diamond keeps ONE copy. Inherited slot
  OPTIONS are re-merged from the direct specs along the CPL (`cplMergedSlot`/`shadowSlotSpec`).
- **Shifted accessors**: a non-first superclass's accessors bake THEIR index while their class
  test covers the subclass, so `expandDefclass` synthesizes overriding reader/accessor/writer
  methods specialized on the SUBCLASS for every inherited slot whose index differs in any CPL
  ancestor. Single inheritance never shifts.
- **Dispatch refinement** (`miRefinement`, gated on `hasMultipleInheritance()`): a class whose
  applicable class specializers have NO single most-specific member gets an EXACT-TAG branch;
  the combined body computes the effective method against it
  (`branchSpecificityOrder`). **LITE residual**: refinement handles ONE parameter position, so
  a diamond-affected class meeting a generic that class-dispatches on several positions can
  miss the second super's methods.
- `conditionReportGroups` inherits `:report` along the CPL; `define-condition` with several
  REGISTERED parents does real MI (an UNREGISTERED extra parent falls back to
  `registerExtraAncestors`); `change-class` remaps slots by name, so a shifted MI layout is
  fine; `%class-meta-table%`'s superclass column is a LIST;
  mop-protocol.lisp's `finalize-inheritance` merges inherited effective slots across ALL
  direct supers (first name wins).

## Where each path hooks in
- **Interpreter**: `evalDefclass` (expands + evals defuns, then REGENERATES the dispatchers the
  class changes: every one with a class-specialized method on a redefinition, for a class never
  defined before only those `GenericInfo.dispatchesOverNewClass` reaches, a class specializer among
  its ancestors or a `standard-object` one, whose test enumerates instances;
  measured on `(ql:quickload :clim)`, over 10 minutes before and under 3 after, 2026-11-05. A
  `cons`/`list`/`sequence` test reads no class (an instance is no cons), yet regenerated too until
  2026-11-06: coalton's load up to `library/math/real.lisp` rebuilt 8512 dispatchers unchanged,
  fset's `compare` (426 methods) 126 times, 15 s of 221),
  `evalDefstruct` (likewise: every one with a struct method on a redefinition, for a new struct
  those `GenericInfo.dispatchesOverNewStruct` reaches, a struct specializer among its ancestors or
  `structure-object`. Measured 2026-11-05 loading coalton's compiler: dispatchers took 64 s of its
  first 240, fset's `compare` regenerated 823 times for its 350 methods),
  `evalDefgeneric`, `evalDefmethod`. A `#'generic` captured BEFORE a later defmethod is NOT
  stale: `evalFunction` answers a LATE-BOUND wrapper for any name `findGeneric` knows. A
  `funcall`/`apply` of that wrapper runs the current dispatcher in `evalCons`'s frame
  (`closureCalled`, since 2026-11-06), not through the wrapper and `apply`'s frame: fset's
  maps call their `#'compare` that way on every key a tree passes.
- **Compilers**: `expandTopLevelDefinitions(program, structAccessors, closRegistry)` sits at
  the old `expandTopLevelDefstructs` slot (after `flattenTopLevel`, before
  `LambdaLists.desugarProgram` — the constructors use `&key`). It walks the whole program
  collecting classes/methods, splices defuns in place, and inserts each generic's dispatcher
  at its defgeneric's position (or the first defmethod's) AFTER the walk, so descendant and
  method sets are complete regardless of definition order (a call that runs earlier still sees
  the earlier table: "Method definitions take effect in program order"). Non-top-level
  defclass/defgeneric/defmethod -> "only supported as a top-level form", with ONE
  exception: a `defmethod` or `defgeneric` anywhere in a top-level `let`/`let*` body (the
  closure-over-let idiom) is registered statically by
  `expandLetNestedDefmethods`/`rewriteNestedDefmethods` while its body defun stays in the
  let (a global-closure `setq`). A nested `defgeneric` (`expandNestedDefgeneric`) leaves
  its `(:method ...)` clauses' defuns plus `'name` in place; the dispatcher goes after the
  let. cl-qrencode's `rs-ecc.lisp` needs it (2026-10-31: `encode-symbol` agrees on all
  four). A top-level `flet`/`labels` is the same shape (body at index 2) and takes the same
  path since 2026-11-06: eclector's `messages-english.lisp` puts two `report-condition` methods
  under `(flet ((package-name* ...)) ...)`, which failed every compile of eclector, and so of
  coalton. ci-spec `generics-defined-in-a-top-level-let-close-over-it`.
  `defclass`/`define-condition`/`defstruct` in a let stay refused: their initforms
  are inlined into top-level constructors (the class's and every subclass's) and the
  metaobject table, so they cannot close over the let, and hoisting a capture-free one
  would have to happen before the many earlier top-level scans that look for definers. No
  program needing it is known.
- **`defclass` answers the class object** (CLHS; the name until 2026-10-31): the interpreter's
  `evalDefclass` returns `classMetaobject`, `find-class`'s object. `define-condition`, evaluated
  through it, still answers the NAME (CLHS): the same day's switch had made it the object too,
  which `DocExamplesTest` caught on `define-condition.md`. On the compile paths a
  top-level `(defparameter|defvar NAME (defclass C ...))` -- lisp-binary's
  `struct-like-defclass`, which hands the value to moptilities' `slot-names` at macro-expansion
  time -- is two top-level forms, the defclass and `(NAME (find-class 'C))`:
  `LispMacroExpander.flattenTopLevel` splits it (`classDefinerInitializer`; a `define-condition`
  initializer splits the same way into `(NAME 'C)`, since 2026-10-31), so the CLI's flatten,
  the tree-shaker's and each backend's all see the defclass at top level. The macro-time
  evaluator runs the unsplit form (an interpreter, so it gets the object). Unconditional, unlike
  a `defvar` whose variable is bound -- the class table is whole-program on these paths anyway.
  Pin: ci-spec `defclass-answers-the-class-object` (both definers, all four backends).
- **A nested method's body exists only once its defmethod form runs, so every
  dispatcher branch calling one is guarded on the assignment** (`(and (not (null
  %<generic>--m<i>)) <specializer-test>)`, todo 445; `ClosRegistry`
  `nestedMethodFunctions`, marked only by the two nested expanders — the interpreter
  never marks, since it registers the method exactly when the form runs). Until the
  form runs the branch is skipped and the call falls through to the default or to
  `no-applicable-method`, which is what the interpreter answers for the same program.
  The guard is a plain variable read (an unassigned global is Java null on the JVM,
  a null reference on WASM), so it costs no eval runtime and no designator probe. A
  SYNTHESIZED system default (`print-object`, the init protocol) is position-free and
  is hoisted to top level instead of guarded, so the fall-through itself never reads
  an unassigned global. Combined-method branches are guarded conservatively: the
  whole branch is skipped when ANY nested participant is unassigned, even when the
  remaining ones could still run (residual).
- **`make-instance`/`slot-value`** are `CL_MACROS`, expanding at the three dispatch sites
  (`evalCons` + both ExprCompilers); a literal quoted name gets the static expansion.
  `expandSetf` has a `closRegistry` parameter with a SLOT_VALUE place case. `#'make-instance`
  as a VALUE, and a DIRECT call with a computed class, both lower to `%mop-make-instance` and
  flip the SAME gate (`referencesMakeInstanceValue`). A runtime slot NAME dispatches through
  `%slot-value-runtime`/`%slot-boundp-runtime`/`%slot-value-set-runtime` (the setf twin
  separately gated by `needsRuntimeSlotSetDispatch`).
- **`UserMacroExpander`**: top-level defclass/defgeneric/defmethod are `macroEval.eval`'d into
  the macro-time evaluator (so a defmacro body can CALL a generic at expansion time) AND kept
  for the compilers. Walker cases keep defmethod lambda lists and defclass names/options
  verbatim; only defmethod bodies and defclass `:initform` values are walked.

## Name resolution gotcha
`defclass`/specializer class names are package-resolved (canonical, `zoo::dog`); the quoted
name in `(make-instance 'dog)` is NOT. `ClosRegistry.findClass` falls back from the exact
normalized key to a UNIQUE base-name match across packages; two packages defining the same
class name make the bare spelling unresolvable (qualify it). `slot-value` matches by slot base
name likewise. `defmethod` stores the specializer as the FOUND class's canonical name.
**A type of exactly the spelled name beats every tolerance, in both directions**: `lookupClass`
answers null for a name a `defstruct` holds exactly (before, `a2::sa` found `a1:sa`'s class, the two
methods of one specializer replaced each other and a struct instance had no applicable method;
cxml's `sax::standard-attribute` and `hax:standard-attribute`), and `structKey` -- behind
`findStructTag`, `descendantStructTags`, `structAncestorCount`, `structAncestorNames` -- takes the
qualified spelling's member only when no class holds the exact spelling. `subtypep`'s same-member
shortcut (`SubtypepName.namesAnotherTypeThan`) reads the same: two registered types of one member
are unrelated, a two-class or two-struct pair included. ci-spec
`defclass-and-defstruct-of-one-name-in-two-packages`, `ClosRegistryTest`.
**A built-in class's name is cl's** (2026-11-06): no tolerance hands a bare or `cl:` built-in class
name (`BUILTIN_CLASS_NAMES`, `FIND_CLASS_ONLY_CLASS_NAMES`) another package's class of the member
(`ClosRegistry.namesBuiltinClass`), and `%class-meta-table%` lists no bare member for such a class.
Coalton defines `Pathname`, `Vector` and `Symbol` classes in its own packages; `(find-class
'pathname)` answered coalton's on all four, and the compiled `class-of` of a pathname too. A type
test of a qualified name a class holds exactly is that class's (`makeTypeTest`), where the member's
standard type had answered: `(typep (make-instance 'p::pathname) 'p::pathname)` was NIL. ci-spec
`make-load-form-literal-object`.
A miss consults `classMissLoader` before answering null, then repeats the lookup once: the
interpreter installs one loading gray.lisp for a Gray base-class member name
(`.kb/gray-streams.md`, "Interpreter dispatch"); the compile paths install none. A probe
asking "is library X loaded" must therefore not be a `findClass` of X's class name.

## Setf methods and class-name aliases
- `(defmethod (setf name) ...)` / `(defgeneric (setf name) ...)`: `normalizeSetfMethodForm`
  rewrites onto the `%setf-` writer-generic convention (`setfFunctionName`), the place joining
  `structAccessors` under `SETF_FUNCTION_MARKER`, so `expandSetf` is unchanged. A user setf
  method MERGES with accessor-generated writer methods or replaces the same-specializer one.
  **Ordering constraint**: normalization sites are `expandTopLevelDefinitions`'s defmethod AND
  defgeneric branches, `expandLetNestedDefmethods`/`rewriteNestedDefmethods`, and the
  interpreter's `evalDefmethod`/`evalDefgeneric` — all BEFORE anything casts the name position
  to `LispSymbol`. Qualified places produce `%setf-PKG::NAME`.
- `(setf (find-class 'alias) (find-class 'target))`: **an alias is a second NAME for one
  class, never a second class.** `ClosRegistry.classAliases` maps alias -> the target's
  CANONICAL name (one level deep); `findClass` consults it right after the exact-name lookup,
  ahead of the package-tolerant fallbacks, so metaobject, tag, layout, ancestor set and
  `%obj-ref` indexes stay the TARGET's. Registered at EXPANSION time
  (`expandSetfFindClass`; the compile path's `isSetfFindClassForm` branch runs inside
  `expandTopLevelDefinitions`, BEFORE the class tables are built) and becomes an extra
  SPELLING of `%class-meta-table%`, the runtime `typep` tag table and the `subtypep` universe.
  Any other shape is the RUN-TIME `(setf find-class)` (2026-11-01, all four backends; a
  run-time `error` until then): `(%set-find-class name value)` (`LispNames.SET_FIND_CLASS`)
  changes what the PUBLIC `find-class` answers and nothing else -- `nil` forgets the name
  (contextl's `layer-makunbound`), a class metaobject answers for it, and a later `defclass` of
  the name ends either. The class, its instances, `class-of` and every internal resolution keep
  the static tables (`%find-class`; a forgotten name stays a static class, so `make-instance` of
  a literal name still constructs -- SBCL signals). Interpreter: `ClosRegistry.findClassOverrides`,
  cleared by `evalDefclass`; compile paths (`usesRuntimeSetfFindClass`): a
  `%find-class-overrides%` alist the public `find-class` reads first, and a
  `(%find-class-defined 'name)` after each top-level defclass. Forgetting a literal name no
  class has when the form expands (a top-level form in program order on the compile paths, a
  nested one against the whole program; the live registry on the interpreter) stays the static
  `nil` (contextl's spx opens with one). ci-spec `find-class-metaobject-substrate`; an unknown target
  throws `there is no class named`; a NON-top-level alias is an error (`markClassMetaTableEmitted` makes `registerClassAlias`
  refuse); `--no-gc` shares one never-mutated `EMPTY_CLOS_REGISTRY` and rejects the place
  outright. Macro-namespace twin: `(setf (macro-function ...))`, `.kb/defmacro-backquote.md`.

## A user method on a BUILT-IN name
**A built-in whose name a program defines a method on becomes that generic's DEFAULT
METHOD.** Otherwise the dispatcher defun SHADOWS the built-in and every non-instance argument
dies with "No applicable method: CLOSE on INTEGER".
- `generateDispatcher(name, registry, builtinFallback)` is the overload; the 2-arg one passes
  null and emits the byte-identical old shape. The fallback replaces `noApplicableMethod` in
  BOTH bodies, supplies `buildCore`'s primary when a branch has only `:before`/`:after`, and
  closes the primary chain as `buildNextChain`'s base; a user DEFAULT method still wins.
  `fallbackCall` takes no `%next-method` thunk and, for a variadic generic, spells
  `(apply #'<stash> params... %gf-rest)` — that tail carries `close`'s `&key abort`.
- **Interpreter**: `defineDispatcher` is the ONE installation seam. `builtinDefaultMethodFor`
  stashes the built-in under `builtinDefaultMethodName` (`%<generic>--builtin`) and MEMOIZES
  the hit in `builtinDefaultMethods`. **The memo is load-bearing**: the dispatcher is
  regenerated on every `defmethod`, and the second pass would find no built-in to stash and
  silently drop the default method. A Java-backed built-in is a `LispFunction`; a
  user/prelude `defun` is a `LispLambda`, deliberately left to be shadowed — that type test is
  also why a MISS needs no memo.
- **Interpreter, the call side** (2026-09-26): stashing is not dispatching. `evalCons`' operator
  tables EXPAND many built-ins before the global binding is read (`(byte-size x)` -> `(car x)`),
  and the multiple-value lowerings spell a producer's values BY NAME (`(floor x)` in a tail or
  under a consumer -> quotient + `(- x q)`), so 45 of the 249 shadowable names ignored the user
  method (byte family, `first`..`tenth`, `1+`, `zerop`, the floor family, `find-symbol`,
  `read-from-string`, `values`, ...). Now a stash of a `ShadowedBuiltins.loweredBuiltinFunctions()`
  name adds it to `methodedExpandedBuiltins`, and `evalCons` sends such a call straight to the
  binding ahead of every table (one identity test per call while the set is the initial empty
  one). A methoded syntactic
  producer (`LispMacroExpander.isSyntacticMultipleValueProducerName`) also gets its dispatcher
  bound under `%<name>--dispatch`, and the defun body, lambda tail, `handler-case` protected
  form and the six consumer arms are renamed onto it (`ShadowedBuiltins.renameCallSites`, the
  compile paths' own walk) before they lower; those arms then re-expand instead of memoizing.
  **Order independence** (2026-09-27): a `defun` (a `defmethod` body included) and a lambda tail
  are lowered at DEFINITION, so one made before the first `defmethod` on its producer used to keep
  the built-in's lowering (`(defun early (x) (floor x))` then a `floor` method: `-: The value #<BX>
  is not of type NUMBER`) while the compile paths, whole-program, dispatched it. Every tail whose
  lowering changed something is now registered (`LispEvaluator.producerTailSites`, weakly: the
  defun's own `(block name ...)` cons, or the `(progn settled)` wrapper a lambda tail's memo entry
  shares between its closures), and a producer's first method, once its alias is bound, re-lowers
  each in place from the raw form. Zero call-time cost for a defun; a producer-tail lambda pays
  one `progn` step. Pinned by
  `ShadowedBuiltinsTest.everyLoweredNameDispatchesAUserMethodOnTheInterpreter` (every name, four
  call shapes), `everyLoweredNameDispatchesFromAFunctionDefinedBeforeTheMethod` (defun, method body,
  closure, `labels`, all defined first) and `MethodedBuiltinFixture` on the interpreter, JVM and
  WASM.
- **Compile paths** (`compiler/ShadowedBuiltins`, run by BOTH backends right after
  `expandTopLevelDefinitions`): `(close X)` is compiler-lowered whatever defuns exist, so the
  spliced dispatcher defun is dead. Per name in the COMPUTED set: replace the dead dispatcher
  (found by structural equality against a regenerated 2-arg dispatcher, so a user defun of the
  same name is never mistaken for it) with the interpreter's body renamed `%<name>--dispatch`;
  bind `%<name>--builtin` to a FORWARDER defun of the original built-in call
  (`ShadowedBuiltins.forwarderDefun`); rewrite call sites and `#'name` references onto the
  dispatcher. The walk (`compiler.CallSiteWalk`, shared with the Gray and socket rewrites) skips
  quoted data, `defmacro` bodies and `macrolet` definitions, the generated defuns themselves
  (`process` emits them unwalked -- rewriting the forwarder's fallback would recurse) and the
  non-evaluated positions of
  the binding, clause and definition forms (`let`/`lambda`/`flet`/`do`/`dolist`/`case`/
  `handler-case`/`cond`/`loop` patterns/slot specs ...), and
  keeps a PLACE's head (`setf`/`incf`/`push`/... -- `(setf (gethash k h) v)` of a methoded
  `gethash` was rewritten to an unknown `%gethash--dispatch` place and failed the compile). When
  `close` is shadowed, `with-open-file`/`with-open-stream`/`with-*-to-string` are pre-expanded
  (`unwindProtect=true`) — side effect: such a WASM module is always in EH mode.
- **The forwarder takes the whole tail** (2026-09-27; it used to drop the `&rest` tail, so a
  methoded `floor` lost every divisor: `(floor 7 2)` was `(7 0)` on the JVM and wasm). A
  variadic generic's forwarder cases over the tail's length into one DIRECT lowered call per
  count the built-in's `BuiltinCallArity` shape accepts (`(floor p)`, `(floor p (car r))`);
  past the positional counts an unbounded built-in is `(apply #'<name> p... r)` -- its
  function-value wrapper, which re-extracts keywords the lowering needs as literals
  (`write-line`'s `:start`/`:end`); `close`'s `(:abort v)` is checked at run time and dropped;
  every other count is `(%program-error (%string-concat "<OP> expects ..., got " count))`, a
  run-time message so no static warning fires for a count no call site passes. A backend alias
  (`%io-write-line`) is called only for a count inside its `WasmSocketsRewrite.ARITIES` range
  (`ShadowedBuiltins.BuiltinAlias`), as the pre-pass redirects only those. Pinned by
  `MethodedBuiltinTailFixture` on three backends and
  `ShadowedBuiltinsTest.everyForwarderCallsItsBuiltinWithACountTheCallShapeAccepts` (every
  name x 0..4 required parameters). Residual: a count the built-in rejects through the
  dispatcher reports the SHAPE's text (`FLOOR expects at most 2 arguments, got 3`), where the
  interpreter applies its `LispFunction`, whose own text can differ (`1 to 2`) -- the
  function-value message divergence `.kb/error-handling.md` already records.
- **The name set**: `BuiltinFunctionWrappers.names()` minus `%`-internals, minus
  `NOT_SHADOWABLE` (signal operators plus `make-instance`/`class-of`), minus
  `EXPANSION_LOWERED`, plus `LOWERED_WITHOUT_WRAPPER` (`close` first). **Pinned by
  `ShadowedBuiltinsTest`: every member must be a Java-backed `LispFunction` in a fresh global
  environment** — the exact interpreter stash criterion.
- **Remaining divergences (triggers)**: (1) a plain `(defun close (x) ...)` is ignored by the
  compile paths while the interpreter honors it; (2) `EXPANSION_LOWERED` names (mapcar, sort,
  format, ...) are un-dispatchable everywhere; (3) under `--component` with sockets spliced,
  `WasmSocketsRewrite` runs BEFORE this pass — the pass COMPOSES via
  `WasmSocketsRewrite.builtinDispatchAliases` (without it an instance reaching `%io-close` was
  a wasm CAST-FAILURE trap); still open, the ASYNC read promotions bypass a user method.
- **A run-time name reaches the dispatcher** (2026-11-05, ci-spec
  `a-method-on-a-lowered-built-in-answers-a-name-built-at-run-time`,
  `a-method-on-a-lowered-built-in-answers-a-form-eval-runs`). The call-site rewrite cannot see
  `(funcall (intern "CLOSE") x)` or `(eval '(close x))`, and no function of the name exists, or
  only the function value's wrapper does, so each answered undefined or the built-in's
  `type-error`. `ShadowedBuiltins.runTimeNames` gives both `_lookup` builders a row of the
  generic's name answering the dispatcher, replacing the wrapper's row. The name stays out of
  `functions`: the forwarder's call still has to lower, and its `#'write-line` still has to be
  the wrapper. The row is gated by the generic's name, as every row is by its own
  (`DesignatorSpellings`), and wasm's `anyDefunNameSpelled` reads those names too, since no defun
  carries one. What else follows the row: `_eval`'s `list` and `+ - * /` arms step aside for a
  methoded name (`JvmEvalRuntimeBuilder.evalBody`, `EvalHooks.armOffset`), as they answer without
  asking the registry; a literal `(symbol-function 'length)` or `(fdefinition 'length)` is
  `#'%LENGTH--dispatch` on a compile path (`CallSiteRenamer`), because the compilers resolve the
  literal in `functions`; a dispatcher reports a wrong count and prints under the built-in's name
  (`arityOperator`, `printedName`), so `(print #'close)` is `#<function CLOSE>` everywhere.
  A write of the name reaches the dispatcher's cell (`.kb/symbol-runtime-api.md`, "A write through
  a computed name"): after `(setf (symbol-function 'length) f)` a literal `#'length`, a
  `(symbol-function 'length)` and a call of `length` answer `f` as `_lookup` does.
- **A generic has one function object on the interpreter** (2026-11-05): `#'g`, `symbol-function`
  and `fdefinition` answer the one late-bound wrapper per name (`LispEvaluator.functionValue`), so
  `(eq #'g #'g)` is T as on the compile paths and SBCL; each reference used to make a new one.

## The instance-initialization protocol
`initialize-instance`, `reinitialize-instance`, `shared-initialize` are CL symbols
(`PackageRegistry.CL_FUNCTIONS`, like `print-object`): CL has ONE generic each, so a method in
any `(:use :cl)` package joins the same generic; otherwise each package mints its own and
`make-instance`'s chain — which matches generics by PLAIN name, first hit — runs one package's
chain while another's hooks never fire.

They have no system primary in the static subset, so `expandDefmethod` SYNTHESIZES one the
first time any method is defined on one, plus the CL chain (`initialize-instance` /
`reinitialize-instance` -> `(apply #'shared-initialize instance t/nil initargs)`,
`shared-initialize` -> the instance); a missing `shared-initialize` generic is CREATED here.
`expandMakeInstance` calls `shared-initialize` DIRECTLY (slot-names `t`) when only that
generic exists. **The synthesis condition is the absence of an ALL-DEFAULT primary, not of any
primary**: a class-specialized primary must not displace the system method a sibling's
`(call-next-method)` has to find. `synthesizeCalledInitProtocolGeneric` does the same for the
three names when the program only CALLS one — so a program merely calling
`reinitialize-instance` gains a `shared-initialize` generic and `expandMakeInstance` routes
construction through the chain (same values, different emitted shape).

Three congruence rules:
- **A `&key` method never rejects a sibling's keyword** — the lite model appends
  `&allow-other-keys` to every `&key` method lambda list instead of taking CL's union --
  AHEAD of an `&aux` section, which it would otherwise misplace (access's
  `rem-arg-list-key-value`; ci-spec `lambda-list-extensions`).
- **A defclass constructor tolerates extra initargs** (`&allow-other-keys` on `%make-<name>`),
  a class with NO slots included (`(&key &allow-other-keys)`, `keywordConstructorShape`). Until
  2026-10-31 that one took no arguments, so an initarg an `initialize-instance` method declares
  (valid, CLHS 7.1.2) failed `Function expects 0 arguments, got 2` on the interpreter and JVM --
  and so did every method initarg on a user subclass of the slot-less `standard-method`. ci-spec
  `defclass-default-initargs`.
- **A bare `(call-next-method)` forwards the WHOLE original argument list**:
  `rewriteNextMethod` emits `apply` over the method's `&rest` variable, injecting one
  (`%method-args`) when the method declares a `&key` tail without a `&rest`. **`&optional`
  needs more than the rest variable** — `&rest` binds what is left AFTER the optionals, so
  forwarding it alone silently drops them; a method that chains has every `&optional` entry
  normalized to `(var init supplied-p)` and the bare call becomes
  `(apply %next-method req... (append (if sp1 (list o1) nil) ... %method-args))`, CL's rule
  that an UNSUPPLIED optional is not passed on. Gated on the body mentioning
  `call-next-method`/`next-method-p`; pinned against SBCL over six tail shapes.

**The trio, `print-object`, `make-load-form`, `slot-unbound` and `slot-missing` are function values with no method in the
program** (`isCallableSystemGenericName`): the interpreter synthesizes the generic in
`resolveFunction`, the compile path in `expandTopLevelDefinitions` for any program that NAMES one
(which also takes it off the no-CLOS fast path). Before 2026-10-07, `#'initialize-instance`
in a program with no CLOS definition refused on the JVM, P1 and the component (`Cannot compile:
INITIALIZE-INSTANCE as a function value`), and the interpreter had no `make-load-form`.
**The init defaults apply to instances only** (`whenInstance`: `(%obj-p x)`, else the
no-applicable-method error, SBCL's answer): `(initialize-instance 1)` used to answer `1` on the
compiled backends and signal `%MOP-FILL-SLOTS expects (instance initargs initforms-p)` on the
interpreter. Every instance is `%obj-p` -- class metaobjects, structs and conditions too -- so the
MOP protocol's own calls pass. Pin: `StandardGenericFunctionValueFixture`.

**Cold-branch tolerance**: on the COMPILE paths only, `expandMakeInstance(cons, registry,
true)` lowers an unknown class to a runtime `error` instead of failing the compile.

## Ambiguous slot writes, runtime `typep`, runtime slot names
- `(setf (slot-value obj 'NAME) v)` with NAME at DIFFERENT indexes in unrelated types does not
  error at compile time: `expandAmbiguousSlotSet` emits an instance-TAG dispatch.
- `(typep x COMPUTED-SPEC)` does not error. The INTERPRETER re-expands per call
  (`expandRuntimeTypep`): a `cond` on the specifier symbol, one arm per registered layout plus
  one per built-in atomic name (`RUNTIME_TYPEP_BUILTINS`); an unrecognized specifier — a
  COMPOUND one included — yields nil rather than signalling. **A runtime specifier is matched
  by SPELLING**; the reverse fallback (a QUALIFIED specifier against a plainly-registered
  class) is deliberately not emitted, since the only plainly-registered classes are the seeded
  ones whose names are `cl` symbols. **Trigger**: a plainly-registered class whose name is not.
- **The COMPILE paths must not inline that dispatch**: its size is proportional to the
  registered-class count, and at cl-postgres scale (165 layouts) three
  sites overflowed the JVM's signed-16-bit branch offsets (the since-removed `StackMapAugmenter`:
  `Index -31123 out of bounds`). `expandTypep(cons, registry, false)` emits `(%typep-runtime value spec)` and
  `expandTopLevelDefinitions` injects the defun plus the `%typep-tag-table%` data table —
  quoted data mapping each type name's spellings to the tags it accepts, emitted as CHUNKED
  top-level forms (a defvar plus `(setq .. (append 'chunk ..))` continuations, 48 entries
  each). Same for `%subtypep-runtime` (`%subtypep-ancestor-table%`; the inline shape
  overflowed the same offsets) and for a computed `error` condition-type datum WITH initargs
  (`(%error-runtime datum (list args...))` over per-condition `%ERROR-RT-n` helpers — the old
  inline shape overflowed the 64 KB hard limit).
- `expandClassSlotDefs` lowers `%class-slot-defs` to the SHARED `%class-slot-defs-runtime`
  defun with `%class-slot-defs-table%` — one entry per registered LAYOUT (classes AND structs,
  since `ClosRegistry.slotDefs` is the one resolver). Designators: instance tag, plain name,
  class metaobject. Answers `((slot-name declared-type) ...)`, a struct's types all `T` —
  which is what lets json.lisp's `%json-out-instance` treat a struct like a CLOS instance. The
  old per-site inline cond hit the JVM 65535-byte ceiling. Chunked by cons-node
  budget (`nodeBudgetedTableForms`).
- A non-literal slot name falls to `expandRuntimeSlotValue` (a NAME dispatch over every slot
  name any layout declares; same-index names share one `member` arm, differing ones get an
  inner `%obj-is` TAG dispatch; only an unknown name signals) or `expandRuntimeSlotBoundp`.

## A RUNTIME class designator: both colon spellings
The compile paths carry no package registry at run time, so `intern` always assembles the
single-colon EXTERNAL spelling `PKG:MEMBER` (`.kb/symbol-runtime-api.md`) while the class is
registered canonically as `PKG::MEMBER`, and every generated designator dispatch matches by
SPELLING. The fix is at the LOOKUP: `LispMacroExpander.addDesignatorSpellings` is the ONE
place turning a registered name into the spellings that designate it, and every generated
table/dispatch built from a class, struct, condition or alias name goes through it:
`%class-meta-table%`, `%mop-make-instance` (and `%MMI-REFILL`), `%allocate-instance`,
`%class-direct-subclasses`, the runtime `typep` tag table, the `subtypep` universe,
`%error-runtime`. One package cannot house two distinct symbols with one member name, so the
added spelling can never designate another class; a `cl-user` program is byte-identical.

## `change-class` — in place, on all four backends
Object identity and every slot whose NAME both classes have survive (CLHS 7.2, also between
unrelated classes), slots the new class adds are filled from initforms, supplied initargs
stored, the instance is the value.
- `expandChangeClass`: capture the OLD tag -> `(%obj-become obj '%class-T)` -> a `cond` on the
  captured tag, one arm per possible source (the target's chain + every non-seeded class)
  whose by-name mapping is not the identity: moved values are read from their old index
  (`let`) before any store, missing slots get the target's initform (unbound without one), NOT
  when an initarg supplies the slot (`changeClassInitformFill`'s `supplied`: Drei's narrowed marks
  have `:initform (error ...)`, ci-spec `clos-computed-change-class`) ->
  the initarg stores. **The tag is captured BEFORE the swap.** Sources mapping alike share one arm
  (`ChangeClassSlotOrigin`): most share nothing with the target, and an arm each repeated one fill
  per source. The computed path's dispatch is quadratic in user classes in its tag tests alone (a
  runtime layout-driven remap would make it linear). Measured 2026-11-05 on
  `AsdfMetaobjectsE2eTest`'s program, 103 classes, cold `wasmtime compile` on 4 CPUs: arms 1.33 MB
  of a 3.32 MB module, 0.89 GB; an inline initarg scan per slot per source took them to 4.69 MB of
  7.09 MB, 21 bodies of 165-203 KB side by side, and the peak to 7.2 GB, which killed a `test-e2e`
  runner ([test-execution.md](test-execution.md), "CI shards"); merged, with a `getf` for a slot of
  one initarg, 0.77 MB of 2.72 MB, largest arm 49 KB, 0.71 GB.
  **An arm tests its sources with one `member`, never an `or`:** an `or` expands to a `let` and an
  `if` per element, which the backends walk recursively (`JvmIntFusionCompiler.scanAssigns` over the
  rest of the nest at each `let`). On the corpus's 358 classes it overflowed a 1 MiB compile thread
  in `JvmDeadMethodEliminationCorpusTest`; on 300 classes of 2 slots the JVM class is 4.74 MB and
  16 s instead of 1.90 MB and 1.7 s. The corpus, front end included, on 8 CPUs: `3cc5e1713`, arms
  5.18 MB of a 16.9 MB module, compile 72 s on the JVM and 61 s on wasm; `f1726187a`, 13.53 of
  25.2 MB, 252 s and 118 s (macOS CI's `compile-jvm` past `CiSpecE2eTest`'s 300 s); both changes,
  1.41 of 12.7 MB, 81 s and 45 s, and it compiles on a 512 KiB stack. Pins
  `WasmToplevelChunkingTest#aComputedChangeClassFillsASlotOncePerWayAClassMapsOntoIt`, ci-spec
  `clos-computed-change-class`.
- **`LispLayout.capacity()` is why this works on the JVM**, where an instance IS its
  `Object[]`: every class an instance can be changed from (the target's ancestors and every
  non-seeded class) reserves the target's slot count at construction. Before 2026-10-31 only
  ancestors did, and a change between unrelated classes overflowed the array on JVM and WASM. `expandTopLevelDefinitions` scans for targets (`registerChangeClassTargets` —
  the form lives in a body, not at top level) and calls `applyChangeClassCapacities()` once
  the registry is complete. `%obj-slots` and the printers bound themselves by the LAYOUT's
  slot count, never the array length.
- **The WASM instance struct's field 0 is MUTABLE** for this, making it structurally identical
  to `TYPE_P1_FUTURE` `{mut i32, mut eq}` — so its rec group carries a second,
  never-instantiated empty struct: wasm canonicalizes a rec GROUP as a whole and a 2-member
  group can never equal a 1-member one, so `ref.test` keeps telling an instance from a future
  (`INSTANCE_TYPE_COUNT = 2`). **Do not "simplify" it.**
- The interpreter needs no reservation (`LispInstance.becomeLayout` grows the array).
- **The class argument may be COMPUTED**: the interpreter resolves natively
  (`resolveChangeClassDesignator`, CL evaluation order, metaobject folded to its name); the
  compile paths lower to `(%change-class-runtime obj cls (list initargs...))` and
  `expandTopLevelDefinitions` (gate `needsChangeClassRuntime`) generates one spelling-matched
  `%CC-<n>` arm per registered NON-SEEDED class — so every non-seeded class joins the
  change-class CAPACITY reservation.

## Real slot unboundness
**A slot written with no `:initform` starts UNBOUND, not nil.** Reading signals
`unbound-slot`; `slot-boundp` says nil; `slot-makunbound` puts it back. jzon's
`coerced-fields` and `%json-out-instance` SKIP an unbound slot.
- The marker is an instance of a LAYOUT-ONLY internal type, `ClosRegistry.UNBOUND_TAG` =
  `%class-%UNBOUND%`, registered in the constructor and deliberately NOT in `classes()`: a
  class there would join every typep tag table, `class-slot-defs` answer and
  `standard-object` descendant set. Testing for it is the same one-compare `%obj-is`; it
  prints `#<%UNBOUND%>`. `SlotSpec.initformSupplied` records whether the source wrote an
  `:initform`; `initform` then holds `(%obj-new '%class-%UNBOUND%)`.
- Every read goes through ONE out-of-line helper `(%slot-read value instance 'NAME)`, with
  `(%slot-bound-p value)` for `slot-boundp` (`slotUnboundDefuns`, emitted when
  `needsSlotUnboundHelper`). **They are calls, not inlined `%obj-is` + `if`, for a size
  reason**: an inline instance-of test per accessor ran
  the ci-spec corpus past the 64 KB method limit
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- In a GENERATED accessor body the `'NAME` rides in `%unspelled-quote` (`checkedSlotRead`), so
  it must not arm the funcall-dispatch gate's name probes
  ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)); a user-written
  `slot-value` keeps the plain quote. `expandSlotValue` is the CHECKED read,
  `expandSlotValueRaw` the unchecked one the `setf` place expansion needs. A RUNTIME slot name
  is checked too (`%slot-read` over the computed name; the interpreter's `evalSlotValue` calls
  the same defun) -- before 2026-10-30 it read the marker `#<%UNBOUND%>` on all four backends.
- **The name `%slot-read` carries is the slot symbol as declared** (`slotNameSpelling`), so
  `cell-error-name` of a slot declared in package `zp` is `ZP::DATA`, `eq` to `'data` read
  there (it was the unqualified `DATA` before 2026-10-30), and an `(eql 'data)` specializer
  written in `zp` matches.
- `unbound-slot` is seeded under `cell-error` (which gained CL's `name` slot) with an extra
  `instance` slot and a `:report` LAMBDA built in Java (`ClosRegistry.unboundSlotReport`, over
  `%obj-ref` indexes rather than `slot-value`). `type-error-datum`,
  `type-error-expected-type`, `cell-error-name`, `unbound-slot-instance` are prelude defuns.
  `print-not-readable` is seeded the same way (`object` slot, SBCL's report text,
  `print-not-readable-object`); the printer signals it (`.kb/pretty-printer.md`).

## `slot-unbound` / `slot-missing`
CL generics (`CL_FUNCTIONS`, `isCallableSystemGenericName`, `standardGenericShape` 3 / 4+1
optional); a method in any `(:use :cl)` package joins the one generic. The first method (or a
mere call) synthesizes the SYSTEM default primary (`synthesizeSlotProtocolDefault`) signalling
what the program signals without the generic, so an uncovered read is unchanged.
- **Routing is gated on the generic existing.** Compile paths: `%slot-read`'s unbound arm is
  `(slot-unbound (class-of instance) instance name)` when the registry has `SLOT-UNBOUND` after
  the walk (`slotUnboundDefuns(routed)`), else the direct `unbound-slot` signal; a program
  NAMING either generic forces the metaobject runtime (`class-of`). The interpreter always
  routes (its `%slot-read` is defined once, lazily, before a later `defmethod` could be seen).
- `missingSlotAccess`: with `SLOT-MISSING` registered when the access expands, a literal-name
  read is `(slot-missing (class-of o) o 'slot 'slot-value)`, a `setf`
  `(slot-missing ... 'setf v)` answering `v`; else `missingSlotStub`. Residuals: `slot-boundp`
  of an undeclared slot answers nil without the call, a runtime-name access of an undeclared
  slot keeps its own error, and a slot SOME other class declares is not detected as missing.
- ci-spec: standalone `clos-slot-unbound-and-slot-missing-methods` (a method reroutes every
  slot read of the program, so it stays out of the shared corpus).

## Slot shadowing, `:default-initargs`, `with-slots`/`with-accessors`, slot options
- **A subclass may re-declare an inherited slot** (CLHS 7.5.3): STORAGE stays the one
  inherited slot (descendants keep the baked index) while the subclass spec overrides
  initform and ADDS its initargs and readers/accessors (`shadowSlot`; the initargs are the
  union over the specifiers, CLHS 7.5.3, a specifier's slot-name default excluded).
- **`(:default-initargs :arg form)` overrides the matching slot's effective initform**, which
  is where BOTH construction paths read it; before this it only reached the constructor, so
  `(error 'my-cond)` ignored it.
- **A slot may name several initargs** (CLHS 7.1.1): `SlotSpec.initargs`, never empty, the
  leftmost supplied one wins (7.1.4). The constructor keeps one `&key` per slot (its first
  initarg) and only a several-initarg slot adds a `&rest` scan (`keywordConstructorShape`);
  every other path -- `:default-initargs`, literal `make-instance`/`change-class`/typed-signal
  sites, the runtime change-class/error/fill helpers, `%mop-fill-slots` -- matches through
  `acceptsInitarg` or the inline `initargTailScan`, a single-initarg slot keeping its old shape.
- **An initarg is any symbol** (CLHS 7.1.1; cl-vectors' `(rx :initarg rx)`): `SlotSpec.initargs`
  hold the symbol's name as written, the constructor's `&key ((rx slot))` matches it as written
  (`.kb/lambda-lists.md`), generated code passes a non-keyword one quoted (`initargForm`), and a
  `make-instance`/`change-class`/typed-signal call site reads `'rx` as a literal initarg
  (`literalInitarg`). Pinned by ci-spec `defclass-default-initargs`.
- **`with-accessors`** is the accessor-call twin of `with-slots` (`expandWithAccessors`),
  needing no registry. **`with-slots` resolves `defstruct` slots too**: `expandSlotValueRaw`
  picks the index out of the LAYOUT registry (`uniqueLayoutSlotIndex`), falling back to the
  tag dispatch when types disagree.
- **A slot variable is a symbol macro** (2026-11-04): `with-slots`/`with-accessors` run
  `substituteSymbolMacros`, the shadow-aware walk of `symbol-macrolet` ([symbol-macrolet.md](symbol-macrolet.md)), so
  what binds the name stops it and an operator or a `case` key is no variable. The textual walk they
  replaced stopped the compile paths on prove's `with-additional-indent`: a macro's inner
  `(with-slots (indent-space) ...)` reached the outer substitution already expanded, its slot list took
  the substitution, and the expansion refused `(slot-value ...)` as a slot name. Each path expands the
  body's macros first (the interpreter once per call site, `expandSlotVariables`), so a macro sees the
  name, not `(slot-value ...)`. ci-spec `with-slots-variable-is-shadowed-by-an-inner-binding`.
- **`with-slots`' entry-time fallback binding is BOUNDNESS-GUARDED**: besides substituting each
  slot variable, `expandWithSlots` `let`-binds it to
  `(if (slot-boundp obj 'slot) (slot-value obj 'slot) nil)` (`boundOrNil`) so code GENERATED
  inside the body still resolves. **Not cosmetic**: `with-slots` BINDS, never reads, so a body
  that only ASSIGNS an initform-less slot must not signal on entry (fast-io's
  `(with-slots (buffer) self (setf buffer ...))`); a read the body REALLY performs still
  signals. **`with-accessors` has no such binding** (2026-11-05): an accessor is a generic with no
  boundness twin, its unguarded read on entry refused a body only writing an unbound slot (Drei's
  views), and with the macros of the body expanded first nothing is left for it to resolve.
- **The instance temp is named PER FORM, so a NESTED `with-slots`/`with-accessors` cannot
  capture the enclosing one's** (`freshObjVar`). With the old fixed `__with_slots_obj` name an
  inner form rebound it and every outer read inside resolved against the INNER instance,
  silently. The name is chosen by scanning the whole form and stepping past
  (`__with_slots_obj`, `__with_slots_obj2`, ...) — a pure function of the form, no counter
  (`.kb/emitted-output-determinism.md`); the outer substitution PLANTS its temp name in the
  body it wraps, covering an inner form produced only by a later macro expansion.
- **A `slot-value` naming a slot NO registered class declares is a RUN-time error**
  (`missingSlotStub`): subforms evaluate for effect, then `(error "The slot X is missing")`,
  read side and `setf` place alike — a condition `handler-case` can see, like CL's
  `slot-missing`. `slot-boundp` on an undeclared slot answers nil.
- **`:writer`** (`SlotSpec.writers`): a symbol defines a two-argument new-value-first generic;
  `(setf place)` is stored pre-mangled as the `%setf-place` name. Writers merge on shadowing
  and are covered by the MI shifted-accessor synthesis.
- **`:allocation :class`** (`SlotSpec.sharedCellVar`): the value lives in ONE `defvar`'d global
  cell per DECLARING class (`%CLASS-CELL-<class>-<slot>%`); a subclass re-declaring with
  `:allocation :class` mints a new cell, one re-declaring WITHOUT reverts the slot to
  `:instance` (CLHS 7.5.3). The slot KEEPS a layout index — a dead mirror — so index
  computations, `slot-exists-p` and `%class-slot-defs` are untouched; every ACCESS routes to
  the cell (the effective spec's `initform` IS the cell symbol; generated methods use
  `checkedCellRead`; literal-name access routes through `classSlotAccessByTag`, null for every
  ordinary program; runtime-name dispatches carry `sharedCellsByTag`; the interpreter's
  `instanceSlotRef` answers a `CellSlotRef`). **Residual**: the instance PRINTER shows the
  mirror, and `equalp`/`%obj-slots` walk mirrors.
- **`standard-class` as a type specifier**: `makeTypeTest` calls `ensureMopClassesSeededFor` on
  an unrecognized symbol specifier before the registry lookup, `built-in-class` too.
  `structure-class` keeps its deliberate empty test.

## `define-method-combination`
Since 2026-11-05 on all four backends (`macro/MethodCombinations`), for McCLIM's `values-max-min`;
in the cached Quicklisp systems arnesi's `wrapping-standard` and `cc-standard` and serapeum's
`standard/context` are the other long forms (both libraries load on the interpreter, where
`wrapping-standard`, `cc-standard` and `standard/context` combine as in SBCL). No run-time part: what
the body computes becomes the dispatcher's code.
- **An evaluator runs the body, once per dispatcher branch.** `expanderLambda` makes the form a lambda
  of the generic's name, the `:method-combination` arguments, `(method . qualifiers)` per applicable
  method and the argument forms: the groups are classified in generated Lisp (`%mc-qualifiers-match-p`,
  a selector being a pattern or a predicate), ordered, and refused when `:required`, and the body runs in
  their scope. The short form is CLHS's long-form equivalent. `LispEvaluator.evalDefineMethodCombination`
  registers a `LispFunction` over it (`ClosRegistry.registerMethodCombination`), which hands the body
  a `standard-method` holding only the qualifiers (`methodObject`; CLHS's example sorts by
  `method-qualifiers`), puts the defun's name where each stood, and expands the macros of the answer
  (arnesi's `closure-with-nextmethod`; a compile path generates its dispatchers past its macro-time pass).
- **The compile paths** run the form in the macro-time evaluator and leave
  `(%define-method-combination 'name '#<function>)` (`UserMacroExpander.stripDefmacroDefinitions`), so the
  backend's registry calls that evaluator: `expandTopLevelDefinitions` registers and drops it first.
- **The effective method** (`definedCombinationEffectiveMethod`): every method applicable to the branch,
  most specific first, through the function; `MethodCombinations.lowered` makes `(call-method m nexts)`
  `(%m thunk args...)`, the thunk the lambda chain `buildNextChain` builds, `(make-method form)` a
  thunk of the form. The arguments are rebound to `%MC-ARG-n`, as the answer may bind a variable named
  like a parameter around a `call-method` (values-max-min's `max`). A signal of the function, a required
  group left empty among them, becomes `(error message)` in the branch: CLHS computes the effective
  method for the call, and a branch no call reaches must not stop the definition.
- **Qualifiers**: under such a generic any atoms before the lambda list (`methodLambdaListIndex`,
  shared with the walkers that had "a symbol after the name"), kept per defun in
  `GenericInfo.methodQualifiers`.
- Residuals: run-time `add-method` methods do not join such a generic (the short form's residual); a
  method object answers `method-qualifiers` only; SBCL's "More than one method of type X with the same
  specializers" is not signalled; `:arguments` takes required parameters and `&rest`.
- A true list matches element by element, `*` matching anything there (CLHS); SBCL 2.2.9's
  `qualifier-check-runtime` honors `*` as the tail only, so `(:a *)` misses `(:a :x)` there.
- Pinned by ci-spec `define-method-combination-long-and-short-forms` (SBCL 2.2.9 agrees).

## `print-object` — the printer consults it
A `defmethod print-object` makes the printing operators render that type through the generic.
**Gated on the program defining a method** (plus the condition and `*print-case*` gates
below): with none, every printing operator compiles exactly as before.

**The DIRECT call always works.** `synthesizePrintObjectDefault` registers CL's SYSTEM method
— `(write-string (if *print-escape* (%prin1-to-string o) (%princ-to-string o)) s)` — from
`expandDefmethod` when the FIRST method is defined and from `expandTopLevelDefinitions` /
`LispEvaluator.resolveFunction` when the program only CALLS the name
(`isCallableSystemGenericName` / `synthesizeCalledSystemGeneric`).
- It renders through the RAW `%prin1-to-string`/`%princ-to-string`, not `%print-object-str`, as
  a **correctness requirement**: `printObjectTags` collects specializers from EVERY parameter
  while a dispatcher dispatches on the FIRST, so a method specialized on its STREAM parameter
  puts that class in the routed set and a routed default would recurse forever.
- **Two compile-path scans had to learn the name**: `expandTopLevelDefinitions`' fast path and
  `injectMvSpillGlobal`'s `*print-escape*` gate; without the second the default read an
  unseeded global and printed the `princ` spelling.
- `expandPrintObjectHook` rewrites `princ-to-string`/`prin1-to-string`/`write-to-string` (and
  `%princ-piece`/`%prin1-piece`, `.kb/string-write-runtime.md`) to
  `(%print-object-str x escape)` and `print`/`princ`/`prin1` to a `write-string` of it
  (+ `terpri`). `format`'s `~A`/`~S` need no case of their own.
- `%print-object-str` falls back to `%princ-to-string`/`%prin1-to-string` — INTERNAL ALIASES
  of the same two functions; without them the fallback would re-enter the rewrite that
  produced it. The raw renderer carries the shared cycle guard (`.kb/pretty-printer.md`) and
  the walk's cons/vector arms its Lisp twin (`%pos-walk`, `%pos-chain-stop` = Floyd).
- **`*print-escape*` is BOUND around the method call** (`printObjectCall`), `t` for
  prin1/print/`~S`, `nil` for princ/`~A`. `*print-escape*`/`*print-readably*` are
  `CL_VARIABLES`; the compile path injects `(defvar ...)` from `injectMvSpillGlobal`, which
  runs AFTER `expandTopLevelDefinitions` and therefore sees the route's own reference. **That
  ORDER is load-bearing.**
- **Gate 2 is a condition's `:report`** (`.kb/error-handling.md`), rendered by the escape-off
  arm through `%condition-report-str`; a method on a condition class still wins. **Gate 3 is
  `*print-case*`**: the leaf every arm falls back to becomes `(%print-cased x escape)`, UNDER
  the method route. There is exactly one place that decides what text a printing operator
  writes.
- **The method is consulted for a NESTED value too**: the generated renderer is a PAIR —
  `%print-object-str` walks a cons and a general rank-1 vector element-wise by recursing into
  itself, `%print-object-leaf` is the routing half. **The walk must reproduce the raw renderer
  byte for byte** for what it does NOT route: a cons (one space before every element but the
  first, `" . "` before a non-nil tail) and `#(...)`; no `'x`/`#'f` abbreviation and no `#*`
  syntax exists here. **The vector guard excludes what it cannot spell** — a string, an array
  of rank != 1, a packed FLOAT array — written as "the element type is not
  `single-float`/`double-float`", NOT "is `t`", since the general answer is a `T` SYMBOL in the
  interpreter and the `t` VALUE on the JVM and no single `eq` spans both; a packed INTEGER
  vector is deliberately walked. The vector arm is emitted only when
  `programUsesGeneralArrayOp`. **The interpreter stopped INLINING the renderer** and
  (re)generates the defun pair whenever the routing moves (`ensurePrintObjectRuntimeLoaded`,
  stamped on the tag set + `routesConditionReports` + `*print-case*`) — what lets a `defmethod
  print-object` evaluated AFTER the first print take effect. **Still not walked (trigger)**: a
  hash table, an array of rank != 1. **Cost**: O(n^2) in string concatenation, like `%print-cased`'s.
- **An instance's slots are walked** (2026-11-05, `%pos-instance`): `%print-object-leaf` takes the
  walk's path, and an instance no method routes spells its own frame from `%obj-print-frame`
  (`.kb/instance-syntax.md`) and hands each slot value back to `%pos-walk`, so a method applies in a
  structure or class slot at any depth (sento logged 353876 characters for one idle actor, and an `ask-s`
  with a `:time-out` met a list another thread was growing in about one run of thirteen). Under
  `princ` a condition's report still goes first. It honors `*print-level*` and `*print-length*` as SBCL
  2.2.9 does (`#S(ITEM :A # :B #)`, `#S(ITEM :A 1 ...)`). A pathname or a stream has no frame and takes the
  leaf. Gate: `printObjectStrDefuns`' `walkInstances`, on for a method or an instance type of the
  program's own (`ClosRegistry.definesInstanceTypes`), so a program holding only seeded conditions keeps
  its two-argument leaf. Pinned by ci-spec `print-object-nested`.
- **`print-object`'s system method walks too** (`%print-object-default` lowering to `%pos-default`, or to
  the raw conversions in a program with no walk), out of `(call-next-method)` and on a direct call. It does
  not route the instance it was handed: `printObjectTags` collects specializers from EVERY parameter
  while the dispatcher dispatches on the first, so a class specialized only as a method's STREAM parameter
  would recurse. A method between two instances starts the walk's path over, so the special
  `%pos-defaults` holds what this method is rendering and a cycle through a method still ends in `#`
  (SBCL exhausts the stack there). The interpreter loads the pair at that call, a direct one being able
  to come first.
- **A `print-object` method defined BELOW its first use renders the built-in text for
  the earlier print on all four backends** (todo 445; pinned by the per-backend
  `*PrintObjectMethodDefinedBelowItsFirstUse` tests and
  `clos-print-object-method-defined-below-its-first-use`). The compile paths
  route through the generic from the very first print (whole-program registry) while
  the interpreter has not seen the method yet — the two cannot agree on the first
  print by construction, so the compile path answers what the interpreter (and CL)
  answers for this program: the branch guard skips the not-yet-assigned body and the
  hoisted system default renders the raw text. The alternative (no-applicable-method)
  was rejected because the repro must PRINT, and a raw `NullPointerException` /
  wasm cast-failure trap — the pre-fix behavior — is a condition no program can act
  on. The same guard covers every generic, not only the printer.
- `print-unreadable-object`'s `:type t` prints the type NAME with the `%struct-`/`%class-` tag
  prefix stripped INLINE (`typeNameOf`), not via the prelude's `type-of`: this expansion runs
  inside the compilers, after the prelude pre-pass. The separating space is written only when a
  body follows (SBCL 2.2.9 writes it with an empty body too, `#<D >`, against CLHS's wording);
  `:identity` prints NO address. **That designator follows `*print-escape*`**
  (CLHS 22.1.3.3): `prin1` of a `quri:uri` gives `#<QURI:URI ...>`, `princ` `#<URI ...>`; the
  tag prefix attaches to the PACKAGE half, so only the unqualified spelling reaches the
  prefix-stripping cond. Where a printer control can be in effect the stripped text goes on through
  `%pc-type-name`, `.kb/pretty-printer.md`. The reference is late (Pass 2), so `injectMvSpillGlobal`
  counts an un-expanded `print-unreadable-object` as the mention declaring `*print-escape*`.

## Short-form `:method-combination`
`(defgeneric g (x) (:method-combination NAME [:most-specific-first | :most-specific-last]))`,
NAME one of `ClosRegistry.SHORT_FORM_COMBINATIONS` —
`progn`/`and`/`or`/`+`/`list`/`nconc`/`append`/`max`/`min`. A NAME outside the set that no `define-method-combination` defined (below) is REJECTED at
`defgeneric` time, and `standard` is the default written out.
- `GenericInfo` gains `methodCombination` + `mostSpecificLast`; `registerDefgeneric` records
  the option BEFORE the inline `(:method ...)` clauses expand.
- Legal qualifiers: the combination NAME as a primary qualifier, `:around`; `:before`/`:after`
  and an unqualified `defmethod` are REJECTED (CLHS).
- `shortFormEffectiveMethod` builds `(NAME (m1 nil args...) ...)` over EVERY applicable method
  of that qualifier in branch-specificity order, reversed under `:most-specific-last`. The
  `next` argument is nil throughout — CLHS gives short-form primaries no `call-next-method`;
  only an `:around` has one, and its next is the combined form.
- No applicable method is the ordinary `noApplicableMethod` error, NOT an empty `(progn)`: an
  empty `(and)` answers `t` and an empty `(+)` zero, hiding a missing method.

## MOP boundary — what is IN
The STATIC metaobject subset is IN: `find-class` AND `class-of` answer a real memoized
`standard-class` or `built-in-class` instance on all four backends, `eq` to each other for the
same class.
- **Interpreter**: Java built-ins over `ClosRegistry.classMetaobject` (designator-aware: plain
  names AND instance tags; a struct class is a `standard-class` instance, `structure-class`
  does not exist) and `builtinClassMetaobject` (`BUILTIN_CLASS_NAMES` = exactly the
  `class-of` designator result set, `T` for everything else; a `built-in-class` instance but for
  `STANDARD_METACLASS_ROOT_NAMES`, `standard-object` and `structure-object`). Seeding is lazy
  (`ensureMopClassesSeeded()`) — **NEVER seed unconditionally**: that joins every runtime
  dispatch table and once pushed the ci-spec corpus over the JVM 64 KB method ceiling.
- **Compile paths**: `expandTopLevelDefinitions`, gated on the program referencing `find-class`
  OR `class-of`, injects the node-budget-chunked `%class-meta-table%` (one entry per registered
  class AND struct layout: spellings — the instance TAG among them — / superclass /
  effective-slot data) plus `%find-class` + `%find-class-materialize` (an entry of the spellings
  alone, what the built-in names resolve to, makes a `built-in-class` instance). The public `find-class`
  defun is a thin wrapper injected only when the program references it without defining it.
  `(class-of x)` expands to `(%find-class <%class-designator dispatch> t)`.
- The OLD tag/type-name view lives on as the internal `%class-designator`, ridden by the light
  consumers (prelude `type-of`, `print-unreadable-object :type`, the no-applicable-method
  message, `%json-out-instance`) — they drag no metaobject runtime in.
- **The built-in class set is TWO lists and ONE narrowing.** `BUILTIN_CLASS_NAMES` is what
  `class-of` can answer (the eight standard stream classes included, which the designator
  answers for a built-in stream instance, `.kb/gray-streams.md`, and `pathname`/`readtable`/`random-state`,
  the fixed layouts of `VALUE_LAYOUT_CLASS_NAMES`, which `class-of` used to refuse on the compile paths and
  answer `t` for on the interpreter); `FIND_CLASS_ONLY_CLASS_NAMES` is the rest of the CL built-in lattice,
  reachable by NAME only because every value it covers has a narrower answer
  (`number`/`real`/`rational` under `integer`/`ratio`/`float`/`complex`, `sequence`/`list`
  under `cons`/`null`, `bit-vector` under the general `vector` — a bit vector IS the general
  array stamped with the remembered element type `bit` since `.todo/043`, but `class-of`
  still answers the narrower representation name — plus `structure-object`, `built-in-class` and
  `standard-object`). The narrowing is ARRAYS: `%class-designator` answers `T` for every one
  of them, while `class-of` answers `vector` at rank 1 and `array` otherwise, leaving a string
  to the narrower `string` the designator already gives. Two emission sites, one dispatch —
  `LispMacroExpander.expandClassOf` (over `%arrayp`, which is the array representations
  WITHOUT strings) and the interpreter's `classOfTypeName`. `type-of` is NOT this dispatch: it
  rides `%class-designator` and its own `%arrayp` arm, and still answers `(simple-vector 3)`.
- **The lattice is NOT copied into the metaobjects.** A built-in class metaobject has no slots
  and no superclasses; standing where a type specifier is expected it means the same set as
  its NAME (the normalization below), so `typep`/`subtypep` hold the lattice — once — and
  `(typep #(1 2) (find-class 'array))` and `(subtypep (find-class 'vector) 'array)` answer
  through it. Filling `direct-superclasses` would be a second copy that can drift, would
  nest the printed representation of every built-in class four deep, and buys no ANSI test:
  what the suite asks of these names is `(typep X (find-class 'name))` and "does the name
  resolve", not `class-precedence-list` (which `.todo/715` §4 rules out).
- **`built-in-class` is a metaclass beside `standard-class`** (2026-11-05, on all four):
  `(class-of (find-class 'array))` is `built-in-class`, `(typep (class-of 1) 'standard-class)` nil.
  McCLIM's `record-presentation-type` asks exactly that to tell a CLOS class, which gets a proxy,
  from a built-in one, which gets a class of its own (every built-in class a `standard-class`
  instance stopped it: `DEFCLASS: the type NULL cannot be a superclass`). SBCL's `system-class`
  (`t`, `function`, `stream`, `sequence`) and its structure classes among the built-in names
  (`hash-table`, `readtable`, `random-state`, the composite streams) are `built-in-class` here;
  `boolean` and `keyword` name no class in SBCL. What moved with it: `ClosRegistry.isClassMetaobject`,
  `closer-mop:classp` and the name normalizations (`metaobjectNameNormalization`,
  `%class-slot-defs-runtime`) ask for `class`; `%slot-protocol-instance-p` asks for a
  `standard-class`, as what `class-of` answers a built-in class for (a stream, a pathname) is no
  protocol instance; the system `shared-initialize` is on `class`. `isMetaobjectClass` means a
  metaclass a program instantiates, so `class` and `built-in-class` have no constructor, and
  `make-instance` of one is the seeded classes' run-time error.
- **Checked 2026-09-11** (suite `ca06bd9`, interpreter, `arrays` + `objects` +
  `types-and-classes`): adding the names fixes 40 `arrays` cases and one
  `types-and-classes` case, **+41 tests**, with `objects` unchanged. Only
  8 `there is no class named` lines survive, for `broadcast-stream` (7) and
  `standard-generic-function` (1; a class since 2026-10-31, not remeasured). `.todo/744` priced the row at **156** by counting every
  TEST-level `ERROR` line naming `array`/`vector`/`bit-vector`/..., which over-counts: the
  test behind such a line often fails for a second reason once the class resolves. 14
  `BIT-VECTOR.*`/`SIMPLE-BIT-VECTOR.*` tests turned ERROR into FAIL for exactly that reason
  -- `(typep #*101 'bit-vector)` was nil because there was no distinct bit-array
  representation then (`.todo/043`, `.todo/180`); since `.todo/043` landed the stamp the
  predicate answers `t`, so those rows want a remeasure -- and the `built-in-class` /
  `structure-object` / `real` rows are behind `*universe*` (`.todo/679`) or
  `class-precedence-list` instead.
  A count of ERROR lines naming a missing operator is an UPPER bound on the tests it wins.
- **`typep`/`subtypep` take a class METAOBJECT wherever a type specifier is expected.** ONE
  rule — "an instance tagged as a `class` descendant continues as its slot-0 name" —
  with three emission sites: `subtypep` folds it in Java for the interpreter
  (`classMetaobjectDesignator`, AHEAD of the `t`/`nil` constant edges), and the emitted
  `%typep-runtime` (the specifier) / `%subtypep-runtime` (BOTH arguments) carry
  `metaobjectNameNormalization`. A metaobject is always a RUNTIME value, so the literal fold is
  untouched.
- `class` is a SEEDED class (`ClosRegistry.CLASS_NAME`), superclass of `standard-class`,
  `built-in-class` and `forward-referenced-class`, so `(typep x 'class)` is the metaobject predicate
  through the ordinary ancestor machinery. It holds the five slots of every class metaobject, its
  children being slot-less. Never instantiated.
- **The method metaobject classes** (2026-10-31, `ClosRegistry.METHOD_METAOBJECT_FAMILIES`):
  `generic-function` > `standard-generic-function`; `method` > `standard-method` > closer-mop's
  `standard-accessor-method` > `standard-reader-method`/`standard-writer-method`;
  `method-combination`; closer-mop's `eql-specializer`. Single-parent roots (AMOP's
  `metaobject`/`specializer` left out); `generic-function` descends from closer-mop's
  `funcallable-standard-object` since 2026-11-01, so naming the family seeds the funcallable pair
  and the MOP base too, and `standard-generic-function`, `standard-method` and `eql-specializer`
  carry the slots of the generic-function protocol below (`GENERIC_FUNCTION_SLOTS`,
  `METHOD_SLOTS`, `OBJECT`); the CL names registered bare (moved from `PackageRegistry.CL_EXPORTED_ONLY` into
  `CL_TYPES`), the closer-mop ones (its externals) under the EXTERNAL spelling
  `CLOSER-MOP:NAME` the reader gives them, so `class-name` is `eq` to the symbol read --
  `seedClass` normalizes its parent lookup for that. The funcallable pair and
  `java:java-exception` are registered `CLOSER-MOP::`/`JAVA::`, and their `class-name` is NOT
  `eq` to the read symbol (`(eq (class-name (find-class 'closer-mop:funcallable-standard-class))
  'closer-mop:funcallable-standard-class)` is nil on all four backends, 2026-10-31). Each FAMILY seeds
  whole where a program names a member: `ensureMopClassesSeededFor` (specializer, type test,
  superclass, the interpreter's `find-class`) and, on the compile paths, a pre-walk symbol scan
  (`seedMethodMetaobjectClassesNamedIn`) so a `find-class` of one is in the tables built after
  the walk -- a keyword never counts, and a bare `method` only QUOTED (it is a common variable
  name; its type/specializer/superclass positions seed during the walk anyway). The shared
  `EMPTY_CLOS_REGISTRY` is never seeded: `makeTypeTest` answers the empty test there.
  **Instances exist only under the generic-function protocol** (below): elsewhere a generic's
  value stays a plain function (`class-of` is `function`, `(typep #'g 'generic-function)` nil,
  trivia's `(etypecase fn (generic-function ...) (function ...))` still takes the function arm)
  and a method is a registry row, so such a specializer or type matches only an instance of a
  USER subclass (contextl's `(defclass layered-method (standard-method) ())`), and
  `make-instance` of a seeded one is a run-time error after the initargs evaluate
  (`expandMakeInstance`), so a library carrying the call compiles. `funcallableTags()` is
  empty while every funcallable layout is a SEEDED class and the protocol is off: nothing can
  make an instance of one, so naming the family adds no call-through-a-value arm. Unblocked moptilities' load (`(:method ((method
  standard-method)) ...)`); `ArgumentShapes` stopped deciding the two generic-function names.
  Residual: `(subtypep 'standard-generic-function 'function)` is `nil t` (SBCL `t t`), the
  funcallable classes' residual below. ci-spec: `clos-slot-options-and-metaobject-types`.
- **Two traps from LAZY seeding**: (a) the preamble's tag list is
  `descendantTags(STANDARD-CLASS)`, and an EMPTY one means "no metaobject can exist" on the
  compile paths but NOT in the interpreter, where the `(find-class 'c)` in the test's own
  argument seeds AFTER the expansion — hence the `liveRegistry` flag, falling back to the
  constant `%class-STANDARD-CLASS` tag; (b) a type SPECIFIER naming a MOP base class is itself
  a seeding trigger (`ensureMopClassesSeededFor`), or `(typep (find-class 'c) 'class)` as a
  program's first MOP form folds to constant nil.
- The built-in `T` class's name slot holds the boolean `t`, not the symbol, so the runtime
  `typep` universal-type arms match BOTH spellings (`universalTypeMatchTest`). `#'class-of`'s
  wrapper is REFERENCE_GATED; `class-name` is a prelude defun over metaobject slot 0.
- **Metaobject slot order is a `%obj-ref` index contract** shared with the closer-mop shim —
  append, never reorder: class = name, direct-superclasses, direct-slots, effective-slots,
  finalized-p (the first superclass of a metaclass descending from `class` opens its layout whatever
  its place: `expandDefclass`'s `layoutPrefix`, for McCLIM's `(presentation-type-info standard-class)`,
  the shifted accessors covering the mixin); slot-definition = name, initargs, initform, type, readers, initfunction (5),
  writers (6, 2026-10-30), allocation (7, 2026-11-01: `:class`, or nil, which
  `closer-mop:slot-definition-allocation` answers as `:instance`; a materialized plain view
  carries `:class` for a `SlotSpec.sharedCellVar` slot -- the compile paths' meta-table row as
  an optional sixth element, so a table without class slots keeps its size), `%location` (8,
  2026-11-05, spelled apart from a user slot-definition class's own slots: "Slot locations").
- **Slot-definition classes** (seeded with the MOP trio): `slot-definition` declares every
  slot; `direct-slot-definition` / `effective-slot-definition` are slot-less children and the
  two `standard-*` classes slot-less grandchildren, so the layout is unchanged. Slot-less too,
  `standard-slot-definition` is a child of the root and the first superclass of both
  (`seedStandardSlotDefinitionClass`, AMOP's precedence; clsql's `slotdef-for-slot-with-class`
  typecases on it, and a compile path refuses a specifier it does not know). All six are `closer-mop`
  externals; access dispatches on `closer-mop:slot-definition` and `direct-slot-definition`.
- **`:type`** reaches a driver-built definition as WRITTEN (`ParsedSlot.writtenType`), a compound
  specifier included, where the static `SlotSpec` keeps t: clsql's `(string 30)` chooses a column
  type. No `:type` written means no initarg, so a slot-definition class's `:around` reads nil as on
  SBCL (clsql's `specified-type`), and the slot's initform gives `slot-definition-type` t. A plain
  view keeps the static name.
- **`:initarg nil`** is accepted (clsql's `sql-database-data-error` writes it), and names no usable
  initarg: `(make-instance 'c nil 9)` leaves the slot unbound where SBCL fills it, and
  `&key ((nil v))` is refused (`LambdaLists.parseKey`; `%ll-key-cell` takes a nil twin for "none").
- **`closer-mop:finalize-inheritance`** of a class outside the protocol is the shim's method on `t`,
  which answers the class: `defclass` finalized it. The protocol's method is on `standard-class`, so
  it wins for every metaobject once the protocol is in the program, the same split as `compute-slots`.
  `compute-class-precedence-list` is `%mop-class-precedence-list` under its AMOP name.
- **A plain view carries direct slots too** (`class-direct-slots`; it used to be nil): the
  interpreter builds them from `ClassInfo.directSlots()`, the compile paths from a 4th
  `%class-meta-table%` column whose element is `(writers . row)` -- or `(writers name)`, a
  reference to the effective row, when the direct `SlotSpec` IS the effective one (an own slot
  nothing shadows). Writers (`ClosRegistry.slotWriters`: `(setf accessor)` then `:writer`
  names, a mangled `%setf-` writer back in list form) fill direct definitions only, AMOP
  5.4.3; the driver passes them as `:writers`. A struct's direct slots stay nil.
- **`allocate-instance`** is IN: an instance of a registered CLOS class with EVERY slot the
  unbound marker — no initforms, no `initialize-instance`; initargs accepted and ignored.
  Interpreter: a registry-backed built-in. Compile paths: `%obj-new` needs a LITERAL tag, so
  `allocateInstanceDefuns` (gated by `needsAllocateInstanceRuntime`; a user defun wins) emits
  one arm per registered class, chunked into `%ALLOC-INST-<n>`, plus the public defun over an
  `or`-chain. It does NOT seed the MOP classes; built-in and struct classes signal.
- `closer-common-lisp` is a resolver-level flat re-export (`.kb/packages.md`).

### Slot locations and methods on `allocate-instance` (McCLIM's dynamic slots)
Since 2026-11-05 on all four backends. McCLIM's `class-with-dynamic-slots` keeps a dynamic variable
in each slot's storage, reached past its own `slot-value-using-class`, and pins the shape.
- **`slot-definition-location`** answers the effective definition's `%location`, the index
  `%obj-ref` takes: a plain view's is its row's position (`classMetaobject`, the struct half,
  `(length %fc_slots)` in the materializer), a driver-built one's is written by
  `finalize-inheritance`, over `compute-slots`' answer in layout order. A class-allocated slot and
  a direct definition answer nil. `compute-slots` no longer reuses an inherited definition whose
  location differs here, the second superclass's slot under multiple inheritance: it computes a
  new one (AMOP makes one per class anyway; one definition held the first class's index). Residual:
  a superclass added at run time (contextl's partial classes) changes `compute-slots`' order and
  not the static layout, so the location stops matching the storage.
- **`standard-instance-access`** is `%obj-ref` with a computed index (`.kb/instance-syntax.md`),
  behind `%instance-location`: AMOP leaves a bad argument undefined, and without the check a
  non-instance traps on WASM. The check comes ahead of `%obj-ref`, whose object is cast first. An
  index past the slots stays unchecked (the interpreter signals, the JVM `ArrayIndexOutOfBounds`,
  WASM traps).
- **A method on `allocate-instance`**: the interpreter's dispatcher stashes the built-in like any
  other. The compile paths' generated defun moves to the stash's name
  (`builtinDefaultMethodName`) when the generic exists, and the dispatcher gets it as fallback,
  where before the generated defun, appended later, overrode the dispatcher and no method ran.
- **`make-instance`** (`ClosRegistry.allocatesThroughGeneric`: such a generic exists and the class
  was defined with a `:metaclass`) calls `(allocate-instance (find-class 'C) initargs...)`, then the
  chain fill, or `%mop-fill-slots` directly without an initialization generic; `%mop-make-instance`'s
  arm does the same. Tracking stays in the default method. Census 2026-11-05: of the cached
  Quicklisp systems, McCLIM's and serapeum's `abstract-standard-class` are the two such methods, both
  on a user metaclass. Residual: a method on `standard-class` or on an `eql` class is not reached
  from `make-instance` of a class without a `:metaclass`; `class-prototype` calls the generic (a
  refusing method refuses it, where SBCL's prototype needs no allocation).
- **What else that load needed**, pinned by the same case: `class-prototype` of a built-in class is a
  value of it (SBCL's where it is a constant), not an allocation; `extract-lambda-list`,
  `extract-specializer-names`; `(find-class 'pathname)` (`BUILTIN_CLASS_NAMES`). A defclass
  whose form seeds a class (the first `funcallable-standard-class`) regenerates the class dispatchers
  BEFORE its driver runs: `shared-initialize` had no arm for the seeded metaclass, so such a class
  kept raw initargs and stayed unfinalized, on the interpreter when a metaclass was in use before.
  A generic function or method instance never fills through the slot-access protocol
  (`fillsThroughSlotProtocol`): its seeded slots start nil with no initfunction to say so.
- **`closer-mop:ensure-class`** is `(eval (defclass ...))` of its initargs, so on the interpreter a
  method can specialize on the class and `make-instance` find it by name, what McCLIM's presentation
  types want. On the compile paths the `eval` defines no class, so `find-class` signals: the class
  set is fixed there (`doc/**/find-class.md`).
- **`forward-referenced-class`** is a seeded metaclass beside `standard-class`: `:metaclass` takes
  its descendants, the system `finalize-inheritance` stays the shim's no-op for one, it gets no
  default superclass, and a class with one among its superclasses is not finalized
  (`%mop-waits-for-a-class-p`). Defined at last it stays the object its subclasses hold, of the
  definition's metaclass, also when moved to another name first, as McCLIM's
  `record-presentation-type` does: `%mop-existing-class` finds it through `find-class`,
  `%mop-change-metaclass` is `change-class` on the interpreter and a no-op on the compile paths (a
  compiled class keeps the layout of its first metaclass; `change-class` in the runtime would give
  every `:metaclass` program its arms), and `ClosRegistry.moveDescendants` makes the static
  descendants of the old name descend from the new one. Residual: a slot the definition brings is
  not in those descendants' layouts (redefinition does not propagate).
  `LispEvaluatorTest.closerMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators`, SBCL 2.2.9
  agrees. McCLIM's own load makes none (measured 2026-11-05 over `clim-core`).
- **A method on `compute-slots` adding a slot** (ESA's `modual-class`) gets it storage:
  `absorbComputedSlots` lists the effective slots neither the form nor its superclasses' static rows
  bring, the `%ABSORBED-SLOTS` of "Layered classes". A method on `make-instance`
  (`constructsThroughGeneric`, a metaclass the method specializes) sees a literal call of such a class
  and every computed one, as `(funcall #'make-instance class ...)`; the default is the system's
  (`%SYSTEM-MAKE-INSTANCE` on the interpreter, `%mop-make-instance` on the compile paths). ci-spec
  standalone `compute-slots-and-make-instance-methods-of-a-metaclass`.
- **`closer-mop:class-default-initargs`** is AMOP's `(initarg form function)` over
  `ClosRegistry.defaultInitargs`: a built-in on the interpreter, the gated `%class-default-initargs`
  dispatch (`%CDI-<n>`) on the compile paths. The readable exports with no operator yet:
  `compute-effective-method`, `specializer-direct-methods`, `specializer-direct-generic-functions`,
  `compute-applicable-methods-using-classes` (a generic with what a program defines, never called).
- Pinned by ci-spec standalone `slot-locations-and-allocate-instance-methods` (SBCL 2.2.9 `sb-mop`
  agrees) and `LispEvaluatorTest.closerMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators`.

### The metaclass protocol (Phase B) and the MOP widening
A `defclass` carrying `(:metaclass M)` — `M` registered and descending from `standard-class` —
keeps its full static expansion and additionally emits ONE
`(%ensure-class-with-metaclass 'name 'M '(supers) (list slot-specs...) (list class-initargs...))`
driver call as its last generated form. Unknown CLASS options become metaclass initargs whose
value is the option TAIL (AMOP canonicalization, SBCL's `(cdr option)`); the option name is ANY
symbol, a non-keyword one passed quoted, and the tail may be dotted -- contextl's deflayer writes
`(original-name . name)` for a slot whose `:initarg` is `original-name` (keywords only until
2026-10-31, ci-spec `defclass-metaclass-protocol`); unknown SLOT options ride the canonical
`(:name .. :initargs .. :initform .. :type .. :readers ..)` spec plist as
`direct-slot-definition-class` initargs.
- The driver + system defaults for `closer-mop:{validate-superclass (permissive t),
  direct-slot-definition-class, effective-slot-definition-class,
  compute-effective-slot-definition, finalize-inheritance, ensure-class-using-class}` are Lisp
  source (`macro/mop-protocol.lisp` via `MopProtocol.forms()`), SELF-CONTAINED over the
  `%obj-ref` index contract, defMETHODs only so user hooks defined before OR after merge into
  the same generics. `finalize-inheritance` runs EAGERLY at definition time (divergence). The
  interpreter's `evalDefclass` loads the protocol once (`ensureMopProtocolLoaded`), covering the
  macro-time evaluator; a compiled program runs the driver call at program start.
- Compile-path gate (`usesMetaclassProtocol` / `namesMopBaseSuperclass`): PREPENDS
  `MopProtocol.forms()` (before the reference scans, so the driver's own `find-class` use
  switches the metaobject runtime on) and appends `seededMopConstructorDefuns`, the generated
  `%mop-make-instance` (chunked into `%MMI-<n>`) and `%register-class-metaobject` (prepends onto
  `%class-metaobjects%` so the driver-built instance shadows the materialized plain view).
- **`ensure-class-using-class` routing**: the driver passes the EXISTING driver-built metaobject
  (nil on first definition), so the system default takes the make path for nil and the
  reinitialize path for a class and a user `:around` fires on REdefinition, per AMOP.
  `:direct-superclasses` crosses as NAMES, resolved inside the chain after user `:around`s may
  munge the list. "Existing" is tracked in the protocol's own `%mop-ensured-classes%` alist,
  deliberately NOT via find-class.
- **Chain-fill initialization of METAOBJECT instances**: for a class whose ancestors include a
  seeded MOP base class, and only when `isMopProtocolActive`, `expandMakeInstance` and the
  `%mop-make-instance` arms allocate UNBOUND instead of calling the keyword constructor, then
  run the initialization generic; the system `shared-initialize` primaries fill via
  `%mop-fill-slots` (slot-names nil = supplied initargs only, non-nil = plus initforms for
  still-unbound slots), which is what makes a user `initialize-instance :around`'s MUNGED
  initargs take effect — so metaobject classes are EXCLUDED from the re-fill replay below.
- **Lite divergence + the initarg RE-FILL repair.** For REGULAR classes, shared-initialize hooks
  run AFTER constructor slot-filling, which IS observable (dao-class's `shared-initialize
  :before` RESETS `direct-keys`). Repair: for a REGULAR class specialized by a `:before` method
  on initialize-instance/shared-initialize (`ClosRegistry.initRefillTargets`, ancestor-inclusive
  via `needsInitRefill`), make-instance re-sets every DECLARED-initarg slot the call supplies
  (`SlotSpec.initargSupplied`; the slot-name-default keyword an `:initarg`-less slot gets is
  deliberately NOT refilled) after the initialization generic returns, leftmost initarg wins.
  Three emission sites, one semantic: `expandMakeInstance` folds it per literal call site
  (`literalKeyword` unwraps both quoted spellings) and `%mop-make-instance` carries
  `%MMI-REFILL` + `%MMI-INIT-TAIL`. **Residual (accepted)**: a specialized PRIMARY without
  call-next-method should suppress the fill entirely, and an `:after` writing a supplied
  declared-initarg slot on a refill-target class would be re-clobbered.
- **Class REdefinition** reinitializes the SAME metaobject (identity survives) and re-finalizes.
  **Divergence**: the INTERPRETER does real redefinition; the COMPILE paths keep the LAST
  definition in the static tables (`classDefunSlots` nil-s the earlier constructor defun; two
  same-name defuns in one class file are a JVM ClassFormatError) while BOTH driver calls run.
  Existing instances are not updated, and a slot whose INDEX changes poisons `slotPositions`.
  The interpreter registers a redefinition from the form plus the PREVIOUS driver-built
  metaobject's class allocations (`withPreviousSlotAllocations`, 2026-11-01) before its driver
  runs: a hook writing a class-allocated slot during the redefinition (contextl's singleton
  `reinitialize-instance :after` for a `:reinitialize t` slot) otherwise wrote the instance slot
  the plain form gives, and the widened re-registration then read the stale shared cell (spx's
  `class1`: 1, SBCL 2). The compile paths emit the widened form, so they always agreed.
- **Slot-definition contract: index 5 = INITFUNCTION** (append-only). The driver builds each
  canonicalized spec with `list` — fresh cells per evaluation, because mito's
  add-referencing-slots `rplacd`s ghost markers into them — carrying
  `:initfunction (lambda () initform)`; the materialized plain views answer nil (the meta table
  is quoted data and cannot carry a live thunk). closer-mop.lisp adds
  `slot-definition-readers` (4), `slot-definition-initfunction` (5), `-writers` (6),
  `-initform` (2), `class-direct-slots` (2),
  `class-direct-subclasses` (interpreter = `directSubclassNames`, metaobject memo FIRST since a
  user `:around` may have INJECTED a superclass; compile paths = a generated defun plus chunked
  `%CDS-<n>` helpers).
- **Metaobject slots are read/written BY NAME, never by `%obj-ref` index**: a user
  slot-definition class may inherit the base through MULTIPLE inheritance with its own mixin
  FIRST (mito's `table-column-class`). The index contract remains valid only for Java/generated
  code that CONSTRUCTS seeded-layout instances.
- **Injected direct superclasses reconcile the STATIC registration**: the driver-built metaobject
  is the truth, so `evalDefclass` re-registers from `widenDefclassToMetaobjectSupers` (its
  superclass list minus the `standard-object` default, plus per-slot `:initarg`s a
  slot-definition `:around` pushed, ONLY onto specs that declare none, plus `:allocation :class`
  where the driver-built direct slot definition is class-allocated and the spec writes no
  `:allocation`, `reconcileSlotAllocations`, 2026-11-01) and
  `UserMacroExpander.widenMetaclassDefclasses` rewrites the EMITTED defclass form the same way
  on the compile paths. The runtime driver re-runs the `:around`, whose contains-checks make the
  injection idempotent. On the interpreter the widened re-registration comes AFTER the driver
  call, so a slot it makes class-allocated gets its cell only then; what finalization stored
  meanwhile went to the prototype's instance slot (contextl's `special-class`
  `finalize-inheritance :after` shifts a class-allocated special slot into a special symbol), so
  `evalDefclass` runs `closer-mop:finalize-inheritance` again when the allocations widened
  (`widensSlotAllocations`); the compile paths emit the widened form, whose cell precedes the
  driver. The allocation case is contextl's singleton layer slots:
  `singleton-direct-slot-definition`'s `initialize-instance :around` defaults `:allocation` to
  `:class`, so the canonical spec passes `:allocation` ONLY when the slot wrote one
  (`ParsedSlot.allocation`) -- an `:instance` default would make that `:around` signal -- and a
  class-allocated slot's `:initform`/`:initfunction` are the WRITTEN initform
  (`ParsedSlot.cellInitform`), not the static spec's read of the shared cell, so contextl's
  `:reinitialize t` re-evaluation reaches the form. Residual: a materialized plain view's
  `slot-definition-initform` of a class slot is still the cell symbol (`SlotSpec` keeps no
  written initform).
- **A driver-built class with no direct superclasses defaults to `(standard-object)`**, per AMOP;
  `standard-object` resolves through find-class ONLY (`FIND_CLASS_ONLY_CLASS_NAMES`). A
  `defclass` may WRITE it as a superclass (contextl's `(defclass special-object
  (standard-object) ())`): `expandDefclass` drops it -- the root every class already has, so
  layout, ancestry and CPL are the empty list's, on every path, the driver call included -- and
  refuses it AHEAD of another superclass (SBCL's precedence error) or twice. Residual:
  `class-direct-superclasses` of a plain class answers nil, never `(standard-object)`.
  `STANDARD-OBJECT`/`STANDARD-CLASS` are in `PackageRegistry.CL_TYPES` — the compiled
  `%find-class` matches SPELLINGS, so a package-local `MITO...::STANDARD-CLASS` otherwise missed
  the seeded entry.
- **The synthesized `shared-initialize` system default FILLS** (it used to return the instance
  unchanged): mito's `make-dao-instance` is `allocate-instance` + `(shared-initialize obj nil
  initargs)`, where the default IS the fill. The interpreter builtin answers an UNREGISTERED
  instance untouched, matching the generated dispatch's fall-through.
- **`slot-exists-p`** (all four backends): true when the layout declares the slot, regardless of
  boundness — interpreter `instanceSlotRef != null`, compile paths a `%obj-is` membership test
  for a literal name and the gated `%slot-exists-p-runtime` for a runtime one.
- **An AMBIGUOUS literal slot name outlines onto the shared runtime dispatch**
  (`expandAmbiguousSlotRead`/`Set` -> `%slot-value-runtime`/`%slot-value-set-runtime`): six such
  reads in `finalize-inheritance` crossed the JVM 64 KB limit at ~250 layouts.
- The end-to-end `(ql:quickload '("mito-core" "dbd-postgres"))` + DAO CRUD run is manual on the
  interpreter, the JVM and the WASM component.

### Definition-time method construction (Phase C)
The `(funcall (compile nil `(lambda () ,code)))` idiom of postmodern's `build-dao-methods`,
where nested `defmethod`s carry the class METAOBJECT spliced as a literal specializer (plus
`(eql (class-name ,class))`).
- The evaluator's `compile` built-in intercepts a NO-ARGUMENT definition containing a defmethod
  and folds the metaobject literals first (`macro/MopEvalCapture`): specializer position -> the
  class name, `(eql (class-name <inst>))` -> `(eql 'name)`, every other occurrence ->
  `(find-class 'name)`. **Load-bearing ordering**: valid during finalization only because the
  driver registers the metaobject BEFORE `finalize-inheritance`.
- The compile paths' MACRO-TIME evaluator records the body into the splice sink
  `UserMacroExpander` attaches (`setMopEvalSpliceSink`); the pass splices the folded forms right
  after the triggering defclass, where `expandLetNestedDefmethods` registers them statically.
- Run-time re-execution goes through the generated `compile` runtime (`macro/CompileRuntime` +
  `compile-runtime.lisp`, gated on a compile reference without a user defun, in the native-image
  resource-config): a defmethod-containing definition answers a do-nothing function, anything
  else is `eval`ed (which gates the eval runtime in) — so a method-defining form built from
  RUNTIME data is silently absorbed, the one soft edge. A `(coerce <form building (lambda ...)>
  ...)` counts as a compile reference (`coercesBuiltLambda`, lisp-unit's `run-code`); a computed
  `'function` coercion calls `compile` on a cons only when the program carries it, so an ordinary
  higher-order `(coerce f 'function)` stays eval-free. A method under a false definition-time guard still registers in the dispatcher;
  calling it falls through to the default or to no-applicable-method (the nested-branch
  guard, todo 445) -- matching the interpreter, where the defmethod never runs.

### Precompiled definitions (`CompiledTemplates`)
A compiled program's `compile` evaluates, and the compiled `eval` binds a runtime lambda's
parameters positionally (`.kb/eval-runtime.md`): contextl's layered-function caller,
`(compile nil `(lambda (&rest rest) (declare ...) (apply (the function ,gf) ...)))`, answered
NIL there. Since 2026-11-01 a definition the program's own definitions compile is compiled ahead:
- The macro-time evaluator's `compile` built-in records every definition that defines no method
  (`setCompiledTemplateSink`); `UserMacroExpander` keeps what a form's REPLAY compiled (not its
  macros' expansion) and emits, ahead of that form, one
  `(%register-compiled-template 'PATTERN #'(lambda (HOLE...) DEFINITION'))` per pattern. The
  PATTERN is the definition with a hole for each datum that is no literal (an object, a quoted
  list or uninterned symbol, a `(function <object>)`) and `(%template-gensym% k)` for each
  other uninterned symbol, k numbering the distinct ones: a match binds each k to one run-time
  uninterned symbol and no two k to the same one, so the definitions are alpha-equivalent. A
  bare symbol is spelled `cl-user::` unless `cl` owns it, since the registration is read again
  in the package where it stands.
- compile-runtime.lisp's `compile` matches a definition against the patterns (`%template-match`,
  a loop in the cdr direction) and calls the first match's factory on the hole values: compiled
  code, closed over the run-time objects. Anything else is still evaluated. The template table
  is a prepended global (`CompileRuntime.globals()`); the gate counts a registration.
- Pinned by `ContextlE2eTest` on the three compile backends (every layered accessor goes
  through it).

### Funcallable instances (`closer-mop:funcallable-standard-class`, djula's templates)
An instance of a class descending from `closer-mop:funcallable-standard-object` IS a function:
`functionp`, `(typep x 'function)` and a `function` specializer answer t (a class specializer still
ranks first), and every call through a value calls what `closer-mop:set-funcallable-instance-function`
installed. Measured against SBCL 2.2.9 (`sb-mop`) over the pinning case: identical but for the
printed instance and the condition an unset instance signals.
- **Seeding is lazier than the MOP base's**: `ensureFuncallableClassesSeeded` registers the pair only
  where a program names one -- `ensureMopClassesSeededFor` by member, the compile path's upfront
  symbol scan (`FUNCALLABLE_CLASS_SPELLINGS`), `seedMopClassesNamedBy` before a defclass -- under
  their closer-mop NAMES (`CLOSER-MOP::FUNCALLABLE-STANDARD-CLASS`, the `JAVA::JAVA-EXCEPTION`
  precedent), so a compiled `find-class` of the spelled symbol matches a table spelling. (The
  plainly-seeded slot-definition classes do NOT: `(find-class 'closer-mop:standard-direct-slot-definition)`
  answers on the interpreter and signals on the compile paths.) FSC is a slot-less child of
  `standard-class`, FSO a slot-less root.
- A `(:metaclass M)` descending from FSC APPENDS FSO to the superclasses when none carries it (AMOP's
  default superclass), after the declared ones so the layout prefix stays; the driver call gets it
  too and `widenDefclassToMetaobjectSupers` skips it like `standard-object`. The metaclass protocol
  runs as for any `:metaclass`.
- **Storage**: `registerClass` gives every FSO descendant `LispLayout.asFuncallable()` -- the
  `funcallable` flag plus two cells past the declared slots: `FUNCALLABLE_CELL` (-2) holds the
  function, `TAIL_CELL` (-1) stays a Gray input stream's pushback. Counted from the end, so a
  change-class target's reserved room keeps the index; the interpreter's `becomeLayout` carries the
  last TWO cells when it grows the array.
- The shim's `set-funcallable-instance-function` stores into -2 after a `typep` check (a type-error
  for a non-funcallable instance or a non-function); a funcallable instance handed over as the
  function is wrapped in a lambda, so the cell always holds a plain closure and every backend unwraps
  ONE level.
- **Calls**: interpreter `LispEvaluator.apply` and `Environment`'s `functionp` read the layout flag.
  JVM `JvmFuncallableInstances`: in each shared dispatcher's classification (and the eval runtime's
  `_apply`), an `Object[]` whose slot 0 is no funcId -- the not-a-function path, so a function value
  pays nothing -- is tested for a funcallable layout TAG (text, like `%obj-is`) and re-enters the
  classification as its cell; `functionp` adds the tag test. WASM `WasmFuncallableInstances`: the
  prologue arm right after the closure test (EH: replace and `br` to the closure path only when the
  cell holds a closure, else the report names the instance; trapping module: replace unconditionally
  before the cast) and in the function-namespace probe; `_apply` hands a non-closure to the spread
  dispatcher, so it needs none; `functionp` adds the layout-address test. Every arm is gated on
  `ClosRegistry.funcallableTags()` (wasm: the baked ones), so a program defining no funcallable
  class is byte-identical.
- Residuals: `(subtypep 'a-funcallable-class 'function)` answers nil (SBCL t) -- the class lattice
  does not reach `function`; an unset instance signals the not-a-function type-error (SBCL its own
  `unset-funcallable-instance-function`).
- Pinned by ci-spec standalone `funcallable-instances-call-their-installed-function` (standalone:
  the arm joins every dispatcher of its program).

### Classes made at run time (contextl's layer contexts)
A class metaobject a program makes AFTER the static tables were built --
`(make-instance 'standard-layer-class :direct-superclasses (list a b))`, contextl's
`adjoin-layer-using-class` -- has no tag, layout or ancestor set, and a static `%obj-is` tag list
cannot name it. Since 2026-10-31 such a class works on all four backends:
- **Its instances** (`allocate-instance`, so the shim's memoized `closer-mop:class-prototype`) are
  `%DYNAMIC-OBJECT%`s (`ClosRegistry.DYNAMIC_OBJECT_TAG`, a LAYOUT ONLY like the unbound marker):
  slot 0 the class metaobject, slot 1 its class precedence list (CLHS 4.3.5, computed over the
  metaobjects' `direct-superclasses`, memoized per class). No slots of their own.
- **"Made at run time"** = a class metaobject `find-class` does not answer for its own name
  (`ClosRegistry.isDynamicClassMetaobject`, `%dynamic-class-p`); an anonymous one names nil, which
  the type tables read as the EMPTY type -- before this, `(subtypep anon x)` answered t.
- **The runtime** is Lisp (`macro/dynamic-classes.lisp`, `DynamicClasses.forms()`, in the
  native resource config): the compile paths PREPEND it when the program carries a `:metaclass`
  defclass AND an `allocate-instance` reference (`expandTopLevelDefinitions`, beside
  `MopProtocol.forms()`), turning `ClosRegistry.enableDynamicClasses()` on; the interpreter loads
  it at the first such class (`ensureDynamicClassesLoaded`: allocate-instance or subtypep met one)
  and regenerates every dispatcher with a class method. Every program without the gate is
  byte-identical. It uses no prelude defun (`class-name` is one: a prelude splice ran before the
  prepend, so the forms read slot `name` instead).
- **Dispatch** (`withDynamicDispatchArm`): a dispatcher whose generic has a CLASS specializer
  (standard combination only) tests those parameters for the dynamic tag; if one holds, each
  method's entry is computed at run time (`dynamicMethodEntry`: `(qualifier function key...)` or
  nil, keys 0 for eql, 10000 + CPL position for a class over a dynamic instance, static rank x 1000
  otherwise, 1000000 unspecialized), and `%dynamic-dispatch` runs the standard combination over
  the stable-sorted entries. With no applicable primary the static body answers. Pinned with
  `:around`/`:before`/`:after`, `call-next-method`, an eql specializer on another argument. The
  entry's `(find-class 'C)` names the REGISTERED spelling (`registeredClassSpelling`): a
  specializer holds the normalized `PKG::NAME`, which the compile paths' class table does not
  carry for a class an exported symbol names, so until 2026-11-01 such a method signalled
  `FIND-CLASS: there is no class named C` on the three compile backends (contextl's
  `layered-function`, once a layer-context prototype reached `print-object`).
- **class-of** reads slot 0 (`expandClassOf`, the interpreter built-in); **typep**: a class test
  (`makeClassInstanceTest`, conditions excepted) and both runtime typep shapes (`%typep-runtime`,
  `expandRuntimeTypep`, ahead of the metaobject normalization) consult the CPL, and the
  `standard-object` enumeration carries the tag; **subtypep** with a dynamic class on either side
  goes to `%dynamic-subtypep`; **allocate-instance**'s generated defun gains the arm.
- **print-object** reaches one (2026-11-01): `ClosRegistry.printObjectTags` adds the dynamic tag
  when a `print-object` method has a class specializer, so the printer routes the instance and
  the dispatcher's arm picks the method by its precedence list (contextl's `#<LAYER NIL>` for a
  layer-context prototype); with none applicable the system method prints
  `#<%DYNAMIC-OBJECT% ...>`.
- Residuals: `make-instance` of a class made at run time and its slots are out; a short-form method-combination generic gets no arm; a `call-next-method` past the
  last user primary does not reach a built-in default method.
- Pinned by ci-spec standalone `classes-made-at-run-time` (standalone: the arm joins every
  dispatcher with a class method).

### The slot-access protocol (contextl's special slots)
AMOP 5.5, since 2026-11-01 on all four backends: with a method on
`closer-mop:slot-value-using-class`, its `setf`, `slot-boundp-using-class` or
`slot-makunbound-using-class` in the program, or a call of one (Drei's `circle-subst`, ci-spec
`slot-access-generics-called-with-no-method`; `SlotProtocol.isProtocolGenericName`: the
closer-mop symbol, which contextl's unqualified names resolve to since the three are
closer-mop externals), `slot-value` (a computed name too), its `setf`, `slot-boundp`,
`slot-makunbound` and the accessors of an instance of a class defined with a `:metaclass` call
the four generics with the class metaobject, the instance and the effective slot definition.
- **The runtime** is Lisp (`macro/slot-protocol.lisp`, `SlotProtocol.forms()`, in the native
  resource config): the system methods, specialized on `standard-class`, read and write the
  instance's own storage through `%slot-value-runtime`/`%slot-value-set-runtime` (a
  class-allocated slot's cell included), an unbound read calling `slot-unbound`; NEVER through
  `slot-value`, whose expansion would route back. `%slot-protocol-instance-p`: `(class-of
  (class-of x))` is not `standard-class`. The compile paths prepend it beside `MopProtocol`
  (gate `namesSlotProtocolGeneric`: the program names a protocol generic, which also brings the
  metaclass runtime), `ClosRegistry.enableSlotProtocol()` before the walk, and the runtime-name
  dispatch gates (`needsRuntimeSlotNameDispatch`/`needsRuntimeSlotSetDispatch`) answer true; the
  interpreter loads it at the first such `defmethod`, or the first call through a loading stub
  (`ensureSlotProtocolLoaded`, `SlotProtocol.ENTRY_NAMES`). Every other program is byte-identical.
- **Routing** (`slotProtocolRoute`): `(let ((o obj) [(n name)] [(v value)]) (if
  (%slot-protocol-instance-p o) (%slot-protocol-value o n) <the static expansion over o>))` in
  `expandSlotValue`, the `expandSetf` `slot-value` place (its static half is
  `expandSlotValueSet`), `expandSlotBoundp`, `expandSlotMakunbound`; the static half is the
  `...Direct` builder, never the surface form again (that would expand forever). A generated
  reader/writer of a `:metaclass` class (`protocolReadBody`/`protocolWriteBody`) routes the same
  way when the gate is on as the class is defined. The interpreter's Java `slot-boundp` and
  `slot-makunbound` builtins and `evalSlotValue`'s computed-name path test the instance and call
  the Lisp entries.
- **Construction** (2026-11-01): `make-instance` of a class defined with a `:metaclass`
  (`ClosRegistry.fillsThroughSlotProtocol`, `markMetaclassed`) fills through the protocol, CLHS
  7.1.4 -- an initarg's leftmost value, then a still-unbound slot's initfunction, each by
  `(setf slot-value-using-class)` after `slot-boundp-using-class` (`%slot-protocol-fill`, SBCL's
  order). With an initialization generic in the program it is the metaobject classes' CHAIN
  FILL (`expandMakeInstance`: allocate unbound, run the generic), and `%mop-fill-slots` (the
  generated defun, the interpreter builtin) hands a protocol instance to `%slot-protocol-fill`,
  so a user `shared-initialize` that never calls the next method (contextl's on
  `special-object`) is the only fill, as in CL; with none, the unbound allocation is followed
  by `%slot-protocol-fill` directly.
- **An inherited initform** (2026-11-05): the definition of a slot a plain superclass declares is
  a plain view with no initfunction, so the fill left that slot unbound on all four backends. A class
  filling through the protocol runs `%slot-protocol-inherit-initfunctions` after its driver with a
  thunk per such slot. SBCL 2.2.9 agrees.
- Residuals: a class whose precedence list carries `:default-initargs` keeps the constructor
  fill (`markDefaultInitargs`: the protocol fill reads the driver-built initfunctions, which
  know no default initargs) and then re-fills through the protocol inside `shared-initialize`
  (extra writes, same values); a `make-instance` of a COMPUTED class takes the chain fill in
  `%mop-make-instance` too, but with no initialization generic in the program its compiled
  arm keeps the constructor (the interpreter re-enters `expandMakeInstance`, so it fills
  through the protocol there).
  `slot-exists-p` is not routed; an interpreter accessor defined before the first protocol
  method is not routed; an inherited plain class's accessor is not routed for a `:metaclass`
  subclass instance.
- Pinned by ci-spec standalone `slot-access-protocol-methods` (SBCL 2.2.9 `sb-mop` agrees) and
  `ContextlE2eTest` (special slots, `dletf`, captured dynamic environments).

### The generic-function protocol (method metaobjects at run time)
Since 2026-11-01 on all four backends (`macro/generic-functions.lisp`, `GenericFunctions`):
`ensure-generic-function`, `add-method`/`remove-method` (generics, so contextl's `:after`
methods merge), `find-method`, `method-qualifiers`, `compute-applicable-methods`, closer-mop's
`ensure-method`, the
`generic-function-*`/`method-*` readers, `intern-eql-specializer`/`eql-specializer-object`.
- **The decision** (h89 item 1, measured 2026-11-01): first-class generic function and method
  METAOBJECTS with a run-time method table the existing dispatchers consult, NOT a narrow
  `ensure-method` over the dispatcher. What the consumers call settled it: contextl's layered
  accessors make generics with `:generic-function-class 'layered-function`, read
  `generic-function-method-class`, and apply the generic object; its layer GC is an
  `add-method :after` on `(layered-function layered-method)` reading `method-specializers` and
  `eql-specializer-object`; mito's relational readers and rutils `add-method` a
  `(make-instance 'standard-method :function <closure>)` -- a closure, which no definition-time
  capture of a `defmethod` form can serve. Every generic's value stays the compiled dispatcher.
- **Gate** (`usesGenericFunctionProtocol`): a program naming one of the operators (quoted data
  included -- `(fboundp 'find-method)` answers t as on the interpreter) or a `defgeneric` with
  `:generic-function-class`/`:method-class`. The compile paths prepend the runtime (with the
  run-time-classes one, whose `%dynamic-dispatch` combination and `%class-key` it uses) and
  `enableGfProtocol()` seeds the method metaobject families with constructors; the interpreter
  binds a loading stub per operator (`ENTRY_NAMES`, unbound again before the runtime loads, or
  `add-method`'s dispatcher would stash a stub as its built-in default and recurse) and loads at
  a `defgeneric` naming metaobject classes or a method on `add-method`/`remove-method`/
  `compute-applicable-methods`. The prelude's signalling `remove-method` is gone. Every other
  program compiles as before.
- **A generic function metaobject** is a funcallable instance (`generic-function` descends from
  `funcallable-standard-object`) made on first request and memoized by name (`%gf-metaobjects%`,
  keyed by the dispatcher's symbol, `%setf-` for a setf name); its `%STATIC` slot holds the
  compiled dispatcher (nil for a generic made only at run time) and its function calls that, or
  dispatches the run-time methods alone. `ensure-generic-function` installs it as the name's
  function, so a COMPUTED `fdefinition` answers it (contextl's `ensure-layered-method`); the
  compile paths' direct calls, `#'g` and a literal `fdefinition` keep the compiled function, and
  the interpreter's `defineDispatcher` re-installs the metaobject over each regenerated
  dispatcher, updating `%STATIC`.
- **Dispatch** (`withDynamicDispatchArm`): every dispatcher reads `(%gf-runtime-methods 'G)`
  first; nil keeps the compiled body. With run-time methods every method's entry is computed --
  a `defmethod`'s by `dynamicMethodEntry`, whose class keys then become the class's position in
  the argument's precedence list (`%class-key`, over the class metaobjects' direct superclasses,
  memoized like a dynamic class's), a run-time method's by its specializer metaobjects
  (`%gf-runtime-entry`; a built-in class ranks from `BUILT_IN_TYPE_RANKS`, the TYPE ranks
  `specializerRank` uses, handed over by `classKeyRanksForm`) -- and `%dynamic-dispatch` runs the
  standard combination. A run-time method's function is AMOP's `(args next-methods)`; its next
  methods are method metaobjects (`%gf-next-method` wraps the rest of the chain), so
  `(funcall (method-function (car next-methods)) args (cdr next-methods))` works.
- **A `defmethod` reaching `add-method`** (h89 item 3): on a generic whose `defgeneric` named
  `:generic-function-class`/`:method-class` (`%gf-declare` records it), each `defmethod` -- a
  `defgeneric`'s `(:method ...)` options included -- is followed by `%gf-static-method-added`
  (`LispMacroExpander.staticMethodAdded`, the same form on every path): a method metaobject of
  the generic's method class with the method's specializers (class metaobjects, eql
  specializers), qualifiers, lambda list (`GenericInfo.methodLambdaLists`) and a function over
  its defun; its `%STATIC-KEY` keeps it out of the run-time table, so `add-method` only records
  it and runs the user's methods. contextl's layer GC clears its caches from there.
- **An eql-selected argument takes the run-time combination** whenever the arm exists (dynamic
  classes or the protocol) and the generic combines (a qualifier or `call-next-method`): the
  static eql branch admits only same-specialized methods, so contextl's
  `(eql (find-layer-class 'bar))` `:before` lost the class primary ("No applicable method",
  layer-gc section 7).
- **`make-instance` with a user method** (contextl's refusal for a singleton class): on the
  compile paths its dispatcher, which `#'make-instance` reaches, defaults to
  `%mop-make-instance` (the run-time construction) when that is emitted; `make-instance` is
  `NOT_SHADOWABLE`, so nothing else gave it a default and every other class signalled.
- **A method function from a lambda expression** (`ensure-method`, contextl's layered accessors):
  `%gf-method-function` compiles `(lambda (args next) (apply #'<expression> args))`, inside an
  `flet` of `call-next-method`/`next-method-p` only where the expression names one. Its own
  variables are gensyms and `%gf-call-next` goes in as a function OBJECT: the definition spells
  no symbol of this runtime, which a precompiled definition would read in the program's package
  ("Precompiled definitions").
- **`find-method`/`remove-method` take the generic's function** (`%gf-designated`): the metaobject
  whose `%STATIC` it is, nil while it has none, so no method was added at run time. Drei's
  `lisp-syntax-swine.lisp` finds and removes a method through `#'name` before redefining it.
- **`compute-applicable-methods`** (2026-11-05): the default method runs the metaobject's methods
  and `%gf-static-methods` through `%gf-runtime-entry` and sorts them as `%dynamic-dispatch` does,
  newest first between equals as SBCL has them. `%gf-static-methods` is what only a dispatcher knows
  (`LispMacroExpander.staticMethodsForm`): `(order method...)`, evaluated per call by a builtin on
  the interpreter, an `(eq gf #'g)` dispatch generated on the compile paths, with arms only in a
  program naming `compute-applicable-methods` (they keep every generic alive). A generic naming its
  metaobject classes answers nil there: its metaobject lists its methods already. One metaobject per
  method's function (`%gf-static-method-objects%`). No dispatcher consults it, so a method on it
  changes only what its caller sees (McCLIM's `:around`, "Out of scope"). Consumers: Drei's
  `print-object` of a lambda list, coalton's `derive-methods`, `access` (over a metaobject; its
  function arm below). A function that is no generic answers nil where SBCL signals. A generic
  whose package exports its name had no arm until 2026-11-06 (the walk records its dispatcher
  under the normalized `PKG::NAME`, the generic keeps `PKG:NAME`), so it answered only its
  run-time methods: fset's `compare` answered 0 for `(1 2)` on all three compiled backends.
- **`:argument-precedence-order`** (`GenericInfo.precedenceIndexes`): a generic's methods are
  ordered by their specializers compared in that order, by the static dispatchers
  (`specificityOrder`, `branchSpecificityOrder`) and in the run-time keys (`dynamicMethodEntry`,
  `%gf-precedence-indexes`); contextl puts a layered function's layer argument LAST, so demo3's
  `:around` ran in the wrong order before (2026-11-01). Pinned by ci-spec
  `clos-defgeneric-defmethod-eql-dispatch` (`clos-apo`, SBCL agrees).
- Residuals: `remove-method` of a `defmethod`'s method drops only the metaobject (the compiled
  dispatcher keeps it); a short-form method combination generic ignores run-time methods; a
  standard generic's `generic-function-methods` lists only the methods added at run time. A
  generic with run-time methods computes every applicable method's entry on every call (no
  effective-method cache): the figure editor's 1000 runs do not finish in minutes.
- Pinned by ci-spec standalone `generic-function-protocol` (SBCL 2.2.9 + closer-mop agrees) and
  `ContextlE2eTest` (contextl's `test/layer-gc.lisp` at top level).

### Layered classes (contextl's `define-layered-class`)
On all four backends since 2026-11-01 (h89 item 1): layered accessors, partial classes, special
slots a later definition adds. What it took, besides the generic-function protocol:
- **Partial classes**: each `define-layered-class C :in L` is a top-level `defclass` of C whose
  driver, through contextl's `initialize-instance`/`reinitialize-instance :around`, makes an
  ANONYMOUS slot-carrying class per layer (`make-instance defining-metaclass`) and puts it in
  front of C's direct superclasses. `widenDefclassToMetaobjectSupers` reconciles the static side:
  `%ABSORBED-SLOTS` (the anonymous classes' slots, static only, accessors generated) and
  `%STATIC-SUPERCLASSES` (the named classes of the metaobject's precedence list, minimal; also
  mito's injected superclasses). **The driver keeps the WRITTEN superclasses**: the compile paths
  emit the widened form, and while it replaced them the run-time driver handed contextl
  `(partial-object)` instead of `(info-mixin)`, losing the class a layer adds (demo3's `city`,
  "no applicable method", 2026-11-01).
- **The metaobject side** (mop-protocol.lisp): `finalize-inheritance` finalizes an unfinalized
  superclass first (an anonymous one never was) and takes `compute-slots`, a generic
  (contextl's `special-class :before` keeps the previous slots), which computes a slot two
  classes define from every definition (CLHS 7.5.3; a layer adds `:layered` to a slot);
  `reinitialize-instance` of a finalized class finalizes it and every class the protocol
  defined over it (`%mop-refinalize`); `closer-mop:class-precedence-list`. A redefinition
  re-primes the `find-class` memo BEFORE `reinitialize-instance`: the finalization inside it
  reaches the prototype through `class-of`, which answered the plain `standard-class` view after
  the static re-registration, so contextl's singleton `:after` wrote a special slot's cell
  directly (spx's `layer3`, `SYMBOL-VALUE expects a symbol, got 2`).
- **`:default-initargs`** (CLHS 7.1.3) along the precedence list (`ClosRegistry.defaultInitargs`)
  in `expandMakeInstance` and `%mop-make-instance` (`%MMI-APPEND-DEFAULTS`): contextl's
  `partial-class` defaults `:defining-metaclass`.
- **`slot-definition-name` is the slot's own symbol** (the driver's `:name`,
  `quoteOf(slot.name())`; every lookup folds it to the base name): it was the base name homed in
  `cl-user` on all four, so a precompiled accessor's `'name2` read `contextl-user::name2` and
  never matched. Pinned by ci-spec `slot-access-protocol-methods` (`sap-other::v`).
- **Multiple-inheritance refinement over an exported class name**: `miBranchRep` keys
  normalized names (the partial-class `:around` of a class `contextl` exports never ran).
- **`call-next-method` a macro builds**: the figure editor's `(call-and-update call-next-method
  object)` expands to the call, inside `with-inactive-layers`' closure. The compile paths expand
  user macros before a method is registered; the interpreter expands the body's user macros
  first when it names `call-next-method`/`next-method-p` (`withNextMethodMacrosExpanded`).
  `#'call-next-method` as a VALUE is a closure (`rewriteNextMethod`): the next method over its
  arguments, the method's own when it gets none.
- Residuals: a layered class defined inside a function or an `eval` (no static side on the
  compile paths); `fe`'s full run time (above).
- Pinned by `ContextlE2eTest` (demo3's classes, a special slot added to an existing instance,
  `slot-value-using-layer` over a `:layered` slot, the figure editors' `call-next-method`).

### Instance updating (CLHS 4.3.6)
A redefinition that changes a class's local slots, or `make-instances-obsolete`, updates the
existing instances through `update-instance-for-redefined-class` (to `shared-initialize` with
the added slots): contextl's `special-object` `shared-initialize` makes an added special slot
special, and its `finalize-inheritance :after` makes the instances obsolete when a slot turns
special.
- **Interpreter**: its layouts change with a redefinition. `LispEvaluator.trackInstance` keeps
  a weak reference to every instance of a `:metaclass` class; after the outermost `defclass`
  (`classDefinitionDepth`) each instance of a class whose layout changed, or that a
  `make-instances-obsolete` named (with its subclasses), moves to the new layout by slot name
  (`LispInstance.replaceStorage`) and is updated. Added slots are the LOCAL ones.
- **Compile paths** (2026-11-01): a class has its LAST definition's layout from the start (the
  earlier constructors are dropped), so an instance made before a redefinition already has the
  storage; what it lacks is the initialization. A `:metaclass` class defined more than once at
  top level (`trackRedefinedClassInstances`) has its instances, and its subclasses', recorded at
  allocation (`%track-instance` around every allocation site) until its last definition has run
  (`%end-instance-tracking`, then nothing is retained); `%update-tracked-instances` compares the
  effective slots before and after a refinalization (CLHS 4.3.6.1: added local slots, discarded
  ones) and updates each instance of the class. Its property list is EMPTY: a slot the last
  definition dropped never had storage in the instance (the plist read first went through
  `%slot-value-runtime`, which such a program does not carry: "undefined function").
- **`update-instance-for-redefined-class` is a system generic** (`CALLABLE_SYSTEM_GENERICS`,
  `synthesizeUpdateInstanceDefault`): the default primary is `shared-initialize` of the added
  slots, synthesized at the first user method or call. It was a prelude `defun`, which a user
  `:before` method shadowed ("No applicable method", 2026-11-01).
- **`%mop-class-finalized`** is the hook `finalize-inheritance` calls: the compile paths generate
  it from what the program carries (`mopClassFinalizedDefun`), the interpreter defines its own.
  A `(boundp '%dynamic-cpls%)` probe in mop-protocol.lisp put the eval runtime into every
  `:metaclass` program (a `boundp` the injected runtime carries escapes `CompileTimeBoundp`,
  which ran before the injection): measured on a two-slot `:metaclass` class, the JVM class
  carried `_eval`/`_store`/`_genv` until the hook replaced it.
- **Scoped out on the compile paths, by measurement**: `make-instances-obsolete` stays a no-op
  there (the prelude's), and an `eval`'d `defclass` defines no class. Their one consumer is
  contextl's `special-slots-1/2/3`, whose handler `eval`s the redefinition that makes a slot
  special: on the JVM it produces no output in 120 s, the handler's `continue` re-entering the
  `dletf`'s check (2026-11-01). A top-level redefinition making a slot special would need the
  obsolescence; nothing in contextl or its tests does that.
- Pinned by ci-spec standalone `instance-updating-of-a-redefined-metaclass-class` (a slot added
  and one dropped, a user `:before` method; SBCL 2.2.9 agrees) and `ContextlE2eTest`: the shared
  exercise's special slot added to an existing instance (all four),
  `anEvaluatedRedefinitionMakesAnExistingInstancesSlotSpecial` (interpreter).

### Registry-growth lesson
The RUNTIME-slot-name dispatch used to be inlined per call site and grows with every layout
times its slots; five extra ci-spec classes pushed a corpus dolist body past the JVM's SIGNED
16-BIT branch encoding (32 KB, hit before the 64 KB method cap). It is now outlined into the
shared `%slot-value-runtime`/`%slot-boundp-runtime`/`%slot-value-set-runtime` defuns,
CHAINED-CHUNKED by cons-node budget (`chainedDispatchDefuns`: overflow arms call
`%SVR-<n>`/`%SBR-<n>`/`%SVW-<n>`; a dispatch that fits stays one defun, byte-identical to the
pre-chunking shape). Top-level compile crashes name the offending form (`JvmLispCompiler`
chunk-loop wrapper).

## Out of scope / known gaps
- An eql-specialized qualified method combines only with same-specializer primaries + the
  default method.
- Still OUT of the MOP (classes are compile-time-static; `--optimize` DCE and the dispatch
  tables depend on it): runtime class construction beyond `make-instance` of a metaclass
  ("Classes made at run time": `ensure-class` from computed data, a non-top-level `defclass`, a
  class made at run time with slots), `update-instance-for-*`.
  Methods made at run time are IN ("The generic-function protocol"). Class REdefinition of a
  statically-known name is IN; redefinition from computed data is out.
- **Known static-model seam**: on the compile paths `find-class`/`class-of` see the WHOLE
  program's classes regardless of form order, while the interpreter only knows classes already
  defined at call time. `change-class` is the ONE runtime exception and is not MOP.
- **A generic's VALUE is still a plain function** (`#'g`, a literal `fdefinition`): access's
  `(etypecase fn (standard-generic-function (compute-applicable-methods ...)) (function ...))`
  takes the function arm and calls it, trivia's `unary-function-p` its test-call arm. Consumers
  of the rest, measured 2026-10-31 in the local
  Quicklisp cache: moptilities' method side (`method-name`, `get-method`, `map-methods` --
  nothing loaded calls them), cl-store, trivial-arguments, documentation-utils, clunit2 and
  in-nomine (`find-method` of a `defmethod`'s method, which only a generic naming its
  metaobject classes lists).
- **McCLIM, 2026-11-05, interpreter**: `(ql:quickload :clim)` loads (`clim-core`, whose `Core/**` is
  geometry, sheets, streams, presentations, commands, panes, frames, gadgets, dialogs, and
  `drei-mcclim`), in under 3 minutes at a load average of 380. A 46-form exercise answers what SBCL
  2.2.9 answers on the same sources, but for the address in an unreadable object and the wording of
  five errors both signal: a user's presentation types, one inheriting a type defined later,
  `presentation-typep`/`-subtypep`, `present-to-string`, `accept-from-string` (`member`, `or`, `real`,
  `symbol`, `expression`, `form`), a command table and `define-command`, `define-application-frame`,
  regions, transformations, text styles, colors, and in Drei a buffer, marks, `lisp-syntax`'s
  `update-parse`, `form-around` and `form-to-object` of `(defun f (x) (+ x 1))`. Not run: anything
  needing a port. No dispatcher consults `compute-applicable-methods`, so its `:around` removing
  the `standard-object` methods for a presentation-type key has no effect; Core's one such method is
  `presentation-typep`'s, whose body answers `t` for a key of that kind as the `t` method does.
  What it took: `built-in-class` above, `define-method-combination` (ESA's `utils.lisp`), `absorbComputedSlots`
  and methods on `make-instance` (Drei's `modual-mixin`), `change-class` honoring initargs, a `macrolet` body run as top-level forms
  (`LispEvaluator.evalMacroletForm`, CLHS 3.2.3.1: a `define-presentation-type` and its methods in one
  expansion, where SBCL's `load` and `compile-file` both expand a member after the one before it;
  the compile paths walk in that order and run the compile-time half afterwards, as
  `compile-file` of a `defclass` gives no `find-class`), `read-preserving-whitespace`,
  `class-default-initargs`.
- **contextl, 2026-10-31**: `(ql:quickload :contextl)` LOADS on all four backends, its layer GC
  included, and `deflayer`, `define-layered-function`/`-method` (`:in` a layer, qualifiers,
  `call-next-method`), `with-active-layers`/`with-inactive-layers`, `ensure-active-layer`,
  `active-layers` and `layer-active-p` answer what SBCL does -- `ContextlE2eTest` (vendored
  contextl + lw-compat, in `contextl-user`). Cleared that day: `(eql (find-class 't))`, a COMPUTED
  eql specializer, any class option under a metaclass, closer-mop's `subclassp`,
  `class-prototype`, `required-args`, an `:after` method dropping the primary's secondary values
  and the interpreter's `#'generic` doing the same (both lost `adjoin-layer-using-class`'s
  `cacheablep`), and the classes made at run time above. `--feature cx-disable-layer-gc` is the
  spelling of contextl's documented switch: a `(push :cx-disable-layer-gc *features*)` in the
  program reaches only that program's own `#+` (`.kb/reader-features.md`), never a loaded
  system's component files. Its own `test/` (measured 2026-11-01, in package `contextl`) passes
  whole on the interpreter -- `demo3`, `special-slots-1/2/3`, `spx`, `layer-gc`,
  `grouped-layers`, `dynenv`, `partial-initargs`, and the figure editors `fe`/`fe2` at 3 runs
  instead of 1000 ("Layered classes", "Instance updating") -- and on the three compile backends
  but for `special-slots-1/2/3`, whose handler `eval`s the class redefinition (a compiled `eval`
  defines no class: measured on the JVM, no output in 120 s -- the handler's `continue` re-enters
  the `dletf` check forever) and `layer-gc`'s `(loop repeat 2 do ...)` of definitions, which the
  compile paths refuse ("DEFCLASS is only supported as a top-level form"; `ContextlE2eTest` runs
  its body once at top level). The full 1000-run `fe` is a benchmark: on the JVM its untimed and
  timed non-layered halves took 172 s each and the layered half did not finish in the rest of
  500 s (layered functions dispatch their run-time methods afresh on every call).
  `ContextlE2eTest` carries `grouped-layers` (`slot-definition-allocation` and the `:allocation`
  reconciliation above), `dynenv` (the slot-access protocol), `spx`, `layer-gc`, `demo3` and the
  figure editors' `call-next-method` shapes. On the compile paths `dynenv` first needed
  `assert`'s default message to double the tildes of the test it quotes: a test holding a
  `dletf`'s expansion carries contextl's `cerror` control string, whose `~S` failed the static
  format check (`.kb/pure-builtin-fold.md`). `contextl-user`/`cxcl` work on
  all four since the same day: cxcl's `#.(loop for sym being the external-symbols of ...)`
  export list was empty (the LOOP clause iterated nothing, `.kb/loop-iteration-heads.md`), and
  past it a `nil`/`t` export, an exported `make-package`, a `cl` internal under a package using
  only cxcl (`.kb/packages.md`) and `layer-makunbound`'s `(setf (find-class x) nil)` /
  `(setf (class-name c) nil)` (below) each stopped the compile. Until 2026-11-01 the
  interpreter printed the symbols of a top-level form that activates a layer qualified
  (`CONTEXTL-USER::INFO-LAYER`): `map-symbol`'s `with-standard-io-syntax` print regenerated the
  print-object renderer under `cl-user` (`.kb/pretty-printer.md`, "Interpreter trap").
- **lisp-binary, 2026-10-31**: `(ql:quickload :lisp-binary)` LOADS on all four backends, the
  WASM pair with `--feature lisp-binary/no-cffi` (its cffi dependency needs `ffi:`, which no
  `.wasm` has). Cleared that day: the Gray `fundamental-binary-stream`/`-character-stream`
  classes (`.kb/gray-streams.md`), `asdf::*asdf-version*` (`.kb/asdf.md`), `defclass`'s value
  (above), a user macro in a lambda-list default on the compile paths
  (`.kb/defmacro-backquote.md`) and the slot NAME below. A `defbinary` struct (a 16-bit unsigned
  and an 8-bit signed field) then round-trips through `write-binary`/`read-binary` over
  flexi-streams' in-memory streams on all four, octets `(2 1 254)`. Not measured further (bit
  streams, the README's 7-bit compressor, its own test suite).
- **`(setf class-name)`** renames the METAOBJECT (its `name` slot), AMOP's answer, and leaves the
  class table alone: `expandSetf`'s `CLASS-NAME` place is `(setf (slot-value c 'name) v)`, the
  closer-mop shim carries `(setf closer-mop:class-name)`. ci-spec `classes-made-at-run-time`.
- **A slot-definition metaobject's name is the slot's SYMBOL** (`SlotSpec.name`, package and
  all), not the package-stripped `baseName`: `ClosRegistry.slotDefinitionInstance` and the
  compile paths' class meta table (`LispMacroExpander.slotDataRow`). Until 2026-10-31 it was the
  bare name, so `(eq (slot-definition-name s) 'p::x)` was NIL for a class defined in package `p`
  on all four (SBCL: T), and lisp-binary's `bind-class-slots`, binding those names around a body
  that reads `lisp-binary::stream-symbol`, died "unbound" while a `defbinary` expanded. Slot
  LOOKUP stays by base name. Pin: ci-spec `slot-value-with-a-package-qualified-runtime-slot-name`.
- eql specializers on strings. The `:type` slot option is RECORDED (`SlotSpec.type`, `"t"` when
  omitted; a checking no-op). Compiled runtime `eval`: generated functions are callable;
  defining classes/methods or using `make-instance`/`slot-value` inside `eval` is not
  (doc/en/guides/eval-limitations.md). `--no-gc` rejects via its generic top-level error.
- `defclass`/`defgeneric`/`defmethod` are in `PackageRegistry.CL_SPECIAL_FORMS`;
  `make-instance`/`slot-value`/`with-slots`/`with-accessors`/`change-class` in `CL_MACROS` —
  pinned in ci-spec (`rontolisp-package-introspection`), the three backend tests and the doc
  pages; update all together.

## Tests
Core: `LispEvaluatorTest#defgeneric*`/`defclass*`/`defmethod*`/`closInUserPackage`,
`JvmLispCompilerTest#compileAndRunDefgeneric*`/`compileAndRunDefclass*`/
`compileAndRunMacroCallingGenericAtExpansionTime`/`compileNestedDefmethodFails` and the WASM
twins, `UserMacroExpanderTest#defmethodLambdaListStaysVerbatim*`/`defclassKeepsNamesAndOptions*`/
`macroBodyMayCallAGenericFunctionAtExpansionTime`,
`LispMacroExpanderTest.theDispatcherLastResortIsOneCallOfTheSharedNoApplicableMethodSignal`,
`WasmLispCompilerTest.aSlotAccessorDispatcherDoesNotCarryItsOwnCopyOfTheNoApplicableMethodTail`,
the `applyAlignedVariadicTarget`, `PackageIsADefmethodSpecializer` and
`defmethodEqlSpecializerNamingAConstant` pairs, plus
`aCapturedGenericFunctionValueSeesLaterMethods` and
`initProtocolGenericsAreSharedAcrossPackages`. Congruence: `LambdaListCongruenceFixture`
(SBCL-classified) in `LispEvaluatorTest`, `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest` (P1 + component), ci-spec
`clos-defmethod-lambda-list-congruence` and standalone `uncaught-defmethod-lambda-list-refusal`.

Feature trios (interpreter + `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`):
multiple inheritance / diamond / inconsistent precedence, setf methods, `setfFindClass`,
`defmethodOnABuiltinName`, computed `typep` and `error` types, runtime class designators,
computed `change-class`, `withSlots` write-only + nested capture, writer / class-allocation /
`standard-class` typecase, short-form method combination, `*FindClass*` / `*CloserMopShim*` /
`classOf*` / `allocateInstance*` / metaobject type specifiers / `defclassMetaclass*` /
`compileCoercesALambdaExpressionToAFunction` /
`compileInterceptsDefinitionTimeMethodConstruction` /
`defclassMetaclassEnsureClassUsingClassAndInitargMunging` (sharing
`MopWideningFixture.MITO_SHAPE_SOURCE`) /
`defclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling` /
`slotExistsPAnswersDeclaredSlotsRegardlessOfBoundness`,
`*PrintObjectMethodDefinedBelowItsFirstUse` /
`*NestedDefmethodCallBeforeItsFormFallsThrough*` (todo 445). Plus `ShadowedBuiltinsTest`,
`FastIoCircularStreamsE2eTest`, `JzonE2eTest`, the PostmodernE2eTest DAO leg, `ContextlE2eTest`
(the MOP protocols, layered classes, instance updating, precompiled definitions).

ci-spec: `clos-defgeneric-defmethod-eql-dispatch`,
`clos-defclass-slots-inheritance-and-dispatch`, `clos-method-qualifiers-and-call-next-method`,
`clos-multiple-inheritance-cpl-slots-and-dispatch`, `clos-setf-methods-and-setf-generic`,
`clos-computed-change-class`, `clos-reinitialize`,
`clos-slot-options-and-metaobject-types`, `clos-defmethod-eql-specializer-over-a-constant`,
`clos-defmethod-bit-vector-specializer`, `clos-print-object-method-defined-below-its-first-use`,
`package-defmethod-specializer`, `defmethod-on-a-builtin-name-keeps-the-builtin`,
`defgeneric-short-form-method-combination`, `find-class-metaobject-substrate`,
`defclass-metaclass-protocol`, `mop-widening-for-mito`,
`compile-definition-time-method-construction`, `runtime-type-dispatch-and-symbol-designators`,
`runtime-type-dispatch-residue`, `runtime-class-designator-spellings`,
`with-slots-write-only-unbound-slot-and-missing-slot`,
`array-operations-enablement-language-group`, `mito-core-enablement-language-group`, standalone
`funcallable-instances-call-their-installed-function`, `classes-made-at-run-time`, `slot-access-protocol-methods`, `generic-function-protocol` and `instance-updating-of-a-redefined-metaclass-class`. Plus the five `doc/*/reference/**` pages
via `DocExamplesTest`.
