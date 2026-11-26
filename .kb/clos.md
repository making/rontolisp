# CLOS static subset — defclass / defgeneric / defmethod / make-instance / slot-value

User docs: `doc/en/reference/special-forms/{defclass,defgeneric,defmethod}.md`,
`doc/en/reference/macros/{make-instance,slot-value}.md`.

Everything expands to plain defuns via `LispMacroExpander` (no backend codegen).
`ClosRegistry` holds classes, generics and `slotPositions` (`-1` when unrelated classes disagree
— `slot-value` then errors "use the accessor"). One per evaluator (`LispEvaluator.closRegistry`)
and per compilation (`Jvm/WasmLispCompiler.Ctx`).

## `expandDefclass` / `registerDefgeneric` / `expandDefmethod`
- A `:reader`/`:accessor` is a synthesized METHOD, not a defun: several classes may declare one
  reader name over DIFFERENT slot positions, and a user `defmethod` must merge, not shadow. The
  write half is a `%setf-<name>` writer GENERIC (new value first) under `SETF_FUNCTION_MARKER`.
- An instance is `(%obj-new '%class-<name> v...)` (`.kb/instance-syntax.md`), NOT a list.
  Layout = superclass slots + own slots, so single inheritance keeps indexes stable.
- A position-ambiguous `slot-value`/`with-slots` name falls back to the reader generic.
- `call-next-method` / `next-method-p` are rewritten away (`rewriteNextMethod`, matched by
  package-stripped name, NOT in `CL_SYMBOLS`) before any backend sees them, including
  `#'call-next-method` under `apply`/`funcall`.
- Re-evaluating a `defgeneric` removes its inline `:method`s first (`GenericInfo.inlineMethodKeys`,
  CLHS defgeneric).

## Lambda-list congruence (CLHS 7.6.4)
`ClosRegistry.LambdaListShape`; `incongruenceWith` answers SBCL's sentence or null.
- **The refusal is the EXPANSION** (`programErrorForm`): a `program-error` where the form runs on
  all four backends, plus the static-program-error compile warning; a later call dispatches over
  what was added. `registerDefgeneric` judges counts and `&rest`/`&key` only (SBCL accepts
  `(defgeneric g (a &key x))` over a method `(a &key y)`).
- Checked against the lambda list AS WRITTEN, before the `&allow-other-keys` append and the
  `%method-args` injection.
- `UserMacroExpander.replayDefinition` swallows that program-error at macro time: the compiled
  program signals it at the same form.
- A `defmethod` under a top-level `let` is expanded in place on the compile paths, so a handler
  sees the refusal everywhere -- what the pins use.
- **Compile-path residual**: a second `defgeneric` changing the required count (legal once its old
  methods are gone) is a compile-time `UnsupportedOperationException` -- one dispatcher per generic.
- Deviation: SBCL signals a plain `error` when the required count differs from a generic that HAS
  methods; rontolisp signals `program-error` throughout. Against SBCL 2.2.9 over 30 `defmethod` and
  8 `defgeneric` shapes, refused/added agree on every one, all four backends byte-identical.
- Blast radius (static scan of `src/main/resources`, `src/test/resources`, `examples/`,
  `ci-spec.yaml`, doc fences and the local Quicklisp cache, 1356 files): no violation outside the pins.

## A forward-referenced superclass
`(defclass a (b) ...)` before `b` exists (ANSI allows it; cxml's `xmlns-normalizer`).
`ClosRegistry.ForwardClass` is what waits.
- An unregistered superclass name becomes a waiting class (`(defclass b () ())`, `named`, no form),
  so `find-class`, `defmethod`, `subtypep`, `typep` have a class to read. Subclasses of a waiting
  class wait too.
- **A waiting class emits** (`waitingClassForms`) only its unfinalized flag, its class-allocated
  cells and a refusing constructor. No accessors, no metaclass driver: an accessor would bake an
  index the layout does not have yet. No `registerSlotPosition` (it would mark the name ambiguous).
- **Finalizing** (`ClosRegistry.nextFinalizableClass`) re-expands the stored form as a
  redefinition; `finalizedClassForms` drops every generated method a primary method defined since
  has the key of (`noteMethodOnWaitingClass`; SBCL: the user method wins). The interpreter
  finalizes from `evalDefclass` in `waitingClassEnvs`; the compile walk from `addExpandedClass`.
- **The refusal is at the allocation seam**: only a class that ever waited tests its flag, so every
  other program is byte-identical. `unfinalizedClassMessage` differs from SBCL's text; the ci-spec
  pins `:refused`. An `&key` default runs before the refusal.
- **Refused at the form**: self as superclass, a cycle through a waiting class, a `defstruct` or
  built-in type name (SBCL's `INVALID-SUPERCLASS`); on the compile paths that fails the compile.
  `define-condition` keeps refusing an unknown first parent (SBCL: `Class not yet defined`).
- **Never defined**: a counted `%compile-warning` at its defclass
  (`warnAboutNeverDefinedSuperclasses`), and a class that refuses instances.
- Residuals (all in the window before the last superclass): `find-class` answers a
  `standard-class`, not `forward-referenced-class`, and `finalized-p` says `t`; a reader is not yet
  `fboundp` on the interpreter; a `find-class` object taken in the window is not `eq` to the
  finalized class; redefinition over a forward reference does not propagate to subclasses; a
  `:metaclass` class that waited is not widened (`widenedMetaclassDefclassOrNull`); an
  `(setf find-class)` alias does not stand in for the name.
- Pinned by ci-spec `defclass-of-a-forward-referenced-superclass` (agrees with SBCL 2.2.9),
  `LispEvaluatorTest.aForwardReferenceCannotCloseACycleOrNameANonClass` and
  `RontoLispCliStreamsTest.aReferenceToAnUndefinedFunctionWarnsAtTheReferenceOnEveryBackend`.

## A class defined below the top level (compile paths, since 2026-11-07)
snooze's `define-content` / `defroute` and contextl's `test/layer-gc.lisp` (layer classes in a
`(loop repeat 2 do ...)`).
- **The decision: hoist, not a run-time `ensure-class`.** Such a form's name, superclasses, slots
  and options are literal, so the static tables (layout, tag, ancestors, dispatch,
  `%class-meta-table%`, `--optimize`'s narrowing) hold it as for a top-level class; only WHEN it
  runs is dynamic. A run-time path would be a second object system for definitions with no
  computed part. "Classes made at run time" stays the path for computed ones.
- `NestedClassDefinitions.split`, first in `expandTopLevelDefinitions`, hoists the definition and
  leaves a `(%nested-class-defined ID)` marker, where `placeClassDefinitionRuns` puts what the
  definition does as it runs.
- **Pending until it runs**: such a class starts as a forgotten name of the public `find-class`
  (`%find-class-overrides%`, "Setf methods and class-name aliases"); each of its definitions ends
  that where it runs (`classDefinedForms`), so snooze's `unless` runs its branch once. `class-of`,
  `typep`, `subtypep`, `make-instance` and dispatch answer the static class from the start.
- **Capture is refused**: initforms and default initargs are inlined into top-level constructors,
  so one mentioning a variable a surrounding form binds is a compile error naming it (the
  interpreter runs it). An initfunction closure would lift that at a funcall per construction; no
  program needing it is known.
- **Methods and generics below any top-level form** take the closure-over-let path ("Where each
  path hooks in"). A computed `eql` specializer's cell is assigned where the method is defined
  (`splitNestedMethodExpansion`): hoisted, contextl's `(eql (find-layer-class 'bar))` ran before
  `bar` existed.
- **A nested `:metaclass` defclass is widened** like a top-level one
  (`replayNestedMetaclassDefclasses`, `widenMetaclassDefclasses`): a user file's top-level forms are
  not replayed whole, so the definition alone is.
- **Combined dispatch while a nested method is undefined**: the static branch skips every method it
  combines with an unassigned nested one (`nestedBranchGuard`). Where a run-time arm exists, calls
  take the run-time combination instead (`dynamicArmTests`), leaving out that method alone:
  layer-gc's `adjoin-layer` needs its `:after` before the loop reaches its `:before`.
- Residuals: class-allocated cells are initialized at the hoisted position, once, even where the
  branch never runs; a class only a false branch defines still has its static class;
  `class-direct-subclasses` lists a pending class; instance tracking of a redefined `:metaclass`
  class ("Instance updating") ends at the first run of a last definition that stands in a loop.
- snooze's `defroute` and contextl's `layer-gc.lisp` agree with SBCL 2.2.9 on all four backends
  (`ContextlE2eTest`). Pinned by ci-spec `classes-and-methods-defined-below-the-top-level`.

## `generateDispatcher(name, registry[, builtinFallback])`
ONE dispatcher defun per generic: a nested-if chain, most specific first. An ordinary defun, so
`#'name`/`funcall`/`mapcar` work with no `BuiltinFunctionWrappers` entry.
- An optimizing compile passes a `DispatchNarrower` (`compiler/GenericDispatchNarrowing`,
  `.kb/optimize-dead-code-elimination.md`); otherwise the dispatcher is byte-identical.
- `specializerRank`, leftmost-first: eql, classes by descending ancestor-set size, built-in types,
  default; stable within a rank. **`package` ranks ahead of `keyword` and `symbol`** because a
  package IS a keyword in this value model (`.kb/symbol-runtime-api.md`); misordered, rove's
  `find-suite` recurses forever. `simple-bit-vector`/`bit-vector` rank ahead of `vector`
  (ci-spec `clos-defmethod-bit-vector-specializer`).
- **The TYPE specializers `defmethod` accepts (`isSupportedTypeSpecializer`) and the types
  `makeTypeTest` builds are ONE definition, not two lists that drift.** Symbol eql specializers
  compare with `equal` (content-safe on WASM).
- A variadic generic's dispatcher forwards via `apply`, which hits the compile backends' ALIGNED
  fast path (`Jvm/WasmApplyCompiler`, ~80-300 B saved each).
- The last resort is ONE call of the shared `%no-applicable-method` defun with the message as a
  literal argument; inlining the error tail cost over a KB EACH across a library's accessors. The
  dispatcher AST is therefore ONE shape everywhere, which `ShadowedBuiltins`' structural
  dead-dispatcher comparison relies on. It must be appended before the format-renderer scan.
- An `(eql form)` form is EVALUATED at method definition (CLHS 7.6.2). A bare symbol is a constant
  (`ClosRegistry.registerConstant`, definition order) or else a variable (every bare name in the
  cached Quicklisp systems is one; SBCL signals `unbound-variable` otherwise); a keyword stays
  literal. `(eql (find-class 'c))` stays the form: find-class is memoized, so per-call evaluation
  compares against the definition-time object. The reader's `t` names the class T.
- **Any other `(eql form)` is evaluated ONCE, where the method is defined**, into a per-generic
  cell (`GenericInfo.eqlSpecializerCell`, one per form TEXT so a same-form redefinition keeps its
  key; `withEqlCellAssignments`). The compile paths initialize the cell to a fresh cons no argument
  is `eql` to, matching the interpreter before the defmethod runs. ci-spec
  `clos-defmethod-eql-specializer-over-a-constant`.
- **Metaclass specializers**: `class` / `standard-class` / `built-in-class` are CLASS specializers
  (`ensureMopClassesSeededFor`); `structure-class` is a TYPE specializer with the empty test. A
  class OBJECT in specializer position (serapeum's `#.(class-of ...)`) is read as its name
  (`classMetaobjectName`). ci-spec: `clos-slot-options-and-metaobject-types`.

### The two dispatch bodies
- `simpleDispatchBody` when the generic has NO qualifier and NO `call-next-method`; otherwise
  `combinedDispatchBody`, one branch per distinct specializer, filtered by `appliesToBranch`.
  A built-in type method applies to a branch below it in `SUBTYPEP_PARENTS` (`REAL` ranks ahead of
  `NUMBER` for `call-next-method` from `integer`).
- **Plus one branch per position-wise MEET of two INCOMPARABLE specializer vectors**
  (`addMeetBranches`/`specializerMeet`): one branch per method suffices only while the vectors form
  a chain, because `appliesToBranch` is a SUBSET test. Quadratic over compatible AND incomparable
  pairs only, so a generic with none is byte-identical. **Trigger**: the JVM 64 KB method ceiling
  (`.kb/jvm-method-size-limits.md`) if a library ever has many incomparable methods.
- The primary chain's values are kept by a `multiple-value-prog1` (a `let` dropped secondary values
  under an `:after`; contextl's `(values context cacheablep)`). `buildNextChain` builds nested
  lambda literals with NO free-variable capture.

### The interpreter's chain memo (`DispatchChain`, since 2026-11-05)
Walking a dispatcher's chain one test per branch was 74% of coalton's `library/math/num.lisp` time
(fset's `compare` has ~350 branches; 11 minutes, 2 with the memo).
- `DispatchChain` is a `LispCons` subclass (like `LocatedCons`), so every walker still sees the
  chain; a pass that rebuilds the form only loses the speed. The compile paths never see one.
- Keyed by argument SHAPES (instance layout, keyword or other symbol, else Java class). Tests no
  shape decides (an `eql` test, a nested method's guard, `package`'s `find-package`, array rank)
  stay per-call, in chain order. Tests have no effect, so dropping one that cannot hold is
  unobservable.
- Under the generic-function protocol or classes made at run time the chain sits inside the dynamic
  arm's `let`s; the memo must reach under it (without, an integer `compare` went 5 -> 200 us).
- A dispatcher generated again is a new chain with an empty table: nothing is invalidated.
- Pinned by `DispatchChainTest` and `LispEvaluatorTest.aGenericAnswersEachCallByItsArguments`.

## Method definitions take effect in program order
`macro/MethodGenerations`. A dispatcher is generated after the whole walk, so without this a
compiled program answered every call with its LAST method table (a call before a `defmethod` saw
that method; a redefined method's first body never ran). The interpreter regenerates at each form.
- **A generation** (`ClosRegistry.MethodGeneration`) is an earlier table a call can observe. Its
  flag is a hoisted `defvar` set right ahead of the defining form, so that form's value stays the
  top level's. Flags are set in program order: "state k or later" is one read of flag k-1.
- **One chain over every table** (`macro/DispatchBranches`): each table's chain is built as
  without generations, then merged: each distinct branch once, guarded by the states whose table
  has it, its value switched by state only where effective methods differ. Why not one
  `dispatchBody` per generation: fset's `compare` (40 generations of up to 426 methods) was a 5.5 MB
  defun (915,783 nodes) past the JVM method limit; merged it is 47,262 nodes, the wasm program
  14.9 -> 5.6 MB. Its JVM compile also needs the outliner's binding-value and `list` cuts
  (`.kb/hot-path-method-size.md`). Residual: two tables ordering two branches apart get the last
  table's order, which can matter only between branches of one rank.
- **`compute-applicable-methods` answers the table of its time** (`staticMethodsForm`).
- **The log**: every table write records a `MethodChange` (`ClosRegistry.changeMethod`, a removal
  by a second `defgeneric` included) at `definitionPosition`; `methodsBefore(p)` replays it. A
  redefinition mints a new `%g--mN`, so the old body stays callable.
- **A system default is no definition** (`putSystemDefault`): it is in every table. A program's own
  default primary on a system generic with no synthesized one is visible from the start (residual).
- **Which changes split** (`generationsOf`; every other generic stays byte-identical): a running
  form must be able to reach the generic since the last split, and the method must be able to apply
  to that call (`cannotApplyBefore`, `ClosRegistry.typeFirstDefined`), which keeps the usual
  class-then-methods-then-use shape in one table.
- **The reach is by mention**, on package-less names, plus `IMPLICIT_CALLS` for operators lowered
  later. A form reaching `funcall`/`apply`/`mapcar`/... counts for every generic an earlier form
  mentioned. Wider than the truth costs a generation and nothing else.
- **Before anything made the generic exist**, a generation signals `undefined-function`, as the
  interpreter does, unless a built-in it shadows answers.
- Residuals, each keeping the last table: a call by a run-time name no form mentions, an operator
  calling a generic not in `IMPLICIT_CALLS` (a Gray generic behind `write-char`, a reader behind a
  position-ambiguous `slot-value`), an instance made before its `defclass` form, the literal
  `fboundp` of a generic. A plain `defun` keeps its whole-program rule (`.kb/core-representation.md`).
- Pinned by ci-spec `clos-methods-take-effect-where-their-forms-run` (agrees with SBCL 2.2.9),
  standalone `generic-function-protocol` (`gfp-out-cmp`) and
  `LispMacroExpanderTest.aGenericKeepsOneMethodTableUnlessAnEarlierFormCanCallIt`.

## Multiple inheritance
CPL per CLHS 4.3.5 (`computeCpl`; inconsistent local orders are an `IllegalArgumentException`).
- **Layout rule**: the FIRST superclass's effective slots keep their indexes (the first descending
  from `class` for a metaclass, the metaobject index contract); other superclasses append new slot
  names; a diamond keeps ONE copy. Inherited slot OPTIONS are re-merged along the CPL
  (`cplMergedSlot`/`shadowSlotSpec`).
- **Shifted accessors**: a non-first superclass's accessors bake THEIR index while their class test
  covers the subclass, so `expandDefclass` synthesizes overriding methods on the SUBCLASS for every
  inherited slot whose index differs. Single inheritance never shifts.
- **Dispatch refinement** (`miRefinement`, gated on `hasMultipleInheritance()`): a class whose
  applicable class specializers have NO single most-specific member gets an EXACT-TAG branch.
  **LITE residual**: ONE parameter position only, so a diamond-affected class meeting a generic that
  class-dispatches on several positions can miss the second super's methods.
- `define-condition` with several REGISTERED parents does real MI (an UNREGISTERED extra parent
  falls back to `registerExtraAncestors`); `change-class` remaps by name, so a shifted layout is
  fine; mop-protocol.lisp's `finalize-inheritance` merges across ALL direct supers (first name wins).

## Where each path hooks in
- **Interpreter**: `evalDefclass` / `evalDefstruct` regenerate only the dispatchers the class can
  change: on a redefinition every one with a class (struct) method, for a new class only those
  `GenericInfo.dispatchesOverNewClass` (`dispatchesOverNewStruct`) reaches; a `cons`/`list`/
  `sequence` test reads no class. Measured: `(ql:quickload :clim)` over 10 minutes -> under 3;
  coalton rebuilt 8512 dispatchers unchanged and fset's `compare` 823 times before.
  A `#'generic` captured BEFORE a later defmethod is NOT stale: `evalFunction` answers a LATE-BOUND
  wrapper; a `funcall`/`apply` of it runs the current dispatcher in `evalCons`'s frame
  (`closureCalled`; fset's maps call `#'compare` per key).
- **Compilers**: `expandTopLevelDefinitions(program, structAccessors, closRegistry)` runs after
  `flattenTopLevel`, before `LambdaLists.desugarProgram` (the constructors use `&key`). Dispatchers
  are inserted AFTER the walk so descendant and method sets are complete ("Method definitions take
  effect in program order" restores the earlier tables). A `defmethod`/`defgeneric` below any
  top-level form is registered statically by `expandNestedDefmethods`/`rewriteNestedDefmethods`
  (`expandNestedDefgeneric`) while its body defun stays where the method stood (a global-closure
  `setq`): the closure-over-let idiom (cl-qrencode's `rs-ecc.lisp`), a top-level `flet`/`labels`
  (eclector's `messages-english.lisp`, needed by coalton), a conditional, a loop, a function body.
  ci-spec `generics-defined-in-a-top-level-let-close-over-it`. A `defclass` there is hoisted ("A
  class defined below the top level"); a `defstruct` below the top level stays refused.
- **`defclass` answers the class object** (CLHS); `define-condition` answers the NAME (CLHS;
  `DocExamplesTest` on `define-condition.md` catches the slip). On the compile paths
  `LispMacroExpander.flattenTopLevel` splits a top-level `(defparameter|defvar NAME (defclass C ...))`
  (lisp-binary's `struct-like-defclass`) into the defclass and `(NAME (find-class 'C))`
  (`classDefinerInitializer`), unconditionally: the class table is whole-program there anyway. Pin:
  ci-spec `defclass-answers-the-class-object`.
- **A nested method's body exists only once its defmethod form runs, so every dispatcher branch
  calling one is guarded on the assignment** (`ClosRegistry` `nestedMethodFunctions`, marked only by
  the nested expanders). Until then the call falls through, as on the interpreter. The guard is a
  plain variable read (an unassigned global is null on the JVM and WASM). A SYNTHESIZED system
  default is hoisted instead of guarded, so the fall-through never reads an unassigned global.
  Combined branches are skipped whole when ANY nested participant is unassigned (residual), except
  where the run-time arm exists ("A class defined below the top level").
- **`make-instance`/`slot-value`** are `CL_MACROS`; a literal quoted name gets the static
  expansion. `#'make-instance` as a VALUE and a call with a computed class both lower to
  `%mop-make-instance` under ONE gate (`referencesMakeInstanceValue`). A runtime slot NAME goes
  through `%slot-value-runtime` & co (setf twin gated by `needsRuntimeSlotSetDispatch`).
- **`UserMacroExpander`**: top-level defclass/defgeneric/defmethod are evaluated into the
  macro-time evaluator (a defmacro body can CALL a generic at expansion time) AND kept for the
  compilers; only defmethod bodies and `:initform` values are walked.

## Name resolution gotcha
`defclass`/specializer class names are package-resolved; the quoted name in `(make-instance 'dog)`
is NOT. `ClosRegistry.findClass` falls back from the exact key to a UNIQUE base-name match across
packages; two packages defining the same class name make the bare spelling unresolvable.
- **A type of exactly the spelled name beats every tolerance, in both directions**: `lookupClass`
  answers null for a name a `defstruct` holds exactly, and `structKey` takes the qualified
  spelling's member only when no class holds the exact spelling (cxml's
  `sax::standard-attribute` vs `hax:standard-attribute`). `SubtypepName.namesAnotherTypeThan`: two
  registered types of one member are unrelated. ci-spec
  `defclass-and-defstruct-of-one-name-in-two-packages`, `ClosRegistryTest`.
- **A built-in class's name is cl's**: no tolerance hands a bare or `cl:` built-in class name
  (`BUILTIN_CLASS_NAMES`, `FIND_CLASS_ONLY_CLASS_NAMES`) another package's class
  (`ClosRegistry.namesBuiltinClass`); coalton defines `Pathname`, `Vector`, `Symbol` classes. A type
  test of a qualified name a class holds exactly is that class's (`makeTypeTest`). ci-spec
  `make-load-form-literal-object`.
- A miss consults `classMissLoader` and retries once: the interpreter's loads gray.lisp for a Gray
  base-class name (`.kb/gray-streams.md`, "Interpreter dispatch"). A probe asking "is library X
  loaded" must therefore not be a `findClass` of X's class name.

## Setf methods and class-name aliases
- `(defmethod (setf name) ...)`: `normalizeSetfMethodForm` rewrites onto the `%setf-`
  writer-generic convention (`setfFunctionName`), so `expandSetf` is unchanged. **Ordering
  constraint**: every normalization site (`expandTopLevelDefinitions`' defmethod and defgeneric
  branches, the nested expanders, `evalDefmethod`/`evalDefgeneric`) runs BEFORE anything casts the
  name position to `LispSymbol`.
- `(setf (find-class 'alias) (find-class 'target))` at top level: **an alias is a second NAME for
  one class, never a second class.** `ClosRegistry.classAliases` (one level deep, consulted right
  after the exact lookup), registered at EXPANSION time (`expandSetfFindClass`, before the class
  tables are built); an extra SPELLING of `%class-meta-table%`, the `typep` tag table and the
  `subtypep` universe. A NON-top-level alias is an error (`markClassMetaTableEmitted` makes
  `registerClassAlias` refuse); an unknown target throws `there is no class named`.
- Any other shape is the RUN-TIME `(setf find-class)` (`LispNames.SET_FIND_CLASS`): it changes what
  the PUBLIC `find-class` answers and nothing else (`nil` forgets the name, contextl's
  `layer-makunbound`); a later `defclass` ends it. Instances, `class-of` and internal resolution
  keep the static tables, so `make-instance` of a forgotten literal name still constructs (SBCL
  signals). Interpreter: `ClosRegistry.findClassOverrides`; compile paths
  (`usesRuntimeSetfFindClass`): the `%find-class-overrides%` alist. Forgetting a name no class has
  when the form expands stays the static `nil`. ci-spec `find-class-metaobject-substrate`. `--no-gc`
  shares one never-mutated `EMPTY_CLOS_REGISTRY` and rejects the place. Macro-namespace twin:
  `(setf (macro-function ...))`, `.kb/defmacro-backquote.md`.

## A user method on a BUILT-IN name
**A built-in whose name a program defines a method on becomes that generic's DEFAULT METHOD.**
Otherwise the dispatcher SHADOWS the built-in and every non-instance argument dies with "No
applicable method". A user DEFAULT method still wins. The 2-arg `generateDispatcher` stays
byte-identical.
- **Interpreter**: `defineDispatcher` is the ONE installation seam; `builtinDefaultMethodFor`
  stashes the built-in under `%<generic>--builtin` and MEMOIZES the hit. **The memo is
  load-bearing**: the dispatcher is regenerated on every `defmethod`, and the second pass would find
  no built-in to stash. Only a Java-backed `LispFunction` is stashed; a user/prelude `defun`
  (`LispLambda`) is deliberately shadowed — which is also why a MISS needs no memo.
- **Interpreter, the call side**: stashing is not dispatching. `evalCons`' operator tables EXPAND
  many built-ins before the binding is read, and the multiple-value lowerings spell a producer's
  values BY NAME, so 45 of 249 shadowable names ignored the user method. A stash of a
  `ShadowedBuiltins.loweredBuiltinFunctions()` name joins `methodedExpandedBuiltins`, sent straight
  to the binding. A methoded syntactic producer
  (`LispMacroExpander.isSyntacticMultipleValueProducerName`) gets `%<name>--dispatch` and its sites
  are renamed (`ShadowedBuiltins.renameCallSites`). **Order independence**: defuns and lambda tails
  are lowered at DEFINITION, so every tail whose lowering changed something is registered weakly
  (`LispEvaluator.producerTailSites`) and re-lowered in place on the producer's first method.
  Pinned by `ShadowedBuiltinsTest.everyLoweredNameDispatchesAUserMethodOnTheInterpreter`,
  `everyLoweredNameDispatchesFromAFunctionDefinedBeforeTheMethod` and `MethodedBuiltinFixture` on
  the interpreter, JVM and WASM.
- **Compile paths** (`compiler/ShadowedBuiltins`, right after `expandTopLevelDefinitions`):
  `(close X)` is compiler-lowered whatever defuns exist, so the spliced dispatcher is dead. It is
  found by structural equality against a regenerated 2-arg dispatcher (a user defun of the name is
  never mistaken for it), renamed `%<name>--dispatch`; `%<name>--builtin` is a forwarder
  (`ShadowedBuiltins.forwarderDefun`); call sites and `#'name` are rewritten. The walk
  (`compiler.CallSiteWalk`, shared with the Gray and socket rewrites) skips the generated defuns
  (rewriting the forwarder's fallback would recurse) and keeps a PLACE's head (`(setf (gethash k h)
  v)` of a methoded `gethash` failed the compile). When `close` is shadowed, `with-open-file` & co
  are pre-expanded — side effect: such a WASM module is always in EH mode.
- **The forwarder takes the whole tail**: one direct lowered call per count the built-in's
  `BuiltinCallArity` accepts, `apply` of the wrapper past the positional counts, a run-time
  `%program-error` otherwise (so no static warning fires for a count no call site passes). A
  backend alias is called only inside its `WasmSocketsRewrite.ARITIES` range
  (`ShadowedBuiltins.BuiltinAlias`). Pinned by `MethodedBuiltinTailFixture` on three backends and
  `ShadowedBuiltinsTest.everyForwarderCallsItsBuiltinWithACountTheCallShapeAccepts`. Residual: a
  rejected count reports the SHAPE's text (`FLOOR expects at most 2 arguments, got 3`) where the
  interpreter's can differ (`.kb/error-handling.md`).
- **The name set**: `BuiltinFunctionWrappers.names()` minus `%`-internals, `NOT_SHADOWABLE` and
  `EXPANSION_LOWERED`, plus `LOWERED_WITHOUT_WRAPPER`. **Pinned by `ShadowedBuiltinsTest`: every
  member must be a Java-backed `LispFunction` in a fresh global environment** — the interpreter's
  stash criterion.
- **Remaining divergences (triggers)**: (1) a plain `(defun close (x) ...)` is ignored by the
  compile paths while the interpreter honors it; (2) `EXPANSION_LOWERED` names (mapcar, sort,
  format, ...) are un-dispatchable everywhere; (3) under `--component` with sockets,
  `WasmSocketsRewrite` runs BEFORE this pass, composed via
  `WasmSocketsRewrite.builtinDispatchAliases`; the ASYNC read promotions still bypass a user method.
- **A run-time name reaches the dispatcher** (ci-spec
  `a-method-on-a-lowered-built-in-answers-a-name-built-at-run-time`,
  `a-method-on-a-lowered-built-in-answers-a-form-eval-runs`): `ShadowedBuiltins.runTimeNames` gives
  both `_lookup` builders a row answering the dispatcher. The name stays out of `functions` (the
  forwarder must still lower). `_eval`'s `list` and arithmetic arms step aside for a methoded name
  (`JvmEvalRuntimeBuilder.evalBody`, `EvalHooks.armOffset`); a literal `(symbol-function 'length)`
  is the dispatcher (`CallSiteRenamer`); the dispatcher prints and reports counts under the
  built-in's name (`arityOperator`, `printedName`). A write of the name reaches the dispatcher's
  cell (`.kb/symbol-runtime-api.md`, "A write through a computed name").
- **A generic has one function object on the interpreter** (`LispEvaluator.functionValue`), so
  `(eq #'g #'g)` is T as on the compile paths and SBCL.

## The instance-initialization protocol
`initialize-instance`, `reinitialize-instance`, `shared-initialize` are CL symbols
(`PackageRegistry.CL_FUNCTIONS`): CL has ONE generic each. Otherwise each package mints its own and
`make-instance`'s chain (matching generics by PLAIN name, first hit) runs one package's while
another's hooks never fire.

`expandDefmethod` SYNTHESIZES the system primary plus the CL chain the first time any method is
defined on one (`synthesizeCalledInitProtocolGeneric` when the program only CALLS one). **The
synthesis condition is the absence of an ALL-DEFAULT primary, not of any primary**: a
class-specialized primary must not displace the system method a sibling's `(call-next-method)`
has to find.

**Who fills the slots** is per class (`ClosRegistry.initializesThroughMethods`): when a primary,
`:before` or `:around` method of `initialize-instance` or `shared-initialize` applies, `make-instance`
allocates every slot unbound and the generic fills it (CLHS 7.1.4); every other class keeps the
one-call constructor `%make-<name>`. typo's `full-fnrecord` computes a slot in a `shared-initialize`
primary over an initform that signals.
- **Per class, not per program**: an `:after` runs after either fill. Census of 279 cached
  Quicklisp releases: 321 `initialize-instance :after`, 50 `shared-initialize :after`, 49
  primary/`:before`/`:around`, all class-specialized.
- A primary never calling the next method leaves the slots unbound, as in CL.
- **A program's own all-`t` primary** displaces the system method, so every class keeps the
  constructor then.
- **Only a declared `:initarg` fills** through the generic; the slot-name keyword the constructor
  also takes does not (CL: an invalid initarg).
- **A class-allocated slot's initarg fills the shared cell**, never the instance's dead mirror.
- **The slot-names are honored** (`%mop-initform-wanted-p`, the interpreter's `selectsSlot`).
- `make-condition` and the signalling operators run no initialization method (SBCL 2.2.9 agrees).
- **`change-class`** (`ClosRegistry.updatesThroughSharedInitialize`): when a `shared-initialize`
  method applies to the target, added slots are left unbound and `(shared-initialize obj
  added-names initargs...)` runs -- CLHS 7.2's system method inlined
  (`changeClassViaSharedInitialize`). A method on `update-instance-for-different-class` is never
  called (census: metatilities' two `:after`s).
- Pinned by ci-spec `an-initialization-method-runs-before-the-fill` (SBCL 2.2.9 agrees) and
  `defclass-metaclass-protocol`'s `#'make-instance` leg.

Three congruence rules:
- **A `&key` method never rejects a sibling's keyword** — the lite model appends
  `&allow-other-keys` to every `&key` method lambda list instead of taking CL's union -- AHEAD of an
  `&aux` section (access's `rem-arg-list-key-value`; ci-spec `lambda-list-extensions`).
- **A defclass constructor tolerates extra initargs** (`&allow-other-keys`, a slot-less class
  included, `keywordConstructorShape`): an initarg an `initialize-instance` method declares is valid
  (CLHS 7.1.2). ci-spec `defclass-default-initargs`.
- **A bare `(call-next-method)` forwards the WHOLE original argument list** (`apply` over the
  `&rest`, injecting `%method-args`). **`&optional` needs more than the rest variable**: `&rest`
  binds what is left AFTER the optionals, so each optional is normalized to `(var init supplied-p)`
  and only SUPPLIED ones are passed on (CL's rule). Pinned against SBCL over six tail shapes.

**`describe-object`** is a callable system generic of the same kind (system method
`%describe-object-default`, SBCL 2.2.9's first two lines); `describe` answers no values
(`passesMultipleValues`). in-nomine's `describe-object :after` is the consumer. ci-spec
`describe-and-describe-object`.

**The trio, `print-object`, `make-load-form`, `slot-unbound` and `slot-missing` are function values
with no method in the program** (`isCallableSystemGenericName`): the interpreter synthesizes the
generic in `resolveFunction`, the compile path for any program that NAMES one (which takes it off
the no-CLOS fast path). **The init defaults apply to instances only** (`whenInstance`, else the
no-applicable-method error, SBCL's answer); every instance is `%obj-p`, metaobjects, structs and
conditions too. Pin: `StandardGenericFunctionValueFixture`.

**Cold-branch tolerance**: on the COMPILE paths only, `expandMakeInstance(cons, registry, true)`
lowers an unknown class to a runtime `error` instead of failing the compile.

## Ambiguous slot writes, runtime `typep`, runtime slot names
- `(setf (slot-value obj 'NAME) v)` with NAME at DIFFERENT indexes in unrelated types emits an
  instance-TAG dispatch (`expandAmbiguousSlotSet`).
- `(typep x COMPUTED-SPEC)`: an unrecognized specifier, COMPOUND included, yields nil rather than
  signalling. **A runtime specifier is matched by SPELLING**; the reverse fallback (a QUALIFIED
  specifier against a plainly-registered class) is deliberately not emitted, since the only
  plainly-registered classes are seeded `cl` names. **Trigger**: a plainly-registered class whose
  name is not.
- **The COMPILE paths must not inline that dispatch**: its size grows with the class count, and at
  cl-postgres scale (165 layouts) three sites overflowed the JVM's signed-16-bit branch offsets.
  `(%typep-runtime value spec)` over the CHUNKED `%typep-tag-table%` data table (48 entries per
  form). Same for `%subtypep-runtime` (`%subtypep-ancestor-table%`) and a computed `error` datum
  with initargs (`%error-runtime`; the inline shape overflowed the 64 KB limit).
- `%class-slot-defs-runtime` over `%class-slot-defs-table%`, one entry per LAYOUT (classes AND
  structs, `ClosRegistry.slotDefs`); a struct's types are all `T`, which lets json.lisp's
  `%json-out-instance` treat a struct like an instance. Inline it hit the 65535-byte ceiling;
  chunked by `nodeBudgetedTableForms`.
- A non-literal slot name falls to `expandRuntimeSlotValue` / `expandRuntimeSlotBoundp`.

## A RUNTIME class designator: both colon spellings
The compile paths carry no package registry at run time, so `intern` assembles `PKG:MEMBER`
(`.kb/symbol-runtime-api.md`) while the class is registered as `PKG::MEMBER`, and generated
dispatches match by SPELLING. `LispMacroExpander.addDesignatorSpellings` is the ONE place turning a
registered name into its designating spellings; every generated table/dispatch from a class, struct,
condition or alias name goes through it. One package cannot house two symbols of one member name, so
the added spelling never designates another class; a `cl-user` program is byte-identical.

## `change-class` — in place, on all four backends
Object identity and every slot whose NAME both classes have survive (CLHS 7.2, also between
unrelated classes); added slots get initforms unless an initarg supplies them
(`changeClassInitformFill`: Drei's narrowed marks have `:initform (error ...)`).
- `expandChangeClass`: **the tag is captured BEFORE the swap** (`%obj-become`); one arm per source
  mapping. A target a `shared-initialize` method applies to calls it instead ("The
  instance-initialization protocol").
- **Sources mapping alike share one arm** (`ChangeClassSlotOrigin`). The computed path is quadratic
  in user classes in its tag tests (a runtime layout-driven remap would make it linear). On
  `AsdfMetaobjectsE2eTest`'s program (103 classes, cold `wasmtime compile`): an inline initarg scan
  per slot per source took the module 3.32 -> 7.09 MB and the peak to 7.2 GB, killing a `test-e2e`
  runner ([test-execution.md](test-execution.md), "CI shards"); merged with a `getf`: 2.72 MB, 0.71 GB.
- **An arm tests its sources with one `member`, never an `or`**: an `or` expands to a `let` and an
  `if` per element, which the backends walk recursively (`JvmIntFusionCompiler.scanAssigns`). On the
  corpus's 358 classes it overflowed a 1 MiB compile thread in
  `JvmDeadMethodEliminationCorpusTest`; the corpus compile went 252 s -> 81 s on the JVM (past
  `CiSpecE2eTest`'s 300 s on macOS CI before). Pins
  `WasmToplevelChunkingTest#aComputedChangeClassFillsASlotOncePerWayAClassMapsOntoIt`, ci-spec
  `clos-computed-change-class`.
- **`LispLayout.capacity()` is why this works on the JVM**, where an instance IS its `Object[]`:
  every class an instance can be changed from (target's ancestors and every non-seeded class)
  reserves the target's slot count (`registerChangeClassTargets`, `applyChangeClassCapacities()`).
  `%obj-slots` and the printers bound themselves by the LAYOUT's slot count, never the array length.
- **The WASM instance struct's field 0 is MUTABLE** for this, making it structurally identical to
  `TYPE_P1_FUTURE` `{mut i32, mut eq}` — so its rec group carries a second, never-instantiated empty
  struct: wasm canonicalizes a rec GROUP as a whole, so `ref.test` keeps telling an instance from a
  future (`INSTANCE_TYPE_COUNT = 2`). **Do not "simplify" it.**
- The interpreter needs no reservation (`LispInstance.becomeLayout` grows the array).
- **A COMPUTED class argument**: the interpreter resolves natively (`resolveChangeClassDesignator`);
  the compile paths lower to `%change-class-runtime` (gate `needsChangeClassRuntime`) with one arm
  per NON-SEEDED class, so every non-seeded class joins the capacity reservation.

## Real slot unboundness
**A slot written with no `:initform` starts UNBOUND, not nil.** jzon's `coerced-fields` and
`%json-out-instance` SKIP an unbound slot.
- The marker is an instance of a LAYOUT-ONLY type, `ClosRegistry.UNBOUND_TAG` = `%class-%UNBOUND%`,
  deliberately NOT in `classes()`: a class there would join every typep tag table, `class-slot-defs`
  answer and `standard-object` descendant set. It prints `#<%UNBOUND%>`.
- Every read goes through ONE out-of-line helper `%slot-read` (`%slot-bound-p` for `slot-boundp`).
  **Calls, not inlined `%obj-is` + `if`, for a size reason**: inline tests per accessor ran the
  ci-spec corpus past the 64 KB method limit ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- In a GENERATED accessor body the `'NAME` rides in `%unspelled-quote` (`checkedSlotRead`), so it
  must not arm the funcall-dispatch gate's name probes
  ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)); a user-written
  `slot-value` keeps the plain quote. `expandSlotValueRaw` is the unchecked read the `setf` place
  needs. A RUNTIME slot name is checked too.
- **The name `%slot-read` carries is the slot symbol as declared** (`slotNameSpelling`): a slot
  declared in `zp` answers `cell-error-name` `ZP::DATA`, and an `(eql 'data)` written in `zp` matches.
- `unbound-slot` is seeded under `cell-error` with an `instance` slot and a Java-built `:report`
  (`ClosRegistry.unboundSlotReport`). `print-not-readable` likewise (SBCL's report text); the printer
  signals it (`.kb/pretty-printer.md`).

## `slot-unbound` / `slot-missing`
CL generics; the first method (or a mere call) synthesizes the SYSTEM default primary
(`synthesizeSlotProtocolDefault`) signalling what the program signals without the generic.
- **Routing is gated on the generic existing.** Compile paths: `%slot-read` calls `slot-unbound`
  only when the registry has it after the walk (`slotUnboundDefuns(routed)`); naming either generic
  forces the metaobject runtime. The interpreter always routes (its `%slot-read` is defined lazily,
  before a later `defmethod` could be seen).
- `missingSlotAccess` with `SLOT-MISSING` registered, else `missingSlotStub`. Residuals:
  `slot-boundp` of an undeclared slot answers nil without the call; a runtime-name access keeps its
  own error; a slot SOME other class declares is not detected as missing.
- ci-spec: standalone `clos-slot-unbound-and-slot-missing-methods` (a method reroutes every slot read
  of the program, so it stays out of the shared corpus).

## Slot shadowing, `:default-initargs`, `with-slots`/`with-accessors`, slot options
- **A subclass may re-declare an inherited slot** (CLHS 7.5.3): STORAGE stays the inherited slot
  (descendants keep the baked index); initform overrides, initargs and readers are the union
  (`shadowSlot`).
- **`(:default-initargs :arg form)` overrides the matching slot's effective initform**, where BOTH
  construction paths read it (so `(error 'my-cond)` honors it).
- **A slot may name several initargs** (CLHS 7.1.1; leftmost supplied wins, 7.1.4): the constructor
  keeps one `&key` per slot, only a several-initarg slot adds a `&rest` scan; elsewhere
  `acceptsInitarg` / `initargTailScan`.
- **An initarg is any symbol** (CLHS 7.1.1; cl-vectors' `(rx :initarg rx)`): held as written,
  passed quoted (`initargForm`, `literalInitarg`; `.kb/lambda-lists.md`). Pinned by ci-spec
  `defclass-default-initargs`.
- **`with-slots` resolves `defstruct` slots too** (`uniqueLayoutSlotIndex`, falling back to the tag
  dispatch).
- **A slot variable is a symbol macro**: `substituteSymbolMacros`, the shadow-aware walk of
  `symbol-macrolet` ([symbol-macrolet.md](symbol-macrolet.md)); a textual walk broke prove's
  `with-additional-indent` (an inner `with-slots` whose slot list took the outer substitution).
  Each path expands the body's macros first (`expandSlotVariables`). ci-spec
  `with-slots-variable-is-shadowed-by-an-inner-binding`.
- **`macroexpand-1` answers CL's shape instead** (`let` + `symbol-macrolet`,
  `LispMacroExpander.expandSlotVariablesForWalker`), since a code walker expands the body after it:
  under cl-environments' walker generic-cl's `remove-from-lazy-seq` compiled a bare unbound `TAIL`
  otherwise. ci-spec `a-walker-expanding-with-slots-keeps-the-names-in-scope`.
- **`with-slots`' entry-time fallback binding is BOUNDNESS-GUARDED** (`boundOrNil`). **Not
  cosmetic**: `with-slots` BINDS, never reads, so a body that only ASSIGNS an initform-less slot must
  not signal on entry (fast-io's `(with-slots (buffer) self (setf buffer ...))`). **`with-accessors`
  has no such binding**: an accessor has no boundness twin, and an unguarded read on entry refused a
  body only writing an unbound slot (Drei's views).
- **The instance temp is named PER FORM, so a NESTED `with-slots`/`with-accessors` cannot capture the
  enclosing one's** (`freshObjVar`): a pure function of the form, no counter
  (`.kb/emitted-output-determinism.md`); the outer substitution PLANTS its temp name in the body, so
  an inner form produced by a later macro expansion steps past it too.
- **A `slot-value` naming a slot NO registered class declares is a RUN-time error**
  (`missingSlotStub`), visible to `handler-case` like CL's `slot-missing`.
- **`:writer`** (`SlotSpec.writers`): `(setf place)` is stored pre-mangled as `%setf-place`; writers
  merge on shadowing and join the MI shifted-accessor synthesis.
- **`:allocation :class`** (`SlotSpec.sharedCellVar`): ONE global cell per DECLARING class; a
  subclass re-declaring with `:allocation :class` mints a new cell, one re-declaring WITHOUT reverts
  to `:instance` (CLHS 7.5.3). The slot KEEPS a layout index — a dead mirror — so index computations,
  `slot-exists-p` and `%class-slot-defs` are untouched; every ACCESS routes to the cell
  (`checkedCellRead`, `classSlotAccessByTag`, `sharedCellsByTag`, the interpreter's `CellSlotRef`).
  **Residual**: the instance PRINTER shows the mirror; `equalp`/`%obj-slots` walk mirrors.
- **`standard-class` as a type specifier**: `makeTypeTest` seeds the MOP classes
  (`ensureMopClassesSeededFor`); `structure-class` keeps its deliberate empty test.

## `define-method-combination`
On all four backends (`macro/MethodCombinations`), for McCLIM's `values-max-min`; arnesi's
`wrapping-standard`/`cc-standard` and serapeum's `standard/context` are the other long forms in the
cached Quicklisp systems. No run-time part: what the body computes becomes the dispatcher's code.
- **An evaluator runs the body, once per dispatcher branch** (`expanderLambda`;
  `LispEvaluator.evalDefineMethodCombination`, `ClosRegistry.registerMethodCombination`). The short
  form is CLHS's long-form equivalent. The body gets a `standard-method` holding only the qualifiers
  (`methodObject`), and the answer's macros are expanded (arnesi's `closure-with-nextmethod`).
- **The compile paths** run the form in the macro-time evaluator and leave
  `(%define-method-combination 'name '#<function>)` (`UserMacroExpander.stripDefmacroDefinitions`).
- **The effective method** (`definedCombinationEffectiveMethod`, `MethodCombinations.lowered`): the
  arguments are rebound to `%MC-ARG-n`, as the answer may bind a variable named like a parameter
  around a `call-method` (values-max-min's `max`). A signal of the function (an empty required
  group) becomes `(error message)` in the branch: CLHS computes the effective method per call, and a
  branch no call reaches must not stop the definition.
- **Qualifiers**: any atoms before the lambda list (`methodLambdaListIndex`),
  `GenericInfo.methodQualifiers`.
- Residuals: run-time `add-method` methods do not join such a generic; a method object answers
  `method-qualifiers` only; SBCL's "More than one method of type X with the same specializers" is not
  signalled; `:arguments` takes required parameters and `&rest`.
- A qualifier pattern list matches element by element, `*` anywhere (CLHS); SBCL 2.2.9 honors `*` as
  the tail only, so `(:a *)` misses `(:a :x)` there.
- Pinned by ci-spec `define-method-combination-long-and-short-forms` (SBCL 2.2.9 agrees).

## `print-object` — the printer consults it
A `defmethod print-object` makes the printing operators render that type through the generic.
**Gated on the program defining a method** (plus the condition and `*print-case*` gates below):
with none, every printing operator compiles exactly as before.

**The DIRECT call always works**: `synthesizePrintObjectDefault` registers CL's SYSTEM method when
the first method is defined or the program only CALLS the name (`synthesizeCalledSystemGeneric`).
- It renders through the RAW `%prin1-to-string`/`%princ-to-string`, not `%print-object-str`, as a
  **correctness requirement**: `printObjectTags` collects specializers from EVERY parameter while a
  dispatcher dispatches on the FIRST, so a method specialized on its STREAM parameter puts that
  class in the routed set and a routed default would recurse forever.
- **Two compile-path scans had to learn the name**: `expandTopLevelDefinitions`' fast path and
  `injectMvSpillGlobal`'s `*print-escape*` gate (without it the default read an unseeded global).
- `expandPrintObjectHook` rewrites the printing operators (and `%princ-piece`/`%prin1-piece`,
  `.kb/string-write-runtime.md`) onto `(%print-object-str x escape)`; `format`'s `~A`/`~S` need no
  case of their own.
- `%print-object-str` falls back to `%princ-to-string`/`%prin1-to-string` — INTERNAL ALIASES, else
  the fallback would re-enter the rewrite. The cycle guard is shared (`.kb/pretty-printer.md`;
  `%pos-walk`, `%pos-chain-stop` = Floyd).
- **`*print-escape*` is BOUND around the method call** (`printObjectCall`). The compile path's
  `(defvar ...)` comes from `injectMvSpillGlobal`, which runs AFTER `expandTopLevelDefinitions` and
  therefore sees the route's own reference. **That ORDER is load-bearing.**
- **Gate 2 is a condition's `:report`** (`.kb/error-handling.md`, `%condition-report-str`); a method
  on a condition class still wins. **Gate 3 is `*print-case*`** (`%print-cased`, UNDER the method
  route). There is exactly one place that decides what text a printing operator writes.
- **The method is consulted for a NESTED value too**: `%print-object-str` walks conses and general
  rank-1 vectors, `%print-object-leaf` routes. **The walk must reproduce the raw renderer byte for
  byte** for what it does NOT route. **The vector guard excludes what it cannot spell** (a string,
  rank != 1, a packed FLOAT array), written as "element type is not `single-float`/`double-float`",
  NOT "is `t`", since the general answer is a `T` SYMBOL in the interpreter and the `t` VALUE on the
  JVM; a packed INTEGER vector is deliberately walked. Emitted only when `programUsesGeneralArrayOp`.
  The interpreter regenerates the defun pair whenever the routing moves
  (`ensurePrintObjectRuntimeLoaded`), so a method defined AFTER the first print takes effect.
  **Still not walked (trigger)**: a hash table, an array of rank != 1. **Cost**: O(n^2) string
  concatenation, like `%print-cased`'s.
- **An instance's slots are walked** (`%pos-instance`, frame from `%obj-print-frame`,
  `.kb/instance-syntax.md`), so a method applies in a slot at any depth (sento logged 353876
  characters for one idle actor otherwise). Honors `*print-level*`/`*print-length*` as SBCL 2.2.9
  (`#S(ITEM :A # :B #)`). A pathname or stream has no frame. Gate: `printObjectStrDefuns`'
  `walkInstances` (`ClosRegistry.definesInstanceTypes`), so a program with only seeded conditions
  keeps its two-argument leaf. Pinned by ci-spec `print-object-nested`.
- **`print-object`'s system method walks too** (`%print-object-default` -> `%pos-default`), but does
  not route the instance it was handed (the STREAM-parameter recursion above). `%pos-defaults`
  makes a cycle through a method end in `#` (SBCL exhausts the stack).
- **A `print-object` method defined BELOW its first use renders the built-in text for the earlier
  print on all four backends** (pinned by the per-backend `*PrintObjectMethodDefinedBelowItsFirstUse`
  tests and `clos-print-object-method-defined-below-its-first-use`): the branch guard skips the
  not-yet-assigned body and the hoisted system default renders the raw text, as the interpreter and
  CL answer. no-applicable-method was rejected because the repro must PRINT; the NPE / wasm cast
  trap it replaced is a condition no program can act on.
- `print-unreadable-object`'s `:type t` strips the tag prefix INLINE (`typeNameOf`), not via the
  prelude's `type-of`: this expansion runs after the prelude pre-pass. The separating space is
  written only when a body follows (SBCL writes `#<D >`, against CLHS); `:identity` prints NO
  address. **That designator follows `*print-escape*`** (CLHS 22.1.3.3): `prin1` gives
  `#<QURI:URI ...>`, `princ` `#<URI ...>`. Under printer control it goes through `%pc-type-name`
  (`.kb/pretty-printer.md`). The reference is late, so `injectMvSpillGlobal` counts an un-expanded
  `print-unreadable-object` as declaring `*print-escape*`.

## Short-form `:method-combination`
NAME in `ClosRegistry.SHORT_FORM_COMBINATIONS`, or one `define-method-combination` defined; any
other is REJECTED at `defgeneric` time. `registerDefgeneric` records the option BEFORE the inline
`(:method ...)` clauses expand.
- Legal qualifiers: the NAME and `:around`; `:before`/`:after` and an unqualified method are
  REJECTED (CLHS). Short-form primaries have no `call-next-method` (CLHS); an `:around`'s next is
  the combined form (`shortFormEffectiveMethod`).
- No applicable method is the ordinary error, NOT an empty `(progn)`: an empty `(and)` answers `t`
  and an empty `(+)` zero, hiding a missing method.

## MOP boundary — what is IN
`find-class` AND `class-of` answer a memoized `standard-class` or `built-in-class` instance on all
four backends, `eq` to each other.
- **Interpreter**: `ClosRegistry.classMetaobject` / `builtinClassMetaobject`. Seeding is lazy
  (`ensureMopClassesSeeded()`) — **NEVER seed unconditionally**: that joins every runtime dispatch
  table and once pushed the ci-spec corpus over the JVM 64 KB method ceiling.
- **Compile paths**: gated on the program referencing `find-class` OR `class-of`, the chunked
  `%class-meta-table%` plus `%find-class`. The public `find-class` defun is injected only when the
  program references it without defining it.
- The internal `%class-designator` serves the light consumers (`type-of`,
  `print-unreadable-object :type`, the no-applicable-method message, `%json-out-instance`), which
  drag no metaobject runtime in.
- **The built-in class set is TWO lists and ONE narrowing.** `BUILTIN_CLASS_NAMES` is what
  `class-of` can answer (incl. the standard stream classes, `.kb/gray-streams.md`, and
  `VALUE_LAYOUT_CLASS_NAMES`); `FIND_CLASS_ONLY_CLASS_NAMES` is the rest of the CL lattice, reachable
  by NAME only because every value has a narrower answer (`class-of` of a bit vector answers the
  representation name, though it is stamped `bit` since `.todo/043`). The narrowing is ARRAYS:
  `%class-designator` answers `T`, `class-of` `vector` at rank 1 and `array` otherwise. Two emission
  sites, one dispatch: `LispMacroExpander.expandClassOf` and the interpreter's `classOfTypeName`.
  `type-of` is NOT this dispatch (still answers `(simple-vector 3)`).
- **The lattice is NOT copied into the metaobjects.** A built-in class metaobject has no slots and
  no superclasses; as a type specifier it means its NAME's set, so `typep`/`subtypep` hold the
  lattice once. Filling `direct-superclasses` would be a second copy that can drift, nest every
  built-in class's printed form four deep, and buy no ANSI test (`class-precedence-list` is ruled
  out by `.todo/715` §4).
- **`built-in-class` is a metaclass beside `standard-class`**: McCLIM's `record-presentation-type`
  asks `(typep (class-of x) 'standard-class)` to tell a CLOS class from a built-in one. SBCL's
  `system-class` and structure classes among the built-in names are `built-in-class` here.
  `ClosRegistry.isClassMetaobject`, `closer-mop:classp` and the name normalizations ask for `class`;
  `%slot-protocol-instance-p` asks for `standard-class`. `class` and `built-in-class` have no
  constructor.
- A count of ANSI ERROR lines naming a missing operator is an UPPER bound on the tests it wins:
  the built-in names were priced at 156 and won +41.
- **`typep`/`subtypep` take a class METAOBJECT wherever a type specifier is expected**: ONE rule
  ("an instance tagged as a `class` descendant continues as its slot-0 name"), three emission sites
  (`classMetaobjectDesignator`, AHEAD of the `t`/`nil` constant edges; `metaobjectNameNormalization`
  in `%typep-runtime` / `%subtypep-runtime`).
- `class` is a SEEDED class (`ClosRegistry.CLASS_NAME`), superclass of `standard-class`,
  `built-in-class` and `forward-referenced-class`, holding the five slots of every class
  metaobject. Never instantiated.
- **The method metaobject classes** (`ClosRegistry.METHOD_METAOBJECT_FAMILIES`): single-parent
  roots (AMOP's `metaobject`/`specializer` left out); `generic-function` descends from closer-mop's
  `funcallable-standard-object`. CL names are registered bare (`CL_TYPES`), closer-mop externals
  under `CLOSER-MOP:NAME` so `class-name` is `eq` to the read symbol (`seedClass`). The funcallable
  pair and `java:java-exception` are registered `CLOSER-MOP::`/`JAVA::`, and their `class-name` is
  NOT `eq` to the read symbol. Each FAMILY seeds whole where a program names a member
  (`ensureMopClassesSeededFor`; compile paths: `seedMethodMetaobjectClassesNamedIn`, where a bare
  `method` counts only QUOTED -- a common variable name). `EMPTY_CLOS_REGISTRY` is never seeded.
  **Instances exist only under the generic-function protocol**: elsewhere a generic's value stays a
  plain function (trivia's `(etypecase fn (generic-function ...) (function ...))` takes the function
  arm), and `make-instance` of a seeded one is a run-time error, so a library carrying the call
  compiles. `funcallableTags()` is empty while every funcallable layout is seeded and the protocol is
  off. Residual: `(subtypep 'standard-generic-function 'function)` is `nil t` (SBCL `t t`). ci-spec:
  `clos-slot-options-and-metaobject-types`.
- **Two traps from LAZY seeding**: (a) an EMPTY `descendantTags(STANDARD-CLASS)` means "no metaobject
  can exist" on the compile paths but NOT in the interpreter, where the test's own `(find-class 'c)`
  seeds AFTER the expansion — hence `liveRegistry`; (b) a type SPECIFIER naming a MOP base class is
  itself a seeding trigger, or `(typep (find-class 'c) 'class)` as a first MOP form folds to nil.
- The built-in `T` class's name slot holds the boolean `t`, not the symbol, so the runtime `typep`
  universal-type arms match BOTH spellings (`universalTypeMatchTest`).
- **Metaobject slot order is a `%obj-ref` index contract** shared with the closer-mop shim — append,
  never reorder: class = name, direct-superclasses, direct-slots, effective-slots, finalized-p (the
  first superclass of a metaclass descending from `class` opens its layout whatever its place,
  `layoutPrefix`, for McCLIM's `(presentation-type-info standard-class)`); slot-definition = name,
  initargs, initform, type, readers, initfunction (5), writers (6), allocation (7: `:class` or nil =
  `:instance`; the compile paths' meta-table row carries it as an optional sixth element), `%location`
  (8, spelled apart from a user slot-definition class's own slots: "Slot locations").
- **Slot-definition classes**: `slot-definition` declares every slot; the children are slot-less, so
  the layout is unchanged. `standard-slot-definition` is the first superclass of both `standard-*`
  ones (`seedStandardSlotDefinitionClass`, AMOP's precedence; clsql typecases on it).
- **`:type`** reaches a driver-built definition as WRITTEN (`ParsedSlot.writtenType`, compound
  included: clsql's `(string 30)`), where the static `SlotSpec` keeps t. No `:type` written means no
  initarg, so a slot-definition class's `:around` reads nil as on SBCL.
- **`:initarg nil`** is accepted (clsql) and names no usable initarg: `(make-instance 'c nil 9)`
  leaves the slot unbound where SBCL fills it; `&key ((nil v))` is refused (`LambdaLists.parseKey`).
- **A plain view carries direct slots too**: the compile paths' 4th `%class-meta-table%` column,
  `(writers name)` referencing the effective row when the direct `SlotSpec` IS the effective one.
  Writers fill direct definitions only (AMOP 5.4.3; `ClosRegistry.slotWriters`).
- **`allocate-instance`**: every slot unbound, initargs ignored. Compile paths: `%obj-new` needs a
  LITERAL tag, so `allocateInstanceDefuns` (gated by `needsAllocateInstanceRuntime`) emits one arm per
  class, chunked into `%ALLOC-INST-<n>`. It does NOT seed the MOP classes; built-in and struct
  classes signal.
- `closer-common-lisp` is a resolver-level flat re-export (`.kb/packages.md`).

### Slot locations and methods on `allocate-instance` (McCLIM's dynamic slots)
McCLIM's `class-with-dynamic-slots` keeps a dynamic variable in each slot's storage, reached past
its own `slot-value-using-class`.
- **`slot-definition-location`** answers `%location`, the index `%obj-ref` takes; a class-allocated
  slot and a direct definition answer nil. `compute-slots` computes a new definition where an
  inherited one's location differs (the second superclass's slot under MI). Residual: a superclass
  added at run time (contextl's partial classes) changes `compute-slots`' order and not the static
  layout, so the location stops matching the storage.
- **`standard-instance-access`** is `%obj-ref` behind `%instance-location`: without the check a
  non-instance traps on WASM. An index past the slots stays unchecked (interpreter signals, JVM
  `ArrayIndexOutOfBounds`, WASM traps).
- **A method on `allocate-instance`**: the compile paths' generated defun moves to the stash's name
  (`builtinDefaultMethodName`) and becomes the dispatcher's fallback; otherwise it overrode the
  dispatcher and no method ran.
- **`make-instance`** calls `allocate-instance` when such a generic exists and the class has a
  `:metaclass` (`ClosRegistry.allocatesThroughGeneric`). Only McCLIM's and serapeum's
  `abstract-standard-class` define such methods in the cached Quicklisp systems, both on a user
  metaclass. Residual: a method on `standard-class` or an `eql` class is not reached from a class
  without a `:metaclass`; `class-prototype` calls the generic (SBCL's needs no allocation).
- `class-prototype` of a built-in class is a value of it. A defclass whose form seeds a class
  regenerates the class dispatchers BEFORE its driver runs (else `shared-initialize` had no arm for
  the seeded metaclass). A generic function or method instance never fills through the slot-access
  protocol (`fillsThroughSlotProtocol`).
- **`closer-mop:ensure-class`** is `(eval (defclass ...))`: on the compile paths the `eval` defines
  no class, so `find-class` signals — the class set is fixed there (`doc/**/find-class.md`).
- **`forward-referenced-class`** is a seeded metaclass beside `standard-class`; a class with one among
  its superclasses is not finalized (`%mop-waits-for-a-class-p`). Defined at last it stays the object
  its subclasses hold, also when moved to another name first (McCLIM's `record-presentation-type`):
  `%mop-existing-class`, `%mop-change-metaclass` (`change-class` on the interpreter, a no-op on the
  compile paths: `change-class` in the runtime would give every `:metaclass` program its arms),
  `ClosRegistry.moveDescendants`. Residual: a slot the definition brings is not in those
  descendants' layouts. SBCL 2.2.9 agrees.
- **A method on `compute-slots` adding a slot** (ESA's `modual-class`) gets storage
  (`absorbComputedSlots`, the `%ABSORBED-SLOTS` of "Layered classes"). A method on `make-instance`
  (`constructsThroughGeneric`) sees literal and computed calls. ci-spec standalone
  `compute-slots-and-make-instance-methods-of-a-metaclass`.
- **`closer-mop:class-default-initargs`** over `ClosRegistry.defaultInitargs` (`%CDI-<n>` on the
  compile paths). Readable exports with no operator: `compute-effective-method`,
  `specializer-direct-methods`, `specializer-direct-generic-functions`,
  `compute-applicable-methods-using-classes`.
- Pinned by ci-spec standalone `slot-locations-and-allocate-instance-methods` (SBCL 2.2.9 `sb-mop`
  agrees) and `LispEvaluatorTest.closerMopShimAnswersOverClassMetaobjectsAndLegacyTagDesignators`.

### The metaclass protocol (Phase B) and the MOP widening
A `defclass` with `(:metaclass M)` (`M` descending from `standard-class`) keeps its full static
expansion plus ONE `%ensure-class-with-metaclass` driver call as its last form. Unknown CLASS options
become metaclass initargs whose value is the option TAIL (AMOP, SBCL's `(cdr option)`): any symbol
as option name, a dotted tail allowed (contextl's deflayer). Unknown SLOT options ride the canonical
spec plist as `direct-slot-definition-class` initargs. ci-spec `defclass-metaclass-protocol`.
- The driver and system defaults are Lisp (`macro/mop-protocol.lisp`, `MopProtocol.forms()`),
  SELF-CONTAINED over the `%obj-ref` index contract, defMETHODs only so user hooks defined before
  OR after merge. `finalize-inheritance` runs EAGERLY at definition time (divergence).
- Compile-path gate (`usesMetaclassProtocol` / `namesMopBaseSuperclass`) PREPENDS the protocol
  before the reference scans, so the driver's own `find-class` switches the metaobject runtime on.
  `%register-class-metaobject` prepends so the driver-built instance shadows the plain view.
- **`ensure-class-using-class` routing**: the driver passes the EXISTING driver-built metaobject (nil
  first), so a user `:around` fires on REdefinition, per AMOP. `:direct-superclasses` crosses as
  NAMES, resolved after user `:around`s may munge the list. "Existing" is the protocol's own
  `%mop-ensured-classes%`, deliberately NOT find-class.
- **Metaobject instances fill through the chain**: mop-protocol.lisp's `shared-initialize`
  primaries make every metaobject class one `initializesThroughMethods` answers for, so a user
  `initialize-instance :around`'s MUNGED initargs take effect and a `:before`'s reset of a supplied
  slot (dao-class's `direct-keys`) is overwritten, as in CL.
- **Class REdefinition** reinitializes the SAME metaobject and re-finalizes. **Divergence**: the
  INTERPRETER redefines; the COMPILE paths keep the LAST definition in the static tables
  (`classDefunSlots` nil-s the earlier constructor: two same-name defuns in one class file are a JVM
  ClassFormatError) while BOTH driver calls run. A slot whose INDEX changes poisons `slotPositions`.
  The interpreter registers a redefinition with the PREVIOUS metaobject's class allocations
  (`withPreviousSlotAllocations`) before its driver runs (contextl's singleton
  `reinitialize-instance :after`; spx's `class1`: 1 vs SBCL 2 otherwise).
- **Slot-definition contract: index 5 = INITFUNCTION** (append-only). The driver builds each spec
  with `list` — fresh cells per evaluation, because mito's add-referencing-slots `rplacd`s into
  them. Materialized plain views answer nil (quoted data cannot carry a thunk).
  `class-direct-subclasses` on the interpreter reads the metaobject memo FIRST (a user `:around`
  may have INJECTED a superclass); compile paths: `%CDS-<n>` helpers.
- **Metaobject slots are read/written BY NAME, never by `%obj-ref` index**: a user slot-definition
  class may inherit the base with its own mixin FIRST (mito's `table-column-class`). The index
  contract holds only for code that CONSTRUCTS seeded-layout instances.
- **Injected direct superclasses reconcile the STATIC registration**: the driver-built metaobject is
  the truth, so `evalDefclass` re-registers from `widenDefclassToMetaobjectSupers` (injected supers,
  `:initarg`s an `:around` pushed onto specs that declare none, `:allocation :class` via
  `reconcileSlotAllocations`) and `UserMacroExpander.widenMetaclassDefclasses` rewrites the emitted
  form the same way on the compile paths. The `:around`'s contains-checks make re-running it
  idempotent. On the interpreter the re-registration comes AFTER the driver, so `evalDefclass`
  re-runs `finalize-inheritance` when allocations widened (`widensSlotAllocations`; contextl's
  `special-class`). The canonical spec passes `:allocation` ONLY when written
  (`ParsedSlot.allocation`: contextl's singleton `:around` would signal on an `:instance` default),
  and a class slot's initform is the WRITTEN one (`ParsedSlot.cellInitform`) for `:reinitialize t`.
  Residual: a plain view's `slot-definition-initform` of a class slot is the cell symbol.
- **A driver-built class with no direct superclasses defaults to `(standard-object)`** (AMOP);
  `standard-object` resolves through find-class ONLY. A `defclass` WRITING it as a superclass drops
  it (layout, ancestry, CPL unchanged) and refuses it ahead of another superclass (SBCL's precedence
  error) or twice. Residual: `class-direct-superclasses` of a plain class answers nil.
  `STANDARD-OBJECT`/`STANDARD-CLASS` are in `PackageRegistry.CL_TYPES`, since the compiled
  `%find-class` matches SPELLINGS.
- **The synthesized `shared-initialize` system default FILLS**: mito's `make-dao-instance` is
  `allocate-instance` + `(shared-initialize obj nil initargs)`.
- **`slot-exists-p`**: true when the layout declares the slot, regardless of boundness.
- **An AMBIGUOUS literal slot name outlines onto the shared runtime dispatch**
  (`expandAmbiguousSlotRead`/`Set`): six such reads in `finalize-inheritance` crossed the JVM 64 KB
  limit at ~250 layouts.
- The end-to-end `(ql:quickload '("mito-core" "dbd-postgres"))` + DAO CRUD run is manual on the
  interpreter, the JVM and the WASM component.

### Definition-time method construction (Phase C)
postmodern's `build-dao-methods`: `(funcall (compile nil `(lambda () ,code)))` with nested
`defmethod`s carrying a class METAOBJECT as a literal specializer.
- The evaluator's `compile` built-in folds the metaobject literals of a no-argument definition
  containing a defmethod (`macro/MopEvalCapture`). **Load-bearing ordering**: valid during
  finalization only because the driver registers the metaobject BEFORE `finalize-inheritance`.
- The compile paths' macro-time evaluator records the body (`setMopEvalSpliceSink`); the folded
  forms are spliced right after the triggering defclass.
- Run-time re-execution: the generated `compile` runtime (`macro/CompileRuntime` +
  `compile-runtime.lisp`, in the native-image resource-config) answers a do-nothing function for a
  defmethod-containing definition, else `eval`s — so a method-defining form built from RUNTIME data
  is silently absorbed, the one soft edge. `coercesBuiltLambda` (lisp-unit's `run-code`) counts as a
  compile reference; an ordinary `(coerce f 'function)` stays eval-free. A method under a false
  definition-time guard falls through like a nested method, matching the interpreter.

### Precompiled definitions (`CompiledTemplates`)
The compiled `eval` binds a runtime lambda's parameters positionally (`.kb/eval-runtime.md`), so
contextl's layered-function caller `(compile nil `(lambda (&rest rest) ...))` answered NIL. A
definition the program's own definitions compile is therefore compiled ahead:
- The macro-time `compile` records every method-free definition (`setCompiledTemplateSink`);
  `UserMacroExpander` emits `%register-compiled-template` ahead of the form whose REPLAY compiled it.
  The PATTERN has a hole for each non-literal datum and `(%template-gensym% k)` for other uninterned
  symbols (a match binds distinct k to distinct symbols, so definitions are alpha-equivalent). A bare
  symbol is spelled `cl-user::` unless `cl` owns it, since the registration is read in its package.
- compile-runtime.lisp's `compile` calls the first match's factory on the hole values (`%template-match`);
  anything else is still evaluated (`CompileRuntime.globals()`).
- Pinned by `ContextlE2eTest` on the three compile backends.

### Funcallable instances (`closer-mop:funcallable-standard-class`, djula's templates)
An instance of a `closer-mop:funcallable-standard-object` descendant IS a function (`functionp`,
`function` specializer; a class specializer still ranks first). Against SBCL 2.2.9 identical but for
the printed instance and the condition an unset instance signals.
- **Seeding is lazier than the MOP base's** (`ensureFuncallableClassesSeeded`,
  `FUNCALLABLE_CLASS_SPELLINGS`, `seedMopClassesNamedBy`), under `CLOSER-MOP::` names so a compiled
  `find-class` of the spelled symbol matches. (The plainly-seeded slot-definition classes do NOT:
  `(find-class 'closer-mop:standard-direct-slot-definition)` answers on the interpreter and signals on
  the compile paths.)
- A metaclass descending from FSC APPENDS FSO to the superclasses (AMOP's default), after the
  declared ones so the layout prefix stays.
- **Storage**: `LispLayout.asFuncallable()` adds two cells past the declared slots,
  `FUNCALLABLE_CELL` (-2) and `TAIL_CELL` (-1, a Gray input stream's pushback), counted from the end
  so a change-class target's reserved room keeps the index; `becomeLayout` carries the last TWO cells.
- `set-funcallable-instance-function` wraps a funcallable instance in a lambda, so the cell always
  holds a plain closure and every backend unwraps ONE level.
- **Calls**: interpreter `LispEvaluator.apply`; JVM `JvmFuncallableInstances` (tested only on the
  not-a-function path, so a function value pays nothing); WASM `WasmFuncallableInstances` (prologue
  arm after the closure test; `_apply` needs none). Every arm is gated on
  `ClosRegistry.funcallableTags()`, so a program with no funcallable class is byte-identical.
- Residuals: `(subtypep 'a-funcallable-class 'function)` is nil (SBCL t); an unset instance signals
  the not-a-function type-error (SBCL `unset-funcallable-instance-function`).
- Pinned by ci-spec standalone `funcallable-instances-call-their-installed-function` (standalone: the
  arm joins every dispatcher of its program).

### Classes made at run time (contextl's layer contexts)
A class metaobject made AFTER the static tables were built (contextl's `adjoin-layer-using-class`)
has no tag, layout or ancestor set. Works on all four backends:
- **Its instances** are `%DYNAMIC-OBJECT%`s (`ClosRegistry.DYNAMIC_OBJECT_TAG`, LAYOUT ONLY):
  slot 0 the class metaobject, slot 1 its memoized CPL. No slots of their own.
- **"Made at run time"** = a metaobject `find-class` does not answer for its own name
  (`ClosRegistry.isDynamicClassMetaobject`); an anonymous one is the EMPTY type to the type tables.
- **The runtime** is Lisp (`macro/dynamic-classes.lisp`, `DynamicClasses.forms()`), prepended on the
  compile paths when the program has a `:metaclass` defclass AND an `allocate-instance` reference
  (`ClosRegistry.enableDynamicClasses()`); the interpreter loads it at the first such class
  (`ensureDynamicClassesLoaded`). Every other program is byte-identical. It uses no prelude defun (a
  prelude splice ran before the prepend).
- **Dispatch** (`withDynamicDispatchArm`, standard combination only): with a dynamic argument each
  method's entry is computed at run time (`dynamicMethodEntry`) and `%dynamic-dispatch` combines.
  The entry's `(find-class 'C)` names the REGISTERED spelling (`registeredClassSpelling`): the
  normalized `PKG::NAME` is absent from the compile paths' table for an exported name.
- **class-of** reads slot 0; class tests and both runtime typep shapes consult the CPL;
  **subtypep** goes to `%dynamic-subtypep`; **print-object** routes the dynamic tag when a method has
  a class specializer (`ClosRegistry.printObjectTags`).
- Residuals: `make-instance` of such a class and its slots are out; a short-form combination generic
  gets no arm; a `call-next-method` past the last user primary does not reach a built-in default.
- Pinned by ci-spec standalone `classes-made-at-run-time` (standalone: the arm joins every dispatcher
  with a class method).

### The slot-access protocol (contextl's special slots)
AMOP 5.5 on all four backends: with a method on (or a call of) `closer-mop:slot-value-using-class`,
its `setf`, `slot-boundp-using-class` or `slot-makunbound-using-class` (`SlotProtocol.isProtocolGenericName`;
ci-spec `slot-access-generics-called-with-no-method`), slot access and the accessors of an instance of
a `:metaclass` class call the four generics.
- **The runtime** is Lisp (`macro/slot-protocol.lisp`, `SlotProtocol.forms()`). The system methods
  read storage through `%slot-value-runtime`/`%slot-value-set-runtime`, NEVER through `slot-value`,
  whose expansion would route back. Compile-path gate `namesSlotProtocolGeneric`
  (`ClosRegistry.enableSlotProtocol()`); the interpreter loads it at the first such `defmethod` or
  stub call (`ensureSlotProtocolLoaded`, `SlotProtocol.ENTRY_NAMES`). Every other program is
  byte-identical.
- **Routing** (`slotProtocolRoute`): the static half is the `...Direct` builder, never the surface
  form again (that would expand forever). Generated accessors of a `:metaclass` class route
  (`protocolReadBody`/`protocolWriteBody`) when the gate is on as the class is defined.
- **Construction** (`ClosRegistry.fillsThroughSlotProtocol`): CLHS 7.1.4 through the protocol, in
  SBCL's order (`%slot-protocol-fill`). With an initialization generic it is the CHAIN FILL, so a user
  `shared-initialize` that never calls the next method (contextl's on `special-object`) is the only
  fill, as in CL.
- **An inherited initform**: a plain superclass's slot is a plain view with no initfunction, so a
  protocol-filled class runs `%slot-protocol-inherit-initfunctions` after its driver. SBCL 2.2.9 agrees.
- Residuals: a class with `:default-initargs` in its precedence list keeps the constructor fill
  (`markDefaultInitargs`; driver-built initfunctions know no default initargs) then re-fills (extra
  writes, same values); a COMPUTED-class `make-instance` with no initialization generic keeps the
  constructor in its compiled arm; `slot-exists-p` is not routed; an interpreter accessor defined
  before the first protocol method is not routed; an inherited plain class's accessor is not routed
  for a `:metaclass` subclass instance.
- Pinned by ci-spec standalone `slot-access-protocol-methods` (SBCL 2.2.9 `sb-mop` agrees) and
  `ContextlE2eTest` (special slots, `dletf`, captured dynamic environments).

### The generic-function protocol (method metaobjects at run time)
On all four backends (`macro/generic-functions.lisp`, `GenericFunctions`): `ensure-generic-function`,
`add-method`/`remove-method` (generics, so contextl's `:after` methods merge), `find-method`,
`compute-applicable-methods`, `ensure-method`, the readers, `intern-eql-specializer`.
- **The decision**: first-class generic function and method METAOBJECTS with a run-time method table
  the existing dispatchers consult, NOT a narrow `ensure-method` over the dispatcher. The consumers
  settled it: contextl makes generics with `:generic-function-class`, reads
  `generic-function-method-class` and applies the generic object; its layer GC is an `add-method
  :after` reading `method-specializers`; mito and rutils `add-method` a `(make-instance
  'standard-method :function <closure>)`, which no definition-time capture can serve. Every
  generic's value stays the compiled dispatcher.
- **Gate** (`usesGenericFunctionProtocol`): a program naming an operator (quoted data included:
  `(fboundp 'find-method)` answers t as on the interpreter) or a `defgeneric` with
  `:generic-function-class`/`:method-class`. The interpreter's loading stubs (`ENTRY_NAMES`) are
  unbound before the runtime loads, or `add-method`'s dispatcher would stash a stub as its built-in
  default and recurse.
- **A generic function metaobject** is a funcallable instance memoized by name (`%gf-metaobjects%`);
  `%STATIC` holds the compiled dispatcher. `ensure-generic-function` installs it as the name's
  function, so a COMPUTED `fdefinition` answers it; the compile paths' direct calls, `#'g` and a
  literal `fdefinition` keep the compiled function.
- **Dispatch** (`withDynamicDispatchArm`): every dispatcher reads `(%gf-runtime-methods 'G)` first;
  nil keeps the compiled body. Otherwise entries are computed (`%class-key`, `%gf-runtime-entry`,
  `BUILT_IN_TYPE_RANKS` via `classKeyRanksForm`) and `%dynamic-dispatch` combines. A run-time
  method's function is AMOP's `(args next-methods)` with method metaobjects as next methods
  (`%gf-next-method`).
- **A `defmethod` reaching `add-method`**: on a generic whose `defgeneric` named a metaobject class,
  each `defmethod` is followed by `%gf-static-method-added` (`LispMacroExpander.staticMethodAdded`);
  its `%STATIC-KEY` keeps it out of the run-time table. contextl's layer GC clears its caches there.
- **An eql-selected argument takes the run-time combination** whenever the arm exists and the
  generic combines: the static eql branch admits only same-specialized methods, so contextl's
  `(eql (find-layer-class 'bar))` `:before` lost the class primary.
- **`make-instance` with a user method**: on the compile paths its dispatcher defaults to
  `%mop-make-instance`; `make-instance` is `NOT_SHADOWABLE`, so nothing else gave it a default.
- **A method function from a lambda expression** (`%gf-method-function`): its variables are gensyms
  and `%gf-call-next` goes in as a function OBJECT, so the definition spells no symbol of this
  runtime, which a precompiled definition would read in the program's package ("Precompiled
  definitions").
- **`find-method`/`remove-method` take the generic's function** (`%gf-designated`; Drei's
  `lisp-syntax-swine.lisp` removes a method through `#'name`).
- **`compute-applicable-methods`** sorts as `%dynamic-dispatch` does, newest first between equals as
  SBCL. `%gf-static-methods` (`LispMacroExpander.staticMethodsForm`) has arms only in a program
  naming `compute-applicable-methods` (they keep every generic alive). No dispatcher consults it, so
  a method on it changes only what its caller sees (McCLIM's `:around`, "Out of scope"). A function
  that is no generic answers nil where SBCL signals. Trap: the walk records a dispatcher under the
  normalized `PKG::NAME` while an exported generic keeps `PKG:NAME` (fset's `compare` answered 0
  when the two missed each other).
- **`:argument-precedence-order`** (`GenericInfo.precedenceIndexes`) orders both the static
  dispatchers and the run-time keys; contextl puts the layer argument LAST. Pinned by ci-spec
  `clos-defgeneric-defmethod-eql-dispatch` (`clos-apo`, SBCL agrees).
- Residuals: `remove-method` of a `defmethod`'s method drops only the metaobject; a short-form
  combination generic ignores run-time methods; a standard generic's `generic-function-methods`
  lists only run-time methods. A generic with run-time methods computes every entry on every call
  (no effective-method cache): the figure editor's 1000 runs do not finish in minutes.
- Pinned by ci-spec standalone `generic-function-protocol` (SBCL 2.2.9 + closer-mop agrees) and
  `ContextlE2eTest` (contextl's `test/layer-gc.lisp`).

### Layered classes (contextl's `define-layered-class`)
On all four backends: layered accessors, partial classes, special slots a later definition adds.
- **Partial classes**: each `define-layered-class C :in L` puts an ANONYMOUS slot-carrying class per
  layer in front of C's direct superclasses. `widenDefclassToMetaobjectSupers` reconciles the static
  side: `%ABSORBED-SLOTS` and `%STATIC-SUPERCLASSES` (also mito's injected superclasses). **The
  driver keeps the WRITTEN superclasses**: handing it the widened list gave contextl
  `(partial-object)` instead of `(info-mixin)` and lost the class a layer adds.
- **The metaobject side** (mop-protocol.lisp): `finalize-inheritance` finalizes an unfinalized
  superclass first and takes `compute-slots` (a generic; merges a slot two classes define, CLHS
  7.5.3); `%mop-refinalize`. A redefinition re-primes the `find-class` memo BEFORE
  `reinitialize-instance`, else `class-of` answered the plain view and contextl's singleton `:after`
  wrote a special slot's cell directly (spx's `layer3`).
- **`:default-initargs`** (CLHS 7.1.3) along the precedence list (`%MMI-APPEND-DEFAULTS`).
- **`slot-definition-name` is the slot's own symbol**, not the base name homed in `cl-user` (a
  precompiled accessor's `'name2` never matched). Pinned by ci-spec `slot-access-protocol-methods`
  (`sap-other::v`).
- **MI refinement over an exported class name**: `miBranchRep` keys normalized names.
- **`call-next-method` a macro builds**: the interpreter expands the body's user macros first when
  it names `call-next-method`/`next-method-p` (`withNextMethodMacrosExpanded`). `#'call-next-method`
  as a VALUE is a closure.
- Residuals: a layered class defined inside a function or an `eval` (no static side on the compile
  paths); `fe`'s full run time (above).
- Pinned by `ContextlE2eTest` (demo3's classes, a special slot added to an existing instance,
  `slot-value-using-layer`, the figure editors' `call-next-method`).

### Instance updating (CLHS 4.3.6)
A redefinition changing a class's local slots, or `make-instances-obsolete`, updates existing
instances through `update-instance-for-redefined-class` (contextl's special slots).
- **Interpreter**: `LispEvaluator.trackInstance` weakly tracks every instance of a `:metaclass`
  class; after the outermost `defclass` (`classDefinitionDepth`) affected instances move by slot name
  (`LispInstance.replaceStorage`). Added slots are the LOCAL ones.
- **Compile paths**: a class has its LAST layout from the start, so what an earlier instance lacks is
  the initialization. A `:metaclass` class defined more than once at top level
  (`trackRedefinedClassInstances`) records its instances at allocation until its last definition has
  run (`%end-instance-tracking`); `%update-tracked-instances` (CLHS 4.3.6.1). Its property list is
  EMPTY: a dropped slot never had storage there.
- **`update-instance-for-redefined-class` is a system generic** (`CALLABLE_SYSTEM_GENERICS`,
  `synthesizeUpdateInstanceDefault`); as a prelude `defun` a user `:before` method shadowed it.
- **`%mop-class-finalized`** is a generated hook (`mopClassFinalizedDefun`): a `(boundp
  '%dynamic-cpls%)` probe in mop-protocol.lisp put the eval runtime into every `:metaclass` program,
  since a `boundp` the injected runtime carries escapes `CompileTimeBoundp`.
- **Scoped out on the compile paths, by measurement**: `make-instances-obsolete` stays a no-op and an
  `eval`'d `defclass` defines no class. Their one consumer, contextl's `special-slots-1/2/3`,
  produces no output in 120 s on the JVM (the handler's `continue` re-enters the `dletf`'s check);
  nothing in contextl makes a slot special by a top-level redefinition.
- Pinned by ci-spec standalone `instance-updating-of-a-redefined-metaclass-class` (SBCL 2.2.9 agrees)
  and `ContextlE2eTest` (all four),
  `anEvaluatedRedefinitionMakesAnExistingInstancesSlotSpecial` (interpreter).

### Registry-growth lesson
An inline per-site RUNTIME-slot-name dispatch grows with every layout times its slots; five extra
ci-spec classes pushed a corpus body past the JVM's SIGNED 16-BIT branch encoding (32 KB, hit before
the 64 KB method cap). Outlined into shared defuns, CHAINED-CHUNKED by cons-node budget
(`chainedDispatchDefuns`); a dispatch that fits stays one defun. Top-level compile crashes name the
offending form (`JvmLispCompiler` chunk-loop wrapper).

## Out of scope / known gaps
- An eql-specialized qualified method combines only with same-specializer primaries + the default
  method.
- Still OUT of the MOP (classes are compile-time-static; `--optimize` DCE and the dispatch tables
  depend on it): runtime class construction beyond `make-instance` of a metaclass ("Classes made at
  run time": `ensure-class` from computed data, a run-time class with slots), a method on
  `update-instance-for-different-class` (`change-class` inlines its system method). IN: a literal
  `defclass` below the top level, methods made at run time ("The generic-function protocol"), class
  REdefinition of a statically-known name; redefinition from computed data is out.
- **Known static-model seam**: on the compile paths `find-class`/`class-of` see the WHOLE program's
  classes regardless of form order; the interpreter only those already defined. `change-class` is
  the ONE runtime exception and is not MOP.
- **A generic's VALUE is still a plain function** (`#'g`, a literal `fdefinition`) outside the
  generic-function protocol: access's `etypecase` takes the function arm, trivia's
  `unary-function-p` its test-call arm. Unserved consumers in the local Quicklisp cache:
  moptilities' method side, cl-store, trivial-arguments, documentation-utils, clunit2, in-nomine
  (`find-method` of a `defmethod`'s method, which only a generic naming its metaobject classes lists).
- **McCLIM (interpreter)**: `(ql:quickload :clim)` loads (`clim-core`, `drei-mcclim`) in under 3
  minutes and a 46-form exercise answers as SBCL 2.2.9 but for addresses and five error wordings;
  nothing needing a port was run. Its `compute-applicable-methods :around` has no effect (harmless
  for Core's one such method). A `macrolet` body runs as top-level forms
  (`LispEvaluator.evalMacroletForm`, CLHS 3.2.3.1).
- **contextl**: loads on all four backends (`ContextlE2eTest`, vendored contextl + lw-compat, in
  `contextl-user`). `--feature cx-disable-layer-gc` is the spelling of its documented switch: a
  `(push :cx-disable-layer-gc *features*)` in the program reaches only that program's own `#+`
  (`.kb/reader-features.md`), never a loaded system's files. Its `test/` passes whole on the
  interpreter (figure editors `fe`/`fe2` at 3 runs instead of 1000) and on the three compile backends
  but for `special-slots-1/2/3` ("Instance updating"). The full 1000-run `fe` is a benchmark: on the
  JVM the non-layered halves took 172 s each and the layered half did not finish in 500 s (no
  effective-method cache). `dynenv` on the compile paths needs `assert`'s default message to double
  the tildes of the test it quotes (`.kb/pure-builtin-fold.md`).
- **lisp-binary**: loads on all four backends, the WASM pair with `--feature lisp-binary/no-cffi`
  (cffi needs `ffi:`, which no `.wasm` has).
- **`(setf class-name)`** renames the METAOBJECT (AMOP) and leaves the class table alone. ci-spec
  `classes-made-at-run-time`.
- **A slot-definition metaobject's name is the slot's SYMBOL** (`SlotSpec.name`), not `baseName`
  (`ClosRegistry.slotDefinitionInstance`, `LispMacroExpander.slotDataRow`): lisp-binary's
  `bind-class-slots` binds those names. Slot LOOKUP stays by base name. Pin: ci-spec
  `slot-value-with-a-package-qualified-runtime-slot-name`.
- eql specializers on strings. The `:type` slot option is RECORDED only (`SlotSpec.type`). Compiled
  runtime `eval` cannot define classes/methods or use `make-instance`/`slot-value`
  (doc/en/guides/eval-limitations.md). `--no-gc` rejects via its generic top-level error.
- `defclass`/`defgeneric`/`defmethod` are in `PackageRegistry.CL_SPECIAL_FORMS`;
  `make-instance`/`slot-value`/`with-slots`/`with-accessors`/`change-class` in `CL_MACROS` — pinned in
  ci-spec (`rontolisp-package-introspection`), the three backend tests and the doc pages; update all
  together.

## Tests
Core: `LispEvaluatorTest#defgeneric*`/`defclass*`/`defmethod*`/`closInUserPackage`,
`JvmLispCompilerTest#compileAndRunDefgeneric*`/`compileAndRunDefclass*`/
`compileAndRunMacroCallingGenericAtExpansionTime` and the WASM twins,
`UserMacroExpanderTest#defmethodLambdaListStaysVerbatim*`/`defclassKeepsNamesAndOptions*`/
`macroBodyMayCallAGenericFunctionAtExpansionTime`,
`LispMacroExpanderTest.theDispatcherLastResortIsOneCallOfTheSharedNoApplicableMethodSignal`,
`WasmLispCompilerTest.aSlotAccessorDispatcherDoesNotCarryItsOwnCopyOfTheNoApplicableMethodTail`, the
`applyAlignedVariadicTarget`, `PackageIsADefmethodSpecializer` and
`defmethodEqlSpecializerNamingAConstant` pairs, plus `aCapturedGenericFunctionValueSeesLaterMethods`
and `initProtocolGenericsAreSharedAcrossPackages`. Congruence: `LambdaListCongruenceFixture`
(SBCL-classified) in `LispEvaluatorTest`, `JvmLispCompilerTest` and
`WasmLispCompilerIntegrationTest` (P1 + component), ci-spec `clos-defmethod-lambda-list-congruence`
and standalone `uncaught-defmethod-lambda-list-refusal`.

Feature trios (interpreter + `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`): multiple
inheritance / diamond / inconsistent precedence, setf methods, `setfFindClass`,
`defmethodOnABuiltinName`, computed `typep` and `error` types, runtime class designators, computed
`change-class`, `withSlots` write-only + nested capture, writer / class-allocation / `standard-class`
typecase, short-form method combination, `*FindClass*` / `*CloserMopShim*` / `classOf*` /
`allocateInstance*` / metaobject type specifiers / `defclassMetaclass*` /
`compileCoercesALambdaExpressionToAFunction` / `compileInterceptsDefinitionTimeMethodConstruction` /
`defclassMetaclassEnsureClassUsingClassAndInitargMunging` (sharing
`MopWideningFixture.MITO_SHAPE_SOURCE`) / `slotExistsPAnswersDeclaredSlotsRegardlessOfBoundness`,
`*PrintObjectMethodDefinedBelowItsFirstUse` / `*NestedDefmethodCallBeforeItsFormFallsThrough*`. Plus
`ShadowedBuiltinsTest`, `FastIoCircularStreamsE2eTest`, `JzonE2eTest`, the PostmodernE2eTest DAO leg,
`ContextlE2eTest` (the MOP protocols, layered classes, instance updating, precompiled definitions).

ci-spec: `clos-defgeneric-defmethod-eql-dispatch`, `clos-defclass-slots-inheritance-and-dispatch`,
`clos-method-qualifiers-and-call-next-method`, `clos-multiple-inheritance-cpl-slots-and-dispatch`,
`clos-setf-methods-and-setf-generic`, `clos-computed-change-class`, `clos-reinitialize`,
`an-initialization-method-runs-before-the-fill`, `clos-slot-options-and-metaobject-types`,
`clos-defmethod-eql-specializer-over-a-constant`, `clos-defmethod-bit-vector-specializer`,
`clos-print-object-method-defined-below-its-first-use`,
`classes-and-methods-defined-below-the-top-level`, `package-defmethod-specializer`,
`defmethod-on-a-builtin-name-keeps-the-builtin`, `defgeneric-short-form-method-combination`,
`find-class-metaobject-substrate`, `defclass-metaclass-protocol`, `mop-widening-for-mito`,
`compile-definition-time-method-construction`, `runtime-type-dispatch-and-symbol-designators`,
`runtime-type-dispatch-residue`, `runtime-class-designator-spellings`,
`with-slots-write-only-unbound-slot-and-missing-slot`, `array-operations-enablement-language-group`,
`mito-core-enablement-language-group`, standalone `funcallable-instances-call-their-installed-function`,
`classes-made-at-run-time`, `slot-access-protocol-methods`, `generic-function-protocol` and
`instance-updating-of-a-redefined-metaclass-class`. Plus the five `doc/*/reference/**` pages via
`DocExamplesTest`.
