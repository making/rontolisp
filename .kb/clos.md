# CLOS static subset — defclass / defgeneric / defmethod / make-instance / slot-value

User-facing behavior: `doc/en/reference/special-forms/{defclass,defgeneric,defmethod}.md`,
`doc/en/reference/macros/{make-instance,slot-value}.md`, and the missing-features
guide (what is out of scope: runtime class ops permanently). Stages 1+2+3 DONE
(2026-07-06): dispatch + standard method combination (`:before`/`:after`/`:around`
+ `call-next-method`/`next-method-p`). Multiple inheritance DONE (2026-08-02,
todo-232) — see "Multiple inheritance" below.

## Design: the defstruct pattern, one shared registry, one shared dispatcher generator

Everything expands to plain defuns via `LispMacroExpander` (no backend codegen):

- `expandDefclass(cons, closRegistry, structAccessors)` — registers a
  `ClosRegistry.ClassInfo` and generates the internal keyword constructor
  `%make-<name>` (`&key ((:initarg slot) initform)...` over the FULL slot list)
  plus one synthesized `(defmethod R ((__obj C)) (nth pos __obj))` per
  `:reader`/`:accessor` — a METHOD, not a plain defun, because several classes
  may declare the same reader name over DIFFERENT slot positions (cl-ppcre's
  `len` in str/repetition/lookbehind/filter) and a user `defmethod` on the same
  name must merge instead of shadowing. The write side of an `:accessor` is a
  `%setf-<name>` writer GENERIC (`(defmethod %setf-A (__new (__obj C)) ...)`,
  new value first) registered in `structAccessors` under
  `SETF_FUNCTION_MARKER`, so `(setf (A x) v)` funcalls the writer dispatcher.
  The interpreter's `evalDefclass` just evals the returned forms (a defmethod
  routes through `evalDefmethod`); the compile path's
  `expandTopLevelDefinitions` sends them through `addExpandedDefinition` (same
  expansion + dispatcher-slot placement as a top-level defmethod). A
  position-ambiguous `slot-value`/`with-slots` name falls back to the reader
  generic (`expandSlotValue`); its setf re-dispatches through the writer.
  `initialize-instance` is supported in the `:after` shape: the FIRST user
  method on it also synthesizes an identity default primary (no system primary
  exists), and `expandMakeInstance` — when any generic plainly named
  `initialize-instance` is registered — hoists the initargs into let* temps,
  calls the constructor, then the generic, and returns the instance. An
  instance is a first-class object built by `(%obj-new '%class-<name> v...)`
  (`.kb/instance-syntax.md`), NOT a list; layout = superclass slots
  (inheritance order) + own slots, so single inheritance keeps slot indexes
  stable in all descendants, and a reader/writer body is
  `(%obj-ref __obj i)` / `(%obj-set __obj i __new)`.
- `registerDefgeneric` / `expandDefmethod` — record into
  `ClosRegistry.GenericInfo` (methods keyed by `qualifier + specializer`, so
  same-qualifier-same-specializer redefinition replaces while a `:before dog`
  and a primary `dog` coexist); each defmethod becomes a plain defun
  `%<generic>--m<i>` (body kept verbatim: a leading docstring is evaluated and
  discarded, `declare` expands to nil). Stage 3: an optional `:before`/`:after`/
  `:around` qualifier precedes the lambda list (stored on `MethodInfo.qualifier`,
  `""` = primary), and EVERY method-body defun gains a leading `%next-method`
  thunk parameter — `rewriteNextMethod` turns `(call-next-method args...)` into a
  guarded `(if %next-method (funcall %next-method args-or-current-params) (error))`
  and `(next-method-p)` into `(not (null %next-method))`. `call-next-method`/
  `next-method-p` are matched by package-stripped name (NOT in `CL_SYMBOLS`, so no
  introspection churn) and are rewritten away before any backend sees them.
  `MethodInfo.usesNext` records whether a body mentions them.
- `generateDispatcher(name, registry)` — ONE dispatcher defun per generic: a
  nested-if chain over the methods, most specific first. Specializers may sit on
  ANY required parameter; methods order by comparing parameters leftmost-first
  with `specializerRank` per parameter (eql 0, classes 10..99 by descending
  ancestor-set size = subclass first, built-in types 200s with subtypes like
  `null`/`keyword`/`integer` before `symbol`/`number`/`list`, default 1000;
  stable sort keeps definition order within a rank), and a branch tests every
  specialized parameter. A generic whose lambda list continues past the
  required params (`&optional`/`&rest`) gets a variadic dispatcher that
  forwards the tail to the selected method via `apply` (`call-next-method`
  there forwards the required args only). `defgeneric` inline
  `(:method [qualifier] (params) body...)` clauses register like separate
  defmethods (`registerDefgeneric` collects their method defuns;
  `expandTopLevelDefinitions` splices them on the compile path). Falls back to
  the default method or
  `(error "No applicable method: <name>")`. eql/built-in tests reuse
  `makeTypeTest`-family helpers (`makeEqlSpecializerTest`: symbols/keywords
  compare with `equal` — content-safe on WASM — numbers/characters with `eql`);
  a class test is `(%obj-is x '%class-C ...)` over the statically-known
  descendant tags. The dispatcher is an ordinary defun, so
  `#'name`/`funcall`/mapcar work with no `BuiltinFunctionWrappers` entry.
  - Two bodies: `simpleDispatchBody` (unchanged single-call-per-branch) is used
    when the generic has NO qualifier and NO `call-next-method` usage; otherwise
    `combinedDispatchBody` emits standard method combination. Combined = one
    branch per distinct specializer (`specKeyOf`, qualifier-independent) plus the
    default fallback; each branch's value is `effectiveMethod(branchRep, ...)`.
  - `effectiveMethod` collects the applicable methods per role (`applicableMethods`
    filters by `appliesToBranch`: default methods always apply; a class method
    applies to a class branch whose class has it as an ancestor; a STRUCT (type)
    method applies to a branch struct that `:include`-descends from it, via the
    spelling-tolerant `descendantStructTags` — sxql's yield methods over the
    sql-statement `:include` tree, todo-244; eql and built-in type methods apply
    only to their exact-same branch — cross-type subtyping among THEM stays out
    of scope),
    then composes them: `:around` (most specific first) wrap a `coreThunk`; the
    core runs `:before` (msf, for effect), the primary chain (msf, value kept via a
    `%clos-result` let), then `:after` (LEAST specific first). The primary/around
    chains are built by `buildNextChain` as nested `(lambda (params) (%m next
    params))` literals passed as each method's `%next-method` — NO free-variable
    capture (each lambda re-binds params, method names are global), so it is just
    first-class `lambda`+`funcall`, well supported on all backends. Base next =
    `nil` for the innermost primary (so `next-method-p` is nil / `call-next-method`
    errors) and the `coreThunk` for the innermost around.

`ClosRegistry` (in `am.ik.rontolisp`) holds classes, generics, and
`slotPositions` (slot base name -> 1-based position, `-1` when unrelated classes
disagree — `slot-value` then errors "use the accessor"). It lives per evaluator
(`LispEvaluator.closRegistry`) and per compilation (`Jvm/WasmLispCompiler.Ctx`,
threaded through `Ctx.Builder` beside `structAccessors`).

## Multiple inheritance (todo-232, 2026-08-02)

`ClosRegistry.ClassInfo` carries `superclasses` (local precedence order), `cpl`
(the class precedence list -- CLHS 4.3.5 topological sort in
`LispMacroExpander.computeCpl`, inconsistent local orders are an
`IllegalArgumentException`) and `directSlots` (the class's OWN specs, for the
CPL option merge) beside the effective `slots` and the ancestor SET. The ~20
consumers that only ask "is X an ancestor?" (descendant tags, typep/subtypep
tables, refill targeting, ...) ride the set unchanged.

- **Layout rule**: the FIRST superclass's effective slots keep their indexes
  (the prefix rule single inheritance already had), each later superclass
  appends its not-yet-present slot names, a diamond keeps ONE copy. Inherited
  slot OPTIONS are then re-merged from the direct specs along the CPL
  (`cplMergedSlot` + `shadowSlotSpec`), so a non-first superclass's
  re-declaration still beats the shared base's.
- **Shifted accessors**: a non-first superclass's readers/accessors bake THEIR
  index and their class test covers the subclass, so `expandDefclass`
  synthesizes overriding reader/accessor (+ `%setf-` writer) methods
  specialized on the SUBCLASS for every inherited slot whose index differs in
  any CPL ancestor -- subclass-first dispatch makes them win. Single
  inheritance never shifts, so nothing is synthesized there.
- **Dispatch refinement** (`miRefinement`, gated on
  `ClosRegistry.hasMultipleInheritance()` so single-inheritance dispatchers
  stay byte-identical): a registered class whose applicable class specializers
  have NO single most-specific member (unrelated supers, e.g. `(a b)` with
  methods on both) gets an EXACT-TAG branch -- `(%obj-is x '%class-X)`, X
  alone -- placed by the same specificity sort. Simple body: the branch calls
  the method X's CPL ranks first. Combined body: the branch's effective method
  is computed against X (complete applicable set; per-branch method order via
  `branchSpecificityOrder`, which ranks class specializers by the branch
  class's CPL index). Classes WITH a dominator need no branch: the dominator's
  own branch sorts first and `appliesToBranch` over its ancestors is complete.
  LITE residual: the refinement handles class dispatch on ONE parameter
  position (every class-specialized method must specialize only that
  position); a diamond-affected class meeting a generic that class-dispatches
  on several positions keeps the per-specializer branches, which can miss the
  second super's methods there. Re-evaluate if a library hits it.
- `conditionReportGroups` inherits `:report` along the CPL;
  `define-condition` with several REGISTERED parents now does real MI (slots
  inherited; an UNREGISTERED extra parent still falls back to the
  ancestors-only `registerExtraAncestors` route); `change-class`'s initform
  fill SKIPS a source whose layout is not a base-name prefix of the target
  (degrades to the layout-swap-only behavior unrelated classes get);
  `%class-meta-table%`'s superclass column is a LIST (consumed by the
  `%find-class-materialize` dolist), and mop-protocol.lisp's
  `finalize-inheritance` merges inherited effective slots across ALL direct
  supers (first occurrence of a name wins, like the static layout merge).

Pinned by `LispEvaluatorTest#defclassMultipleInheritance*`/`#defclassDiamond*`/
`#defclassCircularSuperclassesSignalInconsistentPrecedence`,
`JvmLispCompilerTest#compileAndRunDefclassMultipleInheritance*`,
`WasmLispCompilerIntegrationTest#compileAndRunDefclassMultipleInheritance`, and
the `clos-multiple-inheritance-cpl-slots-and-dispatch` ci-spec case (all four
backends).

## MOP protocol widening for mito (todo-246, 2026-08-03)

Extends the Phase B metaclass protocol (see the MOP-boundary section below) to
mito's `table-class`/`dao-table-class`/`dao-table-mixin` shapes
(class/table.lisp, dao/table.lisp, dao/mixin.lisp) and its `table-column-class`
slot-definition subclass (class/column.lisp). Five pieces, landed together:

- **`ensure-class-using-class` routing.** `%ensure-class-with-metaclass` no
  longer builds the class itself: it applies
  `closer-mop:ensure-class-using-class` with the EXISTING driver-built
  metaobject (nil on first definition), the name, and
  `:metaclass`/`:direct-superclasses` (NAMES -- resolution happens inside the
  chain, after user :arounds may munge the list)/`:direct-slots` + the class
  initargs. The system default method (mop-protocol.lisp) takes the make path
  for nil and the reinitialize path for a class -- so a user `:around`
  specialized on a metaclass (mito's dao-table-class superclass injection)
  fires on REdefinition, per AMOP. "Existing" is tracked in the protocol's own
  `%mop-ensured-classes%` alist, deliberately NOT via find-class: the static
  class table answers a materialized plain view for a class whose driver call
  has not run yet, so find-class cannot tell "already ensured" from
  "statically known".
- **Chain-fill initialization of METAOBJECT instances.** For a class whose
  ancestors include a seeded MOP base class -- and only when the protocol is
  loaded (`ClosRegistry.isMopProtocolActive`, set by the interpreter's protocol
  load and the compile paths' prepend) -- `expandMakeInstance` and the generated
  `%mop-make-instance` arms allocate the instance UNBOUND (`%obj-new` of unbound
  markers) instead of calling the keyword constructor, then run the
  initialization generic on it; the system `shared-initialize` primaries
  (mop-protocol.lisp, specialized on `standard-class` and the two
  slot-definition base classes) perform the initarg fill via `%mop-fill-slots`
  (interpreter: registry-backed builtin, stays correct as classes accrue;
  compile paths: generated per-class dispatch chunked into `%MOP-FILL-<n>`
  helpers; slot-names nil = supplied initargs only, non-nil = plus initforms
  for still-unbound slots -- unsupplied-no-initform stays UNBOUND, mito's
  col-type `slot-boundp` rides that). This is what makes a user
  `initialize-instance :around`'s MUNGED initargs take effect (mito rewrites
  `:direct-superclasses`/`:direct-slots`; its `table-column-class` :around
  pushes a default initarg into a slot spec's `:initargs`): CL's ordering
  (:before -> fill) holds natively here, so metaobject classes are EXCLUDED
  from the initarg re-fill replay -- the refill stays the mechanism for
  REGULAR classes only, whose constructor still fills first (that divergence
  stands; its reason, the static constructor model, is unchanged). The
  standard-class primary additionally -- INSIDE the chain, so an :around's
  post-`call-next-method` code already sees the results -- resolves
  `:direct-superclasses` designators (names or metaobjects; mito pushes
  `(find-class 'dao-class)` instances) into metaobjects on slot 1, and converts
  the `:direct-slots` canonicalized spec plists into direct-slot-definition
  metaobjects on slot 2 through `direct-slot-definition-class` +
  `%mop-make-instance` (which recurses into the same chain, so slot-definition
  :arounds fire too).
- **Class REdefinition (metaclass classes).** Re-evaluating a
  `defclass ... (:metaclass M)` for an existing name reinitializes the SAME
  metaobject (identity survives, per AMOP: `reinitialize-instance` ->
  shared-initialize with slot-names nil), re-registers it in the find-class
  memo (the registry's re-registration had invalidated it) and re-finalizes.
  Scope + divergence: the INTERPRETER does real redefinition (registry
  re-registers, constructor/accessors re-evaluate); the COMPILE paths see the
  program statically -- the static tables (layout, typep tags, baked accessor
  indexes, constructor) keep the LAST definition
  (`expandTopLevelDefinitions` nil-s the earlier defclass-generated
  constructor defun via `classDefunSlots`; two same-name defuns in one class
  file are a JVM ClassFormatError) while BOTH driver calls still run in
  top-level order (first creates, second reinitializes), so the metaobject
  protocol observably matches the interpreter. Reason for the divergence:
  classes are compile-time-static (dispatch tables, `--optimize` DCE);
  re-evaluate if a library uses the FIRST layout before redefining. On every
  path, existing instances are not updated
  (`update-instance-for-redefined-class` stays out), and a slot whose INDEX
  changes between definitions poisons the shared `slotPositions` map exactly
  like two unrelated classes disagreeing (use the accessor).
- **Slot-definition contract: index 5 = INITFUNCTION** (append-only contract,
  both slot-definition base classes). The driver call builds each canonicalized
  spec with `list` -- fresh cells per evaluation, because mito's
  add-referencing-slots `rplacd`s ghost markers into them -- carrying
  `:initfunction (lambda () initform)` (nil when no initform), and the default
  `compute-effective-slot-definition` copies it onto the effective slot. Filled
  only on DRIVER-built definitions: the materialized plain views (interpreter
  `classMetaobject`, compiled `%find-class-materialize`) answer nil there --
  the meta table is quoted data and cannot carry a live thunk. Shim additions
  (closer-mop.lisp): `slot-definition-readers` (index 4),
  `slot-definition-initfunction` (5), `class-direct-slots` (2),
  `class-direct-subclasses` (next bullet).
- **`class-direct-subclasses`** rides the internal `%class-direct-subclasses`:
  interpreter = `ClosRegistry.directSubclassNames` -- the metaobject memo's
  direct-superclass lists FIRST (a driver-built instance may carry a
  superclass a user :around INJECTED, which the static registry never sees --
  mito's dao-class push), then the static registry's declared superclasses;
  compile paths = a generated defun (gated on the reference; forces the
  metaobject runtime, whose `%class-metaobjects%` memo it scans) plus chunked
  static-arm helpers (`%CDS-<n>`), names materialized through `%find-class`.
- **`(apply #'call-next-method ...)` / `(funcall #'call-next-method ...)`** are
  rewritten by `rewriteNextMethod` onto the guarded `%next-method` thunk with
  the explicit arguments -- mito spells EVERY :around's chaining this way;
  before this only the head-position `(call-next-method ...)` call was
  rewritten and the apply spelling died on an undefined function.

Pinned by `defclassMetaclassEnsureClassUsingClassAndInitargMunging`
(`LispEvaluatorTest`; `compile`-prefixed in `JvmLispCompilerTest`; same name in
`WasmLispCompilerIntegrationTest` -- all three share
`MopWideningFixture.MITO_SHAPE_SOURCE`) and ci-spec `mop-widening-for-mito`
(all four backends): e-c-u-c :around superclass injection +
initialize-instance :around initarg munging on the metaclass AND on a
slot-definition class + custom slot classes with an extra col-type slot +
initfunction readback + same-name redefinition.

## Setf methods -- `(defmethod (setf name) ...)` / `(defgeneric (setf name) ...)` (todo-232, 2026-08-02)

`normalizeSetfMethodForm` rewrites the definition onto the `%setf-` writer-generic
convention the defclass `:accessor` writers already use: the name becomes the
mangled `%setf-<place>` symbol (`setfFunctionName`) and the place joins
`structAccessors` under `SETF_FUNCTION_MARKER`, so `(setf (name args...) v)`
funcalls the writer dispatcher (new value first) with NO change to `expandSetf`.
A user setf method therefore MERGES with accessor-generated writer methods on the
same generic (different specializer) or replaces one (same specializer, CL
redefinition semantics). Normalization sites: `expandTopLevelDefinitions`'s
defmethod AND defgeneric branches, the let-nested walkers
(`expandLetNestedDefmethods`/`rewriteNestedDefmethods`, which now thread
`structAccessors`), and the interpreter's `evalDefmethod`/`evalDefgeneric` -- all
BEFORE anything casts the name position to `LispSymbol`. `#'(setf name)` resolves
to the dispatcher through the existing setf-function route (it is just
`%setf-name`). Package-qualified places produce `%setf-PKG::NAME`, the exact
shape the defclass accessor writers have exercised since cl-ppcre. Pinned by
`LispEvaluatorTest#setfMethod*`/`#defgenericSetfNameWithInlineMethod`,
`Jvm/Wasm*#compileAndRunSetfMethodDispatchesPerClass`, and ci-spec
`clos-setf-methods-and-setf-generic`.

## `(setf (find-class 'alias) (find-class 'target))` -- class name aliases (todo-242, 2026-08-02)

**Invariant: an alias is a second NAME for one class, never a second class.** The
registry keeps `ClosRegistry.classAliases` (alias -> the target's CANONICAL name,
resolved at registration time so the map is one level deep) and `findClass` consults
it right after the exact-name lookup, ahead of the package-tolerant fallbacks --
`aliasTarget` matches the exact spelling, the qualified spelling's member, then a
UNIQUE member match over the alias table, mirroring `uniqueByMember` (a quoted alias
name is no more package-resolved than a quoted class name). Because every consumer
goes through `findClass`, the metaobject, the instance tag, the layout, the ancestor
set and the `%obj-ref` indexes all stay the TARGET's: `(eq (find-class 'alias)
(find-class 'target))` holds, and `make-instance` / `typep` / `subtypep` /
`handler-case` / `class-name` through the alias behave exactly as through the target.

The registration happens at EXPANSION time (`LispMacroExpander.expandSetfFindClass`,
the `FIND-CLASS` case of `expandSetf`), which is the one moment both paths share: the
interpreter expands the form as it evaluates it, and the compile path expands it in
`expandTopLevelDefinitions` -- a dedicated `isSetfFindClassForm` branch beside the
`deftype` one, so the alias is in the registry BEFORE the class tables are built. It
becomes an extra SPELLING of the target's `%class-meta-table%` entry (so the runtime
`%find-class` memoizes one metaobject for both names), an extra name in the runtime
`typep` tag table, and an extra member of the runtime-`subtypep` universe. The form's
own value stays the value CL gives it (the target's metaobject), so a macro whose last
form is the setf still answers a class.

**Divergence + its re-evaluation trigger.** Only the ALIASING shape is accepted: both
names must be literal quoted symbols and the value must be a `find-class` call --
anything else throws naming the supported shape, and an unknown target throws
`there is no class named`. The reason is that the compiled backends fix their class
table at compile time; the same reason makes a NON-top-level alias an error rather
than a silent wrong answer: `classMetaTableForms` calls
`ClosRegistry.markClassMetaTableEmitted`, after which `registerClassAlias` refuses
(the interpreter never emits that table, so it is unaffected). If the class table
ever becomes runtime-extensible, both restrictions can go -- they are consequences
of the static class model, not of the alias design. (todo-246 landed
`ensure-class-using-class`, but only over statically-known names -- the table is
still fixed at compile time, so the restrictions stand.) The `--no-gc` backend shares one never-mutated `EMPTY_CLOS_REGISTRY`
for its CLOS-free `expandSetf` overload, so the place is rejected there outright.

Consumer: cl-dbi's `defclass/a` / `define-condition/a` (`src/utils.lisp`), which give
every class a `<bracket>`-spelled twin (`.todo/238`). Tests:
`LispEvaluatorTest#setfFindClassRegistersAnAliasNameForTheSameClass`/`#setfFindClassAliasIsVisibleToHandlerCase`/`#setfFindClassRejectsNonAliasShapesAndUnknownTargets`,
`JvmLispCompilerTest#compileSetfFindClassRegistersAnAliasNameForTheSameClass`,
`WasmLispCompilerIntegrationTest#compileSetfFindClassRegistersAnAliasNameForTheSameClass`,
ci-spec `find-class-metaobject-substrate`. The macro-namespace twin of this idiom is
`(setf (macro-function ...))`, `.kb/defmacro-backquote.md`.

## A user method on a BUILT-IN name (todo-237, 2026-08-02)

**A built-in whose name a program defines a method on becomes that generic's
DEFAULT METHOD.** In CL these are generic functions whose standard methods stay;
adding one never removes the built-in behavior. Here the dispatcher is an
ordinary `defun` of the generic's name, so without this it SHADOWS the built-in
and every non-instance argument dies with "No applicable method: CLOSE on
INTEGER" — `(ql:quickload "fast-io")` poisoned `close` for the whole image
(its `gray.lisp` methods `close`, `open-stream-p`, `input-stream-p`,
`output-stream-p`, `stream-element-type`, all CL built-ins here), so any later
`with-open-file` anywhere in the program died.

- `generateDispatcher(name, registry, builtinFallback)` is the overload; the
  2-arg one passes null and emits the byte-identical old shape, which is what
  keeps a program with no colliding generic byte-identical. The fallback name
  replaces the
  `noApplicableMethod(...)` last resort in BOTH bodies (`simpleDispatchBody`,
  `combinedDispatchBody` -> `effectiveMethod`), supplies `buildCore`'s primary
  when a branch has only `:before`/`:after` methods, and closes the primary
  chain as `buildNextChain`'s base — so a bare `(call-next-method)` out of the
  least specific user primary reaches the built-in, as in CL. A user DEFAULT
  (unspecialized) method still wins outright: the built-in is the last resort,
  not a method the specificity sort can outrank.
- `fallbackCall` takes no leading `%next-method` thunk (the built-in IS the end
  of the chain) and, for a variadic generic, spells
  `(apply #'<stash> params... %gf-rest)` — that tail is what carries `close`'s
  `&key abort` to the built-in.
- **Interpreter**: `LispEvaluator.defineDispatcher` is the ONE installation
  seam (all four sites in `evalDefclass`/`evalDefgeneric`/`evalDefmethod` route
  through it). `builtinDefaultMethodFor` stashes the built-in under
  `LispMacroExpander.builtinDefaultMethodName` (`%<generic>--builtin`, the
  `%<generic>--m<i>` convention with a reserved index) and MEMOIZES the hit in
  `builtinDefaultMethods`. **The memo is load-bearing**: the dispatcher is
  regenerated on every `defmethod`, and the second pass would find no built-in
  to stash and silently drop the default method. A Java-backed built-in is a
  `LispFunction`; a user/prelude/library `defun` is a `LispLambda` and is
  deliberately left to be shadowed — that type test is also why a MISS needs no
  memo (a dispatcher is itself a `LispLambda`, so re-probing keeps answering
  null).
- **Compile paths** (half 2, `compiler/ShadowedBuiltins`, run by BOTH backends
  right after `expandTopLevelDefinitions`): `(close X)` is compiler-lowered
  (`Jvm/WasmExprCompiler` `case LispNames.CLOSE` -> `Jvm/WasmCloseCompiler`)
  no matter what defuns exist, so the dispatcher defun the splice emits under
  the generic's own name is dead there. The pass follows the Gray-dispatch
  model with a COMPUTED name set — every registered generic whose name is in
  `ShadowedBuiltins.loweredBuiltinFunctions()` — and per name: replaces the
  dead dispatcher (found by structural equality against a regenerated 2-arg
  dispatcher, so a user defun of the same name is never mistaken for it) with
  the SAME dispatcher body the interpreter installs, renamed to
  `%<name>--dispatch` (`shadowedBuiltinDispatcher`); binds the fallback
  `%<name>--builtin` with a FORWARDER defun whose body spells the original
  built-in call, which the compilers still lower (`builtinForwarderDefun`; the
  `&rest` tail is dropped — the lite built-ins ignore keyword tails, e.g. the
  lowered `close` strips `:abort`); and rewrites the program's call sites and
  `#'name` references onto the dispatcher. The walker skips quoted data,
  `defmacro`/`macrolet` bodies, the generated defuns themselves (rewriting the
  forwarder's fallback would recurse — the Gray `DISPATCH_DEFUNS` rule) and the
  non-evaluated positions of `let`/`lambda`/`flet`/`do`/`dolist`/`case`/
  `handler-case` families. When `close` is shadowed the `with-open-file`/
  `with-open-stream`/`with-*-to-string` forms are pre-expanded
  (`unwindProtect=true`) so their implicit `(close f)` routes through the
  dispatcher exactly like the interpreter's global-binding lookup — a side
  effect is that such a WASM module is always in EH mode.
- **The name set**: `BuiltinFunctionWrappers.names()` minus `%`-internals,
  minus `NOT_SHADOWABLE` (signal operators — they double as handler-case
  clause heads — and `make-instance`/`class-of`, which have their own dispatch
  machinery), minus `EXPANSION_LOWERED` (wrapped names the INTERPRETER
  evaluates via `evalCons`/macro expansion, not a global `LispFunction` — its
  half stashes nothing for those, so dispatching them here would diverge the
  other way), plus `LOWERED_WITHOUT_WRAPPER` (lowered names with no wrapper,
  `close` first among them). **Pinned by `ShadowedBuiltinsTest`: every member
  must be a Java-backed `LispFunction` in a fresh global environment** — that
  is the exact interpreter stash criterion, so the two halves keep the same
  boundary. A new built-in added per the CLAUDE.md workflow lands in
  `Environment` + `WRAPPER_DEFS` and is therefore shadowable automatically.
- **Remaining divergences (re-evaluation triggers)**: (1) a plain
  `(defun close (x) ...)` of a built-in name is still ignored by the compile
  paths — same boundary as the interpreter half, which deliberately lets
  defuns shadow each other, but the interpreter DOES honor the defun at call
  sites while the compilers do not; (2) `EXPANSION_LOWERED` names (mapcar,
  sort, format, ...) are un-dispatchable on EVERY path today — if the
  interpreter ever routes them through global function bindings, move them out
  of the exclusion; (3) under `--component` with sockets spliced,
  `WasmSocketsRewrite` runs BEFORE this pass and has already turned
  `close`/`listen`/... call sites into `rontolisp::%io-*` calls — the pass
  COMPOSES via `WasmSocketsRewrite.builtinDispatchAliases`: those alias heads
  rewrite onto the dispatcher too, and the forwarder's fall-through calls the
  `%io-*` defun (socket-table bookkeeping stays in the loop; without this an
  instance reaching `%io-close` was a wasm CAST-FAILURE trap, caught by the
  concatenated ci-spec, whose `sleep` case makes the module async and splices
  the io dispatch). Still open there: the ASYNC read promotions
  (`read-line`/`read-char`/`read-byte` -> `(await (%*-future ...))`) bypass a
  user method on those names; (4) a runtime
  designator (`(funcall 'close x)`, `symbol-function`) does not dispatch on
  the compile paths (no call site to rewrite — the Gray limitation).

Pinned by `LispEvaluatorTest#defmethodOnABuiltinName*`/
`#defmethodOnAVariadicBuiltinNameForwardsTheKeywordTail`/
`#defgenericOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod`,
`JvmLispCompilerTest#compileAndRunDefmethodOnABuiltinName*`/
`#compileAndRunDefgenericOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod`,
`WasmLispCompilerIntegrationTest#defmethodOnABuiltinName*`, ci-spec
`defmethod-on-a-builtin-name-keeps-the-builtin` (concatenated, so it also
proves the whole-program rewrite does not disturb the other cases' call
sites), and `FastIoCircularStreamsE2eTest` (a real `(asdf:load-system
:circular-streams)` pulling fast-io's five built-in-name methods, then a real
file round-trip through `with-open-file` on all four backends).

## Where each path hooks in

- **Interpreter**: `evalDefclass` (expands + evals the defuns, then REGENERATES
  every dispatcher that has a class-specialized method — the new class may
  extend a descendant set), `evalDefstruct` (likewise regenerates every
  dispatcher with a struct-specialized or `structure-object` method,
  `GenericInfo.hasStructMethod` — a later defstruct widens a struct
  specializer's descendant tag set AND the `structure-object` enumeration;
  sxql defines convert-for-sql's `structure-object` method in operator.lisp
  and the clause structs it must catch in clause.lisp, todo-244),
  `evalDefgeneric` (register + eval dispatcher),
  `evalDefmethod` (eval method defun + re-eval the regenerated dispatcher).
  Works anywhere (REPL/load/macro expansion), like defstruct. Known edge: a
  `#'generic` captured BEFORE a later defmethod keeps the stale dispatcher;
  calls by name always dispatch fresh.
- **Compilers**: `expandTopLevelDefstructs` grew into
  `LispMacroExpander.expandTopLevelDefinitions(program, structAccessors,
  closRegistry)` at the same pipeline slot (after `flattenTopLevel`, before
  `LambdaLists.desugarProgram` — the constructors use `&key`). It walks the
  whole program collecting classes/methods, splices defclass/defmethod defuns in
  place, and inserts each generic's dispatcher at its defgeneric's position (or
  the first defmethod's) AFTER the walk, so descendant sets and method sets are
  complete regardless of definition order. Non-top-level
  defclass/defgeneric/defmethod -> `Jvm/WasmExprCompiler` "only supported as a
  top-level form" cases (beside DEFSTRUCT).
- **`make-instance`/`slot-value`** are macro-classified (`CL_MACROS`) and
  expand at the three dispatch sites (`evalCons` + both ExprCompilers) through
  the registry; a literal quoted name gets the static expansion.
  `expandSetf` gained a third parameter (`closRegistry`) with a SLOT_VALUE
  place case; the three setf dispatch sites pass it. Since the DAO milestone
  (2026-08-01) the non-literal forms resolve too: `#'make-instance` taken as a
  VALUE gets a `BuiltinFunctionWrappers` wrapper (REFERENCE_GATED) forwarding
  to the generated `%mop-make-instance` (the interpreter defines the same
  function natively beside it) -- postmodern's make-dao
  `(apply #'make-instance class args)`; a runtime slot NAME dispatches through
  the shared `%slot-value-runtime`/`%slot-boundp-runtime`/
  `%slot-value-set-runtime` defuns (the setf twin is separately gated on a
  runtime-name `(setf (slot-value ...))` -- `needsRuntimeSlotSetDispatch` --
  so read-only programs stay byte-identical; the interpreter serves
  `%slot-value-set-runtime` as a builtin) -- postmodern's dao-from-fields
  column writes.
- **`UserMacroExpander`** (cl-who-critical): top-level
  defclass/defgeneric/defmethod are `macroEval.eval`'d into the macro-time
  evaluator (so a defmacro body can CALL a generic at expansion time — cl-who's
  `process-tag` -> `convert-tag-to-string-list` chain) AND kept in the program
  for the compilers. Walker cases keep defmethod lambda lists (specializers)
  and defclass names/options verbatim; only defmethod bodies and defclass
  `:initform` values are walked.

## The instance-initialization protocol (todo-173)

`initialize-instance`, `reinitialize-instance` and `shared-initialize` are CL
symbols (`PackageRegistry.CL_FUNCTIONS`, like `print-object` and for the same
reason): CL has ONE generic each, so a method defined inside any `(:use :cl)`
package joins the same generic. Before that classification (pre-DAO,
2026-08-01) each package minted its own
(`CL-PPCRE::INITIALIZE-INSTANCE` vs `POSTMODERN::SHARED-INITIALIZE`), and
`make-instance`'s protocol chain — which matches generics by PLAIN name and
takes the first hit — ran one package's chain while another package's hooks
silently never fired (postmodern's `sql-name`-computing
`shared-initialize :after` on its slot metaobjects was the symptom; pinned by
`LispEvaluatorTest#initProtocolGenericsAreSharedAcrossPackages`).

They have no system-supplied primary method in the static subset (the
generated constructor already fills the slots), so `expandDefmethod`
SYNTHESIZES one the first time a program defines any method on one of them —
and the CL chain with it:
`initialize-instance`'s and `reinitialize-instance`'s defaults
`(apply #'shared-initialize instance t/nil initargs)`, `shared-initialize`'s
returns the instance. When the program has no `shared-initialize` method yet, the
generic is CREATED here (with its own default); its dispatcher is emitted by the
caller — the compile path reserves a slot for every registered generic in
`addExpandedDefinition` (dispatchers are generated after the whole walk, so
order does not matter), the interpreter defines any missing dispatcher after each
`defmethod`. `expandMakeInstance` calls `shared-initialize` DIRECTLY (with the
`t` slot-names argument) when only that generic exists, which is the same
effective chain CL's default `initialize-instance` primary would run.

The synthesis condition is the absence of an ALL-DEFAULT primary, not of any
primary: the system method applies to every instance, so a class-specialized
primary (ironclad's sha256 register reset) must not displace it — a sibling
primary's `(call-next-method)` still has to find it.

Three congruence rules landed with it, all general CL semantics the lite model
had been approximating:
- **A `&key` method never rejects a sibling's keyword.** CL computes a generic
  call's valid keyword set as the UNION of the generic's and every applicable
  method's; the lite model instead appends `&allow-other-keys` to every `&key`
  method lambda list. (ironclad calls `(update-digest state seq :digest d)`,
  which reaches an updater declaring only `:start`/`:end`.)
- **A defclass constructor tolerates extra initargs** (`&allow-other-keys` on
  `%make-<name>`): a non-slot initarg belongs to an `initialize-instance` /
  `shared-initialize` method, not the constructor (`(make-instance 'hmac :key k)`).
- **A bare `(call-next-method)` forwards the rest tail**, not just the required
  parameters: `rewriteNextMethod` emits `apply` over the method's `&rest`
  variable, injecting one (`%method-args`) when the method declares a `&key` tail
  without a `&rest`. Without this, ironclad's hmac `reinitialize-instance` lost
  the `:key` it forwards to the system default and PBKDF2 silently produced the
  wrong key.

**Cold-branch tolerance**: on the COMPILE paths only,
`expandMakeInstance(cons, registry, true)` lowers an unknown class to a runtime
`error` call instead of failing the compile — the compilers expand every branch
eagerly, so a dispatcher naming classes from subsystems that were not loaded
(ironclad's `make-kdf` listing scrypt/argon2/bcrypt) would otherwise be
uncompilable. The interpreter passes `false`, so a genuinely wrong class name
still reports when the branch runs. Same tolerance `format` has for a
cold-branch control string.

## Ambiguous slot writes and runtime `typep` (todo-173)

- `(setf (slot-value obj 'NAME) v)` where NAME sits at DIFFERENT indexes in
  unrelated types no longer errors at compile time: `expandAmbiguousSlotSet`
  emits an instance-TAG dispatch that writes the type-correct index (the
  write-side twin of the ambiguous READ dispatch). Routing through the reader
  generic — the old behavior — only worked when the class declared an
  `:accessor`; ironclad's `digest` slot has a `:reader` only, and the name
  collides with the `digest` reader of the `unsupported-digest` condition.
- `(typep x COMPUTED-SPEC)` no longer errors. The INTERPRETER re-expands per
  call through `expandRuntimeTypep`: a `cond` keyed on the specifier symbol, one
  arm per registered layout (matched by canonical name AND plain member name)
  plus one per built-in atomic type name (`RUNTIME_TYPEP_BUILTINS`), each arm
  carrying the very test the literal specifier would have compiled to. An
  unrecognized specifier — a COMPOUND one included — yields nil rather than
  signalling: this is the lite runtime-dispatch model, not a real type table.
- **The COMPILE paths must not inline that dispatch** (todo-115): its size is
  proportional to the registered-class count, and at cl-postgres scale (165
  layouts, ~68 KB of AST per call site) three sites each overflowed the JVM's
  signed-16-bit branch offsets (`StackMapAugmenter: Index -31123 out of
  bounds`). `expandTypep(cons, registry, false)` — what `Jvm/WasmExprCompiler`
  call — emits `(%typep-runtime value spec)` instead, and
  `expandTopLevelDefinitions` injects, once the registry is complete, the
  `%typep-runtime` defun (bounded: the built-in arms plus a table scan) plus the
  `%typep-tag-table%` data table — pure quoted data mapping each type name's
  spellings to the instance tags it accepts, emitted as CHUNKED top-level forms
  (a defvar plus `(setq .. (append 'chunk ..))` continuations, 48 entries each)
  so no single method grows with the registry. The same treatment applies to
  `%subtypep-runtime` (its grouped ancestor sets moved into
  `%subtypep-ancestor-table%` — the inline shape had silently reached 59 KB) and
  to a computed `error` condition-type datum WITH initargs: `(error
  (get-error-type code) :code ...)` lowers to `(%error-runtime datum (list
  args...))`, dispatching over one small per-condition-class construction helper
  (`%ERROR-RT-n`, the literal typed expansion over `getf` reads with the slot
  `:initform` as the getf default) — the old per-site inline expansion reached
  90 KB, past the JVM's 64 KB hard method limit. Non-condition class names fall
  to the object-designator path (CL calls that datum undefined behavior; the
  interpreter's inline dispatch still constructs any class). Pinned by
  `JvmLispCompilerTest#compileAndRunTypepWithComputedSpecifier` /
  `#compileAndRunErrorWithComputedConditionType`,
  `WasmLispCompilerIntegrationTest#typepWithComputedSpecifierAndRuntimeErrorType`
  and the `runtime-type-dispatch-and-symbol-designators` ci-spec case.

## Runtime slot names + class introspection on the compiled backends (todo-146)

jzon's `coerced-fields` walk (`(slot-value obj (c2mop:slot-definition-name s))`
over `(c2mop:class-slots (class-of obj))`) forced compile-path support for a
RUNTIME (non-literal) slot name and for `%class-slot-defs`:

- `expandClassSlotDefs` (both compilers dispatch `%class-slot-defs` through it):
  lowers the call site to the SHARED `%class-slot-defs-runtime` defun, injected by
  `expandTopLevelDefinitions` (gated on a `%class-slot-defs` reference) together
  with its `%class-slot-defs-table%` data table -- one entry per registered
  LAYOUT (classes AND structs, since `ClosRegistry.slotDefs` is the one resolver
  both it and the interpreter use), designators being the instance tag
  (`%class-NAME`/`%struct-NAME`, what `%class-designator` yields), the plain
  name, and a class metaobject (what `class-of` yields; its name slot is read),
  answering the `((slot-name declared-type) ...)` list (a struct's types all
  read `T`). Anything else (builtin type names included) is nil, the
  interpreter's semantics. A struct answering here is what lets a slot-walking
  serializer (json.lisp's `%json-out-instance`) treat a struct like a CLOS
  instance. It used to inline the membership cond PER CALL SITE; that shape
  grows with the registry, and the ci-spec corpus's rtd form hit the JVM
  65535-byte method ceiling (70178 bytes) the day the three MOP base classes
  joined the registry -- if the shared-defun shape ever needs revisiting, the
  table is chunked by cons-node budget (`nodeBudgetedTableForms`), not entry
  count.
- `expandSlotValue` with a non-literal name falls to `expandRuntimeSlotValue`:
  a NAME dispatch over every slot name any layout declares. Names sitting at the
  same index everywhere share one `member` arm (the common case); a name at
  differing indexes gets an inner `%obj-is` TAG dispatch, so an ambiguous runtime
  name resolves instead of erroring. Only an unknown name signals at run time.
- `expandSlotBoundp` with a non-literal name falls to `expandRuntimeSlotBoundp`:
  instance-tag dispatch over the layouts, each arm a `member` of the runtime name
  over that type's declared slot names (the lite always-initialized semantics).

Pinned by `runtime-type-dispatch-residue` (ci-spec) and `JzonE2eTest`'s CLOS
stringification case.

## Name resolution gotcha

`defclass`/specializer class names are package-resolved (canonical, e.g.
`zoo::dog`) but the quoted name in `(make-instance 'dog)` is NOT.
`ClosRegistry.findClass` therefore falls back from the exact normalized key to a
UNIQUE base-name match across packages; two packages defining the same class
name make the bare spelling unresolvable (qualify it). `slot-value` matches by
slot base name for the same reason. `defmethod` stores the specializer as the
FOUND class's canonical name (not the spelling at the method site).

## Real slot unboundness (todo-199)

**A slot written with no `:initform` starts UNBOUND, not nil.** Reading it signals
`unbound-slot`; `slot-boundp` says nil; `slot-makunbound` puts it back. This is CL
semantics and it is what the DAO/serializer idiom ("this column was not fetched")
rests on -- jzon's `coerced-fields` and `json.lisp`'s `%json-out-instance` both SKIP
an unbound slot rather than writing `null`.

- The marker is an instance of a LAYOUT-ONLY internal type, `ClosRegistry.UNBOUND_TAG`
  = `%class-%UNBOUND%` (registered in the `ClosRegistry` constructor, deliberately NOT
  in `classes()`: a class there would join every typep tag table, `class-slot-defs`
  answer and `standard-object` descendant set). Testing for it is therefore the same
  one-compare `%obj-is` every instance-of test is, on all four backends. It prints
  `#<%UNBOUND%>` if it ever escapes -- printing an instance does not hide unbound
  slots.
- `SlotSpec.initformSupplied` records whether the source wrote an `:initform`;
  `initform` then holds `(%obj-new '%class-%UNBOUND%)`.
- Every read goes through ONE out-of-line helper, `(%slot-read value instance 'NAME)`,
  with `(%slot-bound-p value)` for the `slot-boundp` arms
  (`LispMacroExpander.slotUnboundDefuns`, emitted by `expandTopLevelDefinitions` when
  `needsSlotUnboundHelper`; the interpreter defines them on first resolution of either
  name). **They are calls, not inlined `%obj-is` + `if`, for a size reason**: an inline
  instance-of test is ~60 bytes of JVM bytecode, and one per accessor and per
  `slot-value` ran the ci-spec corpus past the 64 KB method limit
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- `expandSlotValue` is the CHECKED read; `expandSlotValueRaw` is the unchecked one the
  `setf` place expansion needs (a write must reach the `%obj-ref` place, and storing
  into an unbound slot is how it becomes bound). A RUNTIME (non-literal) slot name
  keeps the raw read -- the wrapper needs the name at expansion time.
- `unbound-slot` is seeded under `cell-error` (which gained CL's `name` slot, so
  `cell-error-name` works and `unbound-variable`/`undefined-function` inherit it) with
  an extra `instance` slot and a `:report` LAMBDA built in Java
  (`ClosRegistry.unboundSlotReport`, over `%obj-ref` indexes rather than `slot-value`,
  which would drag the ambiguous-name dispatch into every program). `type-error-datum`,
  `type-error-expected-type`, `cell-error-name` and `unbound-slot-instance` are prelude
  defuns (`LispPreludeLibrary`), so one definition serves all four backends.

## Inherited-slot shadowing, `:default-initargs`, `with-accessors` (todo-199)

- **A subclass may re-declare an inherited slot** (CLHS 7.5.3): the STORAGE stays the
  one inherited slot -- so every descendant keeps the index its ancestors baked -- while
  the subclass specification overrides the initform/initarg it writes and ADDS its
  readers/accessors to the inherited ones (`LispMacroExpander.shadowSlot`; "written or
  not" survives parsing as `ParsedSlot.initargSupplied` +
  `SlotSpec.initformSupplied`). postmodern's `savepoint-handle` re-declares
  `transaction-handle`'s `open-p`/`connection` this way.
- **`(:default-initargs :arg form)` overrides the matching slot's effective initform**,
  which is where BOTH construction paths read it: the generated constructor's keyword
  default and the registry initform `buildTypedConstruct` fills unsupplied slots from.
  Before this it only reached the constructor, so `(error 'my-cond)` ignored it.
- **`with-accessors`** is the accessor-call twin of `with-slots`, same substitution
  machinery (`expandWithAccessors`), and needs no registry: an accessor is an ordinary
  generic and an ordinary `setf` place.
- **`with-slots` now resolves `defstruct` slots too**: `expandSlotValueRaw` picks the
  index out of the LAYOUT registry (`uniqueLayoutSlotIndex`, both kinds) instead of the
  class-only `slotPosition` map, falling back to the tag dispatch when types disagree.
- **`with-slots`' entry-time fallback binding is BOUNDNESS-GUARDED** (todo-236): besides
  substituting each slot variable textually, `expandWithSlots` also `let`-binds it to an
  entry-time read, so code GENERATED inside the body (a user macro whose template
  mentions a slot variable -- macros expand after this) still resolves the name. That
  read is `(if (slot-boundp obj 'slot) (slot-value obj 'slot) nil)`, never a bare
  `slot-value` (`LispMacroExpander.boundOrNil`). **Why the guard is not cosmetic**:
  `with-slots` BINDS, it never reads, so a body that only ASSIGNS a slot declared without
  an `:initform` must not signal on entry -- fast-io's
  `(with-slots (buffer) self (setf buffer (make-output-buffer ...)))` inside
  `initialize-instance` is exactly that, and it made every
  `(make-instance 'fast-io:fast-input-stream ...)` die with "The slot BUFFER is unbound".
  A read the body REALLY performs still signals: that one went through the substitution
  and is the slot itself. Cost: one extra dispatch per named slot per entry, on the
  compile paths. `with-accessors`' fallback is NOT guarded -- an accessor is a generic
  function with no boundness twin, and wrapping it would force EH mode on WASM for every
  `with-accessors`; a write-only `with-accessors` over an unbound slot still signals.
  Pinned by `LispEvaluatorTest#withSlotsBindsAWriteOnlyUnboundSlot` +
  `#withSlotsStillSignalsWhenTheBodyReadsAnUnboundSlot`,
  `JvmLispCompilerTest#compileAndRunWithSlotsWriteOnlyUnboundSlot`,
  `WasmLispCompilerIntegrationTest#multiParameterDispatchVariadicGenericsAndDefaultInitargs`
  and the ci-spec case `with-slots-write-only-unbound-slot-and-missing-slot`.
- **A `slot-value` naming a slot NO registered class declares is a RUN-time error**
  (todo-236, `LispMacroExpander.missingSlotStub`): the subforms evaluate for effect, then
  `(error "The slot X is missing")` -- read side and `setf` place alike. It used to throw
  out of the expander, which on the eagerly expanding compile paths failed the whole
  BUILD over a read that may never execute; the interpreter never noticed because it
  expands a method body only when the method is called. fast-io's `open-stream-p` reads
  `'openep`, a typo for its own `openp` slot, in a method nothing in the library calls.
  Signalling also makes it a condition `handler-case` can see, on every backend, which is
  what CL's `slot-missing` protocol does. `slot-boundp` on an undeclared slot already
  answered nil and still does.

## `change-class` -- in place, on all four backends (todo-199)

`(change-class instance 'name initarg value ...)` mutates the instance IN PLACE: the
object identity and every slot the two layouts share survive, the slots the new class
adds are filled from their initforms, the supplied initargs are stored, and the
instance is the value. postmodern's connection pool needs the identity to survive
(`connect` changes a local and returns it; a copy would strand every other reference).

- `expandChangeClass` emits: capture the OLD tag -> `(%obj-become obj '%class-T)` ->
  a `cond` on the captured tag filling `[slotCount(source), slotCount(target))` from
  the target's initforms -> the initarg stores -> the instance. The tag is captured
  BEFORE the swap because the fill has to know which class the instance WAS but can
  only store into the WIDER layout, i.e. after it.
- **`LispLayout.capacity()` is why this works on the JVM**, where an instance IS its
  `Object[]` and cannot grow without losing identity: every ancestor of a
  `change-class` target reserves the target's slot count at construction.
  `expandTopLevelDefinitions` scans the whole program for change-class targets
  (`registerChangeClassTargets`, the form lives in a body, not at top level) and calls
  `applyChangeClassCapacities()` once the registry is complete. Only classes a program
  actually names widen anything. `%obj-slots` and the printers bound themselves by the
  LAYOUT's slot count, never by the array length.
- **The WASM instance struct's field 0 became MUTABLE** for this, which makes it
  structurally identical to `TYPE_P1_FUTURE` `{mut i32, mut eq}` -- so its rec group
  carries a second, never-instantiated empty struct: wasm canonicalizes a rec GROUP as
  a whole, and a 2-member group can never equal a 1-member one, so `ref.test` keeps
  telling an instance from a future (`INSTANCE_TYPE_COUNT = 2`). Do not "simplify" it.
- The interpreter needs no reservation at all (`LispInstance.becomeLayout` grows the
  array; the LispInstance, not the array, is the identity) -- the reservation is
  harmless there and keeps ONE expansion for all four backends.

## `print-object` -- the printer consults it (todo-199)

A `defmethod print-object` makes the printing operators render that type through the
generic. **Gated on the program defining a method**: with none -- and with no condition
in reach either, see the second gate below -- every printing operator compiles exactly as
before and every existing artifact stays byte-identical.

- `printObjectTags(registry)` is ONE of the two gates and the routed tag set (class
  specializers and `defstruct` ones -- a struct name parses as a TYPE specializer
  carrying the struct name, so both descendant-tag families are collected).
- `expandPrintObjectHook` rewrites `princ-to-string`/`prin1-to-string` to
  `(%print-object-str x escape)` and `print`/`princ`/`prin1` to a
  `write-string` of it (+ `terpri` for `print`). `format`'s `~A`/`~S` need no case of
  their own: they lower to those two conversions.
- The generated `%print-object-str` falls back to `%princ-to-string` /
  `%prin1-to-string` -- INTERNAL ALIASES of the same two functions. Without them the
  fallback would re-enter the very rewrite that produced it. The interpreter INLINES
  the renderer instead of calling the defun, because it re-expands per call and must
  see a `defmethod` that follows the first print.
- **`*print-escape*` is BOUND around the method call** (`printObjectCall` wraps it in a
  `let`), `t` for prin1/print/`~S` and `nil` for princ/`~A` -- the escape flag the hook
  already threads through `%print-object-str` is exactly the value CL binds there, so a
  portable method that branches on `(and (null *print-readably*) (null *print-escape*))`
  (quri's `uri` method: bare URI under princ, `#<TYPE uri>` under prin1) behaves as it
  does in CL. `*print-escape*`/`*print-readably*` are `CL_VARIABLES` holding CL's
  defaults; the interpreter seeds both into `specialVars` (nothing in user code declares
  them, yet the route binds one), and the compile path injects `(defvar ...)` from
  `LispMacroExpander.injectMvSpillGlobal`, which runs AFTER `expandTopLevelDefinitions`
  and therefore sees the route's own reference. That ORDER is the load-bearing part: a
  `setq` would not proclaim the name special, and injecting before the expansion would
  miss the reference the expansion creates.
- **The SECOND gate is a condition's `:report`** (`.kb/error-handling.md`, todo-206):
  the same rewrite fires for a program that can build a condition, and the
  escape-off arm of `%print-object-str` renders one through
  `%condition-report-str`. A `print-object` method on a condition class still wins,
  because the method route is tested first. The two share this seam rather than
  sitting beside each other -- there is exactly one place that decides what text a
  printing operator writes.
- **LITE: the method is consulted for the value the operator is HANDED, not for one
  nested inside a printed list/vector** -- `(print (list obj))` still shows the built-in
  syntax. Making it recursive means hooking each backend's list renderer (hand-emitted
  bytecode / wasm), not the shared expander; revisit only if a library needs it.
- `print-unreadable-object`'s `:type t` prints the type NAME with the
  `%struct-`/`%class-` tag prefix stripped INLINE (`typeNameOf`), not by calling the
  prelude's `type-of`: this expansion runs inside the compilers, long after the prelude
  pre-pass that would have spliced that defun -- a direct compiler invocation (every
  backend unit test) has no prelude pass at all. The separating space is written only
  when a body follows. `:identity` is accepted and prints NO address: there
  is no object-identity token in the value model, and a per-backend one would break the
  byte-identical cross-backend output the suite rests on.

## Out of scope / known gaps

- Qualifiers (`:before`/`:after`/`:around`) + `call-next-method`/`next-method-p`:
  DONE (Stage 3, 2026-07-06). Combination is for class + default methods;
  eql/type-specialized qualified methods combine only with same-specializer
  primaries + the default method (cross-type subtyping among specializers is not
  computed).
- MOP boundary (redrawn 2026-08-01, the DAO/MOP milestone): the STATIC metaobject
  subset is IN -- `find-class` AND `class-of` answer a real memoized
  `standard-class` instance on all four backends, `eq` to each other for the
  same class. Interpreter: Java built-ins over `ClosRegistry.classMetaobject`
  (designator-aware: plain names AND instance tags; struct layouts answer too --
  a struct class is a `standard-class` instance, `structure-class` does not
  exist) and `ClosRegistry.builtinClassMetaobject`
  (`ClosRegistry.BUILTIN_CLASS_NAMES` = exactly the `%class-designator` result
  set, `T` for everything else e.g. arrays); lazy `ensureMopClassesSeeded()` --
  NEVER seed unconditionally: an unconditional seed joins every runtime dispatch
  table and once pushed the ci-spec corpus over the JVM 64 KB method ceiling.
  Compile paths: `LispMacroExpander.expandTopLevelDefinitions` injects, gated on
  the program referencing `find-class` OR `class-of`, a `%class-meta-table%`
  data table (node-budget-chunked, one entry per registered class AND struct
  layout: spellings -- the instance TAG among them, so a `class-of` designator
  resolves by the same scan -- / superclass / effective-slot data) plus the
  generated `%find-class` (internal resolver: table scan, then the built-in
  class fallback, CL errorp semantics) + `%find-class-materialize` pair; the
  public `find-class` defun is a thin wrapper injected only when the program
  references it without defining it, so a user `find-class` never changes what
  `class-of` answers. `(class-of x)` expands to `(%find-class
  <%class-designator dispatch> t)`. The OLD tag/type-name view lives on as the
  internal `%class-designator`, which is what the light consumers ride (prelude
  `type-of`, `print-unreadable-object :type`, the no-applicable-method message,
  json.lisp's `%json-out-instance`) -- they drag no metaobject runtime in and
  keep every non-MOP program byte-identical. `%class-slot-defs` accepts a class
  metaobject as designator too (reads its name slot; the generated
  `%class-slot-defs-runtime` gains that preamble only when `standard-class` is
  registered). `typep`/`subtypep` take a class METAOBJECT wherever a type
  specifier is expected (todo-230, 2026-08-03): it designates its own class, so
  `(subtypep (find-class 'sub) (find-class 'super))` answers exactly like the
  name spelling and `(typep x (find-class 'c))` tests the value against it --
  mito's `contains-class-or-subclasses` (src/core/util.lisp) rides both. ONE
  normalization rule -- "an instance tagged as a `standard-class` descendant
  continues as its slot-0 name" -- with three emission sites: `subtypep` folds
  it in Java for the interpreter (`LispMacroExpander.classMetaobjectDesignator`,
  applied AHEAD of the `t`/`nil` constant edges), and the emitted
  `%typep-runtime` (the specifier) / `%subtypep-runtime` (BOTH arguments) carry
  `LispMacroExpander.metaobjectNameNormalization`, the very
  `(if (%obj-is v '<standard-class descendants>) (setq v (%obj-ref v 0)))`
  preamble `%class-slot-defs-runtime` uses. A metaobject is always a RUNTIME
  value, so the specifier is never literal and the runtime dispatch is always
  the path taken; the literal fold is untouched.
  `class` is a SEEDED slot-less class (`ClosRegistry.CLASS_NAME`), the
  superclass of `standard-class` and hence of every user metaclass, so
  `(typep x 'class)` is the metaobject predicate through the ordinary ancestor
  machinery rather than a fourth special case. It is never instantiated and
  contributes no slots, so the `%obj-ref` index contract below is unchanged.
  Two traps this cost a round to learn, both from the LAZY seeding:
  (a) the preamble's tag list is `descendantTags(STANDARD-CLASS)`, and an EMPTY
  one means "no metaobject can exist" on the compile paths (final registry) but
  NOT in the interpreter, where the `(find-class 'c)` in the test's own argument
  seeds AFTER the expansion -- hence the `liveRegistry` flag, which falls back
  to the constant `%class-STANDARD-CLASS` tag (the only tag possible while no
  user metaclass is defined) instead of dropping the preamble;
  (b) a type SPECIFIER naming a MOP base class is itself a seeding trigger
  (`ClosRegistry.ensureMopClassesSeededFor`, called from the interpreter's
  `typep`/`subtypep`) -- otherwise `(typep (find-class 'c) 'class)` as a
  program's first MOP form expands to a constant nil before anything seeds.
  The built-in `T` class's name slot holds the boolean `t`, not the symbol, so
  the runtime `typep` universal-type arms match BOTH spellings
  (`universalTypeMatchTest`, plus the instance branch's `(member tn '(t atom))`);
  `subtypep`'s existing `(eq b t)` edge already did.
  `#'class-of`'s wrapper is REFERENCE_GATED in
  `BuiltinFunctionWrappers` -- ungated it referenced `%find-class` in programs
  the injection scan said needed no runtime. `class-name` is core (a prelude
  defun over metaobject slot 0). The metaobject slot order (name,
  direct-superclasses, direct-slots, effective-slots, finalized-p;
  slot-definitions: name, initargs, initform, type, readers) is a `%obj-ref`
  index contract shared with the closer-mop shim -- append, never reorder.
  `allocate-instance` (2026-08-01) is IN: an instance of a registered CLOS
  class (metaobject or name designator) with EVERY slot the unbound marker --
  no initforms, no `initialize-instance` -- the `dao-from-fields` idiom;
  initargs accepted and ignored. Interpreter: a registry-backed built-in
  beside `find-class` (`LispEvaluator`). Compile paths: `%obj-new` needs a
  LITERAL tag, so `LispMacroExpander.allocateInstanceDefuns` (gated on an
  `allocate-instance` reference, `needsAllocateInstanceRuntime`; a user defun
  wins) emits one construction arm per registered class, chunked into
  `%ALLOC-INST-<n>` helper defuns by cons-node budget so no method grows with
  the registry (the `%error-runtime` lesson), plus the public
  `allocate-instance` defun over an `or`-chain. It does NOT seed the MOP
  classes: a metaobject argument only comes from find-class/class-of, whose
  own references seed already. Built-in and struct classes signal (a struct's
  construction contract is its positional constructor). The
  `closer-common-lisp` package that table.lisp `:use`s is a resolver-level
  flat re-export -- mechanics in `.kb/packages.md`.
  The metaclass protocol (Phase B, 2026-08-01) is IN: a `defclass` carrying
  `(:metaclass M)` -- `M` must be registered and descend from `standard-class`,
  i.e. defined by an earlier `defclass (standard-class)` -- keeps its full
  static expansion (constructor, accessors, registry entry; instances of the
  class stay ordinary) and additionally emits ONE
  `(%ensure-class-with-metaclass 'name 'M '(supers) (list slot-specs...)
  (list class-initargs...))` driver call as its last generated form (the spec
  spines are `list`-built and carry `:initfunction` thunks since todo-246 --
  see the widening section above; the driver itself now routes through
  `ensure-class-using-class`). Unknown CLASS
  options become metaclass initargs whose value is the option TAIL list
  (`(:table-name "u")` -> `:table-name ("u")`, AMOP canonicalization); unknown
  SLOT options are collected per slot (single occurrence each) and ride the
  canonical `(:name .. :initargs .. :initform .. :type .. :readers ..)` spec
  plist as `direct-slot-definition-class` initargs. The driver + the system
  default methods for `closer-mop:{validate-superclass (permissive t),
  direct-slot-definition-class, effective-slot-definition-class,
  compute-effective-slot-definition, finalize-inheritance}` are Lisp source:
  `macro/mop-protocol.lisp` via `MopProtocol.forms()` (the `FormatRenderer`
  pattern), SELF-CONTAINED over the `%obj-ref` index contract -- no closer-mop
  shim dependency, defMETHODs only (no defgenerics) so user hook methods
  defined before OR after merge into the same generics. The dynamic-extent
  contract postmodern relies on holds: the default
  `compute-effective-slot-definition` calls `effective-slot-definition-class`
  and instantiates its answer INSIDE the user override's `call-next-method`
  (so a `*direct-column-slot*` binding is visible to the effective slot
  class's `:initform`). `finalize-inheritance` runs EAGERLY at definition time
  (documented divergence; user `:after` methods = postmodern's
  `build-dao-methods` hook, Phase C). The protocol runs "in the evaluator at
  hand": the interpreter's `evalDefclass` loads the protocol once
  (`ensureMopProtocolLoaded`; a defclass merely EXTENDING a seeded MOP base
  class just seeds), which also covers the compile paths' macro-time
  evaluator; the compiled program runs the same driver call at program start
  in top-level order. Compile-path gate (`usesMetaclassProtocol` /
  `namesMopBaseSuperclass` in `expandTopLevelDefinitions`): PREPENDS
  `MopProtocol.forms()` to the program (before the reference scans, so the
  driver's own `find-class` use switches the metaobject runtime on) and
  appends `seededMopConstructorDefuns` (keyword constructors for the three
  seeded MOP base classes, whose defclass never ran), the generated
  `%mop-make-instance` (runtime-class make-instance: designator -> name ->
  per-class `apply` of the generated constructor + the program's
  initialization generic; a METAOBJECT-ancestored class's arm instead
  allocates UNBOUND for the chain fill since todo-246 -- see the widening
  section; arms bounded to METAOBJECT-ancestored classes,
  WIDENED to every program-registered class -- seeded condition classes
  excluded, they have no keyword constructor -- and chunked into `%MMI-<n>`
  helpers with the init call hoisted, whenever the program takes
  `#'make-instance` as a value) and
  `%register-class-metaobject` (prepends onto `%class-metaobjects%`, so the
  driver-built metaclass instance shadows the materialized plain view -- the
  memo scan takes the first hit; the interpreter twin primes
  `ClosRegistry.classMetaobjects` via `registerClassMetaobject`). Lite
  divergences (documented on the defclass page): for REGULAR (non-metaobject)
  classes -- metaobject classes moved to the chain fill in todo-246, where
  CL's order holds natively -- shared-initialize hooks run
  AFTER constructor slot-filling -- and that IS observable, the earlier "the
  default primary is identity, so the DAO protocol cannot tell" claim was
  wrong: upstream dao-class's `shared-initialize :before` RESETS its
  `direct-keys` slot and counts on CL's order (:before -> initarg fill) to
  restore it from the `:keys` class option. The repair is the initarg RE-FILL
  (2026-08-01): for a REGULAR class specialized by a `:before` method on
  initialize-instance/shared-initialize (`ClosRegistry.initRefillTargets`,
  ancestor-inclusive via `needsInitRefill`), make-instance re-sets every
  DECLARED-initarg slot the call supplies (`SlotSpec.initargSupplied`; the
  slot-name-default keyword a `:initarg`-less slot gets is deliberately NOT
  refilled -- dao-class's `table-name` slot must keep the :before's write)
  after the initialization generic returns, leftmost initarg wins. Three
  emission sites, one semantic: `expandMakeInstance` folds it statically per
  literal call site (`%obj-set` with the baked slot index; the interpreter's
  `%mop-make-instance`/`#'make-instance` builtin re-enters this expansion with
  quoted args, so `literalKeyword` unwraps both spellings), and the generated
  `%mop-make-instance` runtime carries `%MMI-REFILL` (one cond bounded by the
  :before-specialized class set) + `%MMI-INIT-TAIL` (plist scan) -- called
  inside the per-class arm right after the initialization generic, and after
  the hoisted init call in the chunked `#'make-instance`-as-value mode.
  Residual (accepted, no known library hits it): a specialized PRIMARY without
  call-next-method should suppress the fill entirely, and an `:after` writing a
  supplied declared-initarg slot on a refill-target class would be re-clobbered
  -- both need the fill to happen INSIDE the generic chain, which the static
  constructor model cannot do. Pinned by
  `defclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling` (all three
  suites) and the PostmodernE2eTest DAO leg. Other lite divergences: inherited
  effective slots are reused from the
  superclass metaobject unless shadowed (the direct-definition list handed to
  compute-effective-slot-definition is the shadowing definition alone), and
  validate-superclass's default is permissive.
  Definition-time method construction (Phase C, 2026-08-01) is IN: the
  `(funcall (compile nil `(lambda () ,code)))` idiom of postmodern's
  `build-dao-methods` (`%eval`), where `code` is a `let*`/`labels` form whose
  nested `defmethod`s carry the class METAOBJECT spliced as a literal
  specializer (plus `(eql (class-name ,class))`) and whose bodies close over
  the bindings. The evaluator's `compile` built-in (`LispEvaluator`; CL
  semantics otherwise: coerce a literal lambda to a function in the null
  lexical env, a non-nil name installs and returns the name) intercepts a
  NO-ARGUMENT definition containing a defmethod and folds the metaobject
  literals first (`macro/MopEvalCapture`): specializer position -> the class
  name, `(eql (class-name <inst>))` -> `(eql 'name)`, every other occurrence
  -> `(find-class 'name)` -- valid during finalization because the driver now
  registers the metaobject BEFORE `finalize-inheritance` (mop-protocol.lisp,
  like CL's ensure-class; that ordering is LOAD-BEARING for the fold). Then:
  the live interpreter returns a function evaluating the folded body in place
  (nested defmethods + closures are native); the compile paths' MACRO-TIME
  evaluator records the body into the splice sink `UserMacroExpander`
  attaches (`setMopEvalSpliceSink`), and the pass -- now also activated by a
  bare `:metaclass` defclass -- splices the folded forms right after the
  triggering defclass, where `expandLetNestedDefmethods` (widened from
  cl-ppcre's direct-body idiom to defmethods at ANY depth, quote-skipped,
  byte-identical for the shallow case) registers them statically and the
  nested method-body defuns compile to global-closure setqs. The run-time
  re-execution of the same call in a compiled program goes through the
  generated `compile` runtime (`macro/CompileRuntime` +
  `compile-runtime.lisp`, injected gated on a compile reference without a
  user defun, registered in the native-image resource-config): a
  defmethod-containing definition answers a do-nothing function (the splice
  already did the work), anything else signals -- so a method-defining form
  built from RUNTIME data is silently absorbed rather than signalled, the one
  soft edge of the divergence. A method under a false definition-time guard
  (`when key-fields`) still registers in the dispatcher; calling it fails on
  the unassigned body global instead of no-applicable-method.
  Pinning tests: `LispEvaluatorTest`/`JvmLispCompilerTest`/
  `WasmLispCompilerIntegrationTest` `*FindClass*` + `*CloserMopShim*` +
  `classOf*`/`compileAndRunClassOf`/`classOfAndSlotAccessors` +
  `allocateInstance*`/`compileAllocateInstance*` +
  `typepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers` (all three suites,
  `compile`-prefixed on the JVM) +
  `defclassMetaclass*`/`compileDefclassMetaclass*`/
  `defclassMetaclassRunsTheClassDefinitionProtocol` (WASM),
  `compileCoercesALambdaExpressionToAFunction` +
  `compileInterceptsDefinitionTimeMethodConstruction` (all three suites),
  ci-spec `find-class-metaobject-substrate` (raw metaobject print shape
  included), `defclass-metaclass-protocol` (the dao-class shape end to end)
  and `compile-definition-time-method-construction`. Still
  OUT (the divergence's remaining "why": classes are compile-time-static,
  `--optimize` DCE and the dispatch tables depend on it): runtime class
  construction (`ensure-class` from computed data, a non-top-level
  `defclass`), `add-method`, `compute-applicable-methods`,
  `update-instance-for-*`. Class REdefinition of a statically-known name is IN
  since todo-246 (the widening section above); redefinition from computed data
  remains out with the rest.
  Known static-model seam: on the compile paths `find-class`/`class-of` see the
  WHOLE program's classes regardless of form order, while the interpreter only
  knows classes already defined at call time.
  Registry-growth lesson (relearned 2026-08-01, Phase B): the RUNTIME-slot-name
  `slot-value`/`slot-boundp` dispatch used to be inlined per call site and
  grows with every layout times its slots -- the metaclass protocol's five
  extra ci-spec classes pushed a corpus dolist body past the JVM's SIGNED
  16-BIT branch encoding (32 KB, hit before the 64 KB method cap). It is now
  outlined into the shared `%slot-value-runtime`/`%slot-boundp-runtime`/
  `%slot-value-set-runtime` defuns (`runtimeSlotValueDefuns` etc., gated on a
  non-literal-name site, `needsRuntimeSlotNameDispatch` /
  `needsRuntimeSlotSetDispatch`; the interpreter resolves runtime names
  natively and never calls the read pair, and serves the set twin as a
  builtin). The defuns themselves are CHAINED-CHUNKED by cons-node budget
  (`chainedDispatchDefuns`: overflow arms call `%SVR-<n>`/`%SBR-<n>`/
  `%SVW-<n>` helpers; a dispatch that fits stays one defun, byte-identical to
  the pre-chunking shape) -- the full postmodern MOP build's registry pushed
  the single-defun shape past the JVM's signed 16-bit branch encoding.
  Top-level compile crashes now name the
  offending form (`JvmLispCompiler` chunk-loop wrapper).
  `change-class` is the ONE runtime exception and is not MOP: both classes are literal, so
  the whole change is a static expansion (see above).
- `:allocation`/`:writer` slot options, eql specializers on strings.
  (Multiple inheritance is IN since 2026-08-02 -- see the section above.)
  The `:type` slot option is RECORDED since 2026-07-18 (`SlotSpec.type`, plain
  name, `"t"` when omitted; still a checking no-op) so introspection can
  report it.
- Compiled runtime `eval`: generated functions are callable; defining
  classes/methods or using `make-instance`/`slot-value` inside `eval` is not
  (doc/en/guides/eval-limitations.md).
- `--no-gc` rejects via its generic top-level error, like defstruct.
- `defclass`/`defgeneric`/`defmethod` are in `PackageRegistry.CL_SPECIAL_FORMS`,
  `make-instance`/`slot-value`/`with-slots`/`with-accessors`/`change-class` in
  `CL_MACROS` — pinned in ci-spec (`rontolisp-package-introspection`), the three
  backend tests, and the doc pages; update all together if those sets change again.

Pinning tests: `LispEvaluatorTest#defgeneric*`/`defclass*`/`defmethod*`/
`closInUserPackage`, `JvmLispCompilerTest#compileAndRunDefgeneric*`/
`compileAndRunDefclass*`/`compileAndRunMacroCallingGenericAtExpansionTime`/
`compileNestedDefmethodFails`, `WasmLispCompilerIntegrationTest#compileAndRunDefgeneric*`/
`compileAndRunDefclass*`, `UserMacroExpanderTest#defmethodLambdaListStaysVerbatim*`/
`defclassKeepsNamesAndOptions*`/`macroBodyMayCallAGenericFunctionAtExpansionTime`,
ci-spec cases `clos-defgeneric-defmethod-eql-dispatch`,
`clos-defclass-slots-inheritance-and-dispatch`, and
`clos-method-qualifiers-and-call-next-method` (all four backends), and the five
`doc/*/reference/**` pages via `DocExamplesTest`. Stage 3 pinning:
`LispEvaluatorTest#{defmethodBeforeAndAfterQualifiersRunAroundThePrimary,
callNextMethodChainsPrimariesAndNextMethodP,aroundMethodWrapsAndCallNextMethodInvokesTheCore,
callNextMethodWithNewArguments,callNextMethodWithNoNextMethodSignals}` and the
`compileAndRun{MethodQualifiersAndCallNextMethod,AroundMethodAndNextMethodP}`
tests in the JVM/WASM suites.
