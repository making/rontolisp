# CLOS static subset — defclass / defgeneric / defmethod / make-instance / slot-value

User docs: `doc/en/reference/special-forms/{defclass,defgeneric,defmethod}.md`,
`doc/en/reference/macros/{make-instance,slot-value}.md`, missing-features guide.
Dispatch, standard method combination and multiple inheritance are DONE.

Everything expands to plain defuns via `LispMacroExpander` (no backend codegen).
`ClosRegistry` (in `am.ik.rontolisp`) holds classes, generics and `slotPositions` (slot
base name -> 1-based position, `-1` when unrelated classes disagree -- `slot-value` then
errors "use the accessor"). One per evaluator (`LispEvaluator.closRegistry`) and per
compilation (`Jvm/WasmLispCompiler.Ctx`, threaded via `Ctx.Builder` beside
`structAccessors`).

## `expandDefclass(cons, closRegistry, structAccessors)`

- Registers a `ClosRegistry.ClassInfo`; generates the keyword constructor `%make-<name>`
  (`&key ((:initarg slot) initform)...` over the FULL slot list) plus one synthesized
  `(defmethod R ((__obj C)) (nth pos __obj))` per `:reader`/`:accessor` -- a METHOD, not a
  defun, since several classes may declare one reader name over DIFFERENT slot positions
  and a user `defmethod` must merge, not shadow.
- An `:accessor`'s write half is a `%setf-<name>` writer GENERIC
  (`(defmethod %setf-A (__new (__obj C)) ...)`, new value first) registered in
  `structAccessors` under `SETF_FUNCTION_MARKER`; `(setf (A x) v)` funcalls that dispatcher.
- Interpreter `evalDefclass` evals the returned forms; the compile path's
  `expandTopLevelDefinitions` sends them through `addExpandedDefinition`.
- A position-ambiguous `slot-value`/`with-slots` name falls back to the reader generic
  (`expandSlotValue`); its setf re-dispatches through the writer.
- `initialize-instance` in the `:after` shape: the FIRST user method also synthesizes an
  identity default primary; `expandMakeInstance`, when a generic plainly named
  `initialize-instance` is registered, hoists initargs into let* temps, calls the
  constructor, then the generic, returning the instance.
- An instance is `(%obj-new '%class-<name> v...)` (`.kb/instance-syntax.md`), NOT a list.
  Layout = superclass slots (inheritance order) + own slots, so single inheritance keeps
  indexes stable in descendants; accessor bodies are `(%obj-ref __obj i)` /
  `(%obj-set __obj i __new)`.

## `registerDefgeneric` / `expandDefmethod`

- Records `ClosRegistry.GenericInfo`, methods keyed by `qualifier + specializer`
  (same-qualifier-same-specializer redefinition replaces; a `:before dog` and a primary
  `dog` coexist). Each defmethod becomes a defun `%<generic>--m<i>` (body verbatim; a
  leading docstring is evaluated and discarded, `declare` expands to nil).
- Optional `:before`/`:after`/`:around` qualifier precedes the lambda list
  (`MethodInfo.qualifier`, `""` = primary). EVERY method-body defun gains a leading
  `%next-method` thunk parameter; `rewriteNextMethod` turns `(call-next-method args...)`
  into `(if %next-method (funcall %next-method args-or-current-params) (error))` and
  `(next-method-p)` into `(not (null %next-method))`. Both are matched by package-stripped
  name (NOT in `CL_SYMBOLS`) and rewritten away before any backend sees them.
  `MethodInfo.usesNext` records whether a body mentions them. `(apply #'call-next-method
  ...)` / `(funcall #'call-next-method ...)` are rewritten too, not only head position.
- `defgeneric` inline `(:method [qualifier] (params) body...)` clauses register like
  separate defmethods; `expandTopLevelDefinitions` splices them on the compile path.

## `generateDispatcher(name, registry[, builtinFallback])`

ONE dispatcher defun per generic: a nested-if chain, most specific first. It is an ordinary
defun, so `#'name`/`funcall`/mapcar work with no `BuiltinFunctionWrappers` entry.

- An OPTIMIZING compile passes a `DispatchNarrower` (`compiler/GenericDispatchNarrowing`)
  omitting branches no call site can select, so the shakers drop those method defuns
  (`.kb/optimize-dead-code-elimination.md`). Every other caller passes null and the
  dispatcher is byte-identical.
- Specializers may sit on ANY required parameter. Ordering compares parameters
  leftmost-first with `specializerRank`: eql 0, classes 10..99 by descending ancestor-set
  size (subclass first), built-in types 200s with subtypes (`null`/`package`/`keyword`/
  `integer`) before `symbol`/`number`/`list`, default 1000; stable sort keeps definition
  order within a rank. `package` ranks 205, ahead of `keyword` (210) and `symbol` (220),
  because a package IS a keyword in this value model (`.kb/symbol-runtime-api.md`);
  misordered, rove's `find-suite` recurses forever. A keyword naming no package falls
  through to a `keyword`/`symbol` method.
- **The TYPE specializers `defmethod` accepts (`isSupportedTypeSpecializer`) and the types
  `makeTypeTest` builds are ONE definition, not two lists that drift.**
  `makeEqlSpecializerTest`: symbols/keywords compare with `equal` (content-safe on WASM),
  numbers/characters with `eql`. A class test is `(%obj-is x '%class-C ...)` over the
  statically-known descendant tags.
- A generic whose lambda list continues past the required params gets a VARIADIC dispatcher
  forwarding the tail via `apply` (and `call-next-method` forwards it too). That shape -- a
  literal `#'m` target whose required parameters are all covered by the leading arguments --
  is the compile backends' ALIGNED fast path (`Jvm/WasmApplyCompiler`): required parameters
  pass directly, the rest parameter takes the tail verbatim (or the excess consed on), no
  build-then-unpack round trip (~80-300 B saved each). Fewer leading arguments than required
  parameters, or a non-variadic target, keep the build-then-unpack path.
- The last resort is ONE call of the shared `%no-applicable-method` defun
  (`noApplicableMethodDefun`), the per-generic message riding as a literal prefix argument;
  re-inlining the error tail cost over a KB EACH across a library's synthesized accessors.
  `expandTopLevelDefinitions` appends it once (a `referencesFunction` scan just before the
  format-renderer scan, which must see its error form); the interpreter defines it before
  its first dispatcher (`LispEvaluator.defineDispatcher`). The dispatcher AST is therefore
  ONE shape everywhere, which `ShadowedBuiltins`' structural dead-dispatcher comparison
  relies on.
- An `(eql form)` specializer's form is EVALUATED at method definition (CLHS 7.6.2); the
  only such form a static walk can evaluate is a constant name. `ClosRegistry` carries a
  `defconstant` name -> literal value table (`registerConstant`/`findConstant`) filled in
  DEFINITION ORDER by `expandTopLevelDefinitions` (from the literal value form, after
  `PureBuiltinFolder`) and by the interpreter's `evalDefconstant` (from the EVALUATED
  value). `parseEqlSpecializerValue` consults it for a BARE symbol only; `(eql 'x)` is the
  symbol `x`, and a bare symbol naming no constant stands for itself.

### The two dispatch bodies

- `simpleDispatchBody` (one call per branch) when the generic has NO qualifier and NO
  `call-next-method` usage; otherwise `combinedDispatchBody` = one branch per distinct
  specializer (`specKeyOf`, qualifier-independent) plus the default fallback, each branch's
  value `effectiveMethod(branchRep, ...)`.
- `applicableMethods` filters by `appliesToBranch`: default methods always apply; a class
  method applies to a class branch whose class has it as an ancestor; a STRUCT (type) method
  applies to a branch struct that `:include`-descends from it, via the spelling-tolerant
  `descendantStructTags`; eql and built-in type methods apply only to their exact-same
  branch (cross-type subtyping among THEM is out of scope).
- **Plus one branch per position-wise MEET of two INCOMPARABLE specializer vectors**
  (`addMeetBranches`/`specializerMeet`). One branch per method suffices only while the
  vectors form a chain, because `appliesToBranch` is a SUBSET test: a
  `(dbd-postgres-connection, DEFAULT)` branch does not admit a `(dbi-connection, string)`
  method, so without the meet branch a call satisfying BOTH took the coarser branch and the
  other method vanished. The scan is quadratic but runs to fixpoint only over compatible
  AND incomparable pairs, so a generic with none keeps its dispatcher byte-identical.
  **Trigger**: the JVM 64 KB method ceiling (`.kb/jvm-method-size-limits.md`) if a library
  ever has many mutually incomparable methods on one generic.
- Composition: `:around` (most specific first) wrap a `coreThunk`; the core runs `:before`
  (msf, for effect), the primary chain (msf, value kept via a `%clos-result` let), then
  `:after` (LEAST specific first). Chains are built by `buildNextChain` as nested
  `(lambda (params) (%m next params))` literals passed as each method's `%next-method` -- NO
  free-variable capture. Base next = `nil` for the innermost primary, the `coreThunk` for
  the innermost around.

## Multiple inheritance

`ClassInfo` carries `superclasses` (local precedence order), `cpl` (CLHS 4.3.5 topological
sort in `LispMacroExpander.computeCpl`; inconsistent local orders are an
`IllegalArgumentException`) and `directSlots`, beside the effective `slots` and the ancestor
SET. The ~20 consumers that only ask "is X an ancestor?" ride the set unchanged.

- **Layout rule**: the FIRST superclass's effective slots keep their indexes, each later
  superclass appends its not-yet-present slot names, a diamond keeps ONE copy. Inherited
  slot OPTIONS are re-merged from the direct specs along the CPL (`cplMergedSlot` +
  `shadowSlotSpec`).
- **Shifted accessors**: a non-first superclass's accessors bake THEIR index while their
  class test covers the subclass, so `expandDefclass` synthesizes overriding
  reader/accessor/`%setf-` writer methods specialized on the SUBCLASS for every inherited
  slot whose index differs in any CPL ancestor. Single inheritance never shifts.
- **Dispatch refinement** (`miRefinement`, gated on `ClosRegistry.hasMultipleInheritance()`
  so single-inheritance dispatchers stay byte-identical): a class whose applicable class
  specializers have NO single most-specific member gets an EXACT-TAG branch
  (`(%obj-is x '%class-X)`, X alone) placed by the same specificity sort. Simple body: it
  calls the method X's CPL ranks first. Combined body: the effective method is computed
  against X (per-branch order via `branchSpecificityOrder`, ranking class specializers by
  the branch class's CPL index). Classes WITH a dominator need no branch.
  **LITE residual**: refinement handles class dispatch on ONE parameter position; a
  diamond-affected class meeting a generic that class-dispatches on several positions keeps
  the per-specializer branches and can miss the second super's methods.
- `conditionReportGroups` inherits `:report` along the CPL; `define-condition` with several
  REGISTERED parents does real MI (an UNREGISTERED extra parent falls back to
  `registerExtraAncestors`); `change-class`'s initform fill SKIPS a source whose layout is
  not a base-name prefix of the target; `%class-meta-table%`'s superclass column is a LIST;
  mop-protocol.lisp's `finalize-inheritance` merges inherited effective slots across ALL
  direct supers (first name wins).

Tests: `LispEvaluatorTest#defclassMultipleInheritance*`/`#defclassDiamond*`/
`#defclassCircularSuperclassesSignalInconsistentPrecedence`,
`JvmLispCompilerTest#compileAndRunDefclassMultipleInheritance*`,
`WasmLispCompilerIntegrationTest#compileAndRunDefclassMultipleInheritance`, ci-spec
`clos-multiple-inheritance-cpl-slots-and-dispatch`.

## Where each path hooks in

- **Interpreter**: `evalDefclass` (expands + evals defuns, then REGENERATES every dispatcher
  with a class-specialized method), `evalDefstruct` (likewise for struct-specialized or
  `structure-object` methods, `GenericInfo.hasStructMethod`), `evalDefgeneric`,
  `evalDefmethod`. Works anywhere (REPL/load/macro expansion). A `#'generic` captured BEFORE
  a later defmethod is NOT stale: `evalFunction` answers a LATE-BOUND wrapper for any name
  `closRegistry.findGeneric` knows, resolving at call time. Non-generic names keep the
  direct value; the compile paths never had the edge. Pinned by
  `aCapturedGenericFunctionValueSeesLaterMethods`.
- **Compilers**: `LispMacroExpander.expandTopLevelDefinitions(program, structAccessors,
  closRegistry)` sits at the old `expandTopLevelDefstructs` slot (after `flattenTopLevel`,
  before `LambdaLists.desugarProgram` -- the constructors use `&key`). It walks the whole
  program collecting classes/methods, splices defuns in place, and inserts each generic's
  dispatcher at its defgeneric's position (or the first defmethod's) AFTER the walk, so
  descendant and method sets are complete regardless of definition order. Non-top-level
  defclass/defgeneric/defmethod -> `Jvm/WasmExprCompiler` "only supported as a top-level
  form".
- **`make-instance`/`slot-value`** are `CL_MACROS`, expanding at the three dispatch sites
  (`evalCons` + both ExprCompilers); a literal quoted name gets the static expansion.
  `expandSetf` has a `closRegistry` parameter with a SLOT_VALUE place case.
  `#'make-instance` as a VALUE gets a REFERENCE_GATED wrapper forwarding to the generated
  `%mop-make-instance` (the interpreter defines it natively); a DIRECT call whose class
  argument is computed lowers in `expandMakeInstance` to `%mop-make-instance` (name symbol
  or class metaobject) and flips the SAME gate (`referencesMakeInstanceValue`). A runtime
  slot NAME dispatches through
  `%slot-value-runtime`/`%slot-boundp-runtime`/`%slot-value-set-runtime` (the setf twin
  separately gated by `needsRuntimeSlotSetDispatch`, so read-only programs stay
  byte-identical; the interpreter serves `%slot-value-set-runtime` as a builtin).
- **`UserMacroExpander`**: top-level defclass/defgeneric/defmethod are `macroEval.eval`'d
  into the macro-time evaluator (so a defmacro body can CALL a generic at expansion time)
  AND kept for the compilers. Walker cases keep defmethod lambda lists and defclass
  names/options verbatim; only defmethod bodies and defclass `:initform` values are walked.

## Name resolution gotcha

`defclass`/specializer class names are package-resolved (canonical, `zoo::dog`); the quoted
name in `(make-instance 'dog)` is NOT. `ClosRegistry.findClass` falls back from the exact
normalized key to a UNIQUE base-name match across packages; two packages defining the same
class name make the bare spelling unresolvable (qualify it). `slot-value` matches by slot
base name likewise. `defmethod` stores the specializer as the FOUND class's canonical name.

## Setf methods -- `(defmethod (setf name) ...)` / `(defgeneric (setf name) ...)`

`normalizeSetfMethodForm` rewrites onto the `%setf-` writer-generic convention: the name
becomes `%setf-<place>` (`setfFunctionName`) and the place joins `structAccessors` under
`SETF_FUNCTION_MARKER`, so `(setf (name args...) v)` funcalls the writer dispatcher with NO
change to `expandSetf`. A user setf method MERGES with accessor-generated writer methods
(different specializer) or replaces one (same specializer).

**Ordering constraint**: normalization sites are `expandTopLevelDefinitions`'s defmethod AND
defgeneric branches, the let-nested walkers
(`expandLetNestedDefmethods`/`rewriteNestedDefmethods`, threading `structAccessors`), and
the interpreter's `evalDefmethod`/`evalDefgeneric` -- all BEFORE anything casts the name
position to `LispSymbol`. `#'(setf name)` resolves through the existing setf-function route.
Package-qualified places produce `%setf-PKG::NAME`.

Tests: `LispEvaluatorTest#setfMethod*`/`#defgenericSetfNameWithInlineMethod`,
`Jvm/Wasm*#compileAndRunSetfMethodDispatchesPerClass`, ci-spec
`clos-setf-methods-and-setf-generic`.

## `(setf (find-class 'alias) (find-class 'target))` -- class name aliases

**Invariant: an alias is a second NAME for one class, never a second class.**
`ClosRegistry.classAliases` maps alias -> the target's CANONICAL name (resolved at
registration, so one level deep); `findClass` consults it right after the exact-name lookup,
ahead of the package-tolerant fallbacks -- `aliasTarget` matches the exact spelling, the
qualified spelling's member, then a UNIQUE member match over the alias table (mirroring
`uniqueByMember`). Every consumer goes through `findClass`, so metaobject, instance tag,
layout, ancestor set and `%obj-ref` indexes stay the TARGET's;
`(eq (find-class 'alias) (find-class 'target))` holds.

Registration is at EXPANSION time (`LispMacroExpander.expandSetfFindClass`, the `FIND-CLASS`
case of `expandSetf`) -- the moment both paths share; the compile path expands it in
`expandTopLevelDefinitions` via a dedicated `isSetfFindClassForm` branch beside the `deftype`
one, so the alias is registered BEFORE the class tables are built. It becomes an extra
SPELLING of the target's `%class-meta-table%` entry, an extra name in the runtime `typep`
tag table, and an extra member of the runtime-`subtypep` universe. The form's value stays
the target's metaobject.

**Divergences.** Only the ALIASING shape is accepted: both names literal quoted symbols, the
value a `find-class` call -- anything else throws naming the supported shape; an unknown
target throws `there is no class named`. A NON-top-level alias is an error:
`classMetaTableForms` calls `ClosRegistry.markClassMetaTableEmitted`, after which
`registerClassAlias` refuses (the interpreter never emits that table). Both restrictions
follow from the static class model. `--no-gc` shares one never-mutated
`EMPTY_CLOS_REGISTRY` for its CLOS-free `expandSetf` overload, so the place is rejected
outright there.

Tests: `LispEvaluatorTest#setfFindClassRegistersAnAliasNameForTheSameClass`/
`#setfFindClassAliasIsVisibleToHandlerCase`/
`#setfFindClassRejectsNonAliasShapesAndUnknownTargets`,
`JvmLispCompilerTest#compileSetfFindClassRegistersAnAliasNameForTheSameClass`,
`WasmLispCompilerIntegrationTest#compileSetfFindClassRegistersAnAliasNameForTheSameClass`,
ci-spec `find-class-metaobject-substrate`. Macro-namespace twin:
`(setf (macro-function ...))`, `.kb/defmacro-backquote.md`.

## A user method on a BUILT-IN name

**A built-in whose name a program defines a method on becomes that generic's DEFAULT
METHOD.** Otherwise the dispatcher defun SHADOWS the built-in and every non-instance
argument dies with "No applicable method: CLOSE on INTEGER".

- `generateDispatcher(name, registry, builtinFallback)` is the overload; the 2-arg one
  passes null and emits the byte-identical old shape. The fallback replaces
  `noApplicableMethod(...)` in BOTH bodies, supplies `buildCore`'s primary when a branch has
  only `:before`/`:after` methods, and closes the primary chain as `buildNextChain`'s base.
  A user DEFAULT (unspecialized) method still wins outright.
- `fallbackCall` takes no leading `%next-method` thunk and, for a variadic generic, spells
  `(apply #'<stash> params... %gf-rest)` -- that tail carries `close`'s `&key abort`.
- **Interpreter**: `LispEvaluator.defineDispatcher` is the ONE installation seam (all four
  sites in `evalDefclass`/`evalDefgeneric`/`evalDefmethod` route through it).
  `builtinDefaultMethodFor` stashes the built-in under
  `LispMacroExpander.builtinDefaultMethodName` (`%<generic>--builtin`) and MEMOIZES the hit
  in `builtinDefaultMethods`. **The memo is load-bearing**: the dispatcher is regenerated on
  every `defmethod`, and the second pass would find no built-in to stash and silently drop
  the default method. A Java-backed built-in is a `LispFunction`; a user/prelude `defun` is
  a `LispLambda`, deliberately left to be shadowed -- that type test is also why a MISS
  needs no memo.
- **Compile paths** (`compiler/ShadowedBuiltins`, run by BOTH backends right after
  `expandTopLevelDefinitions`): `(close X)` is compiler-lowered no matter what defuns exist
  (`Jvm/WasmExprCompiler` `case LispNames.CLOSE` -> `Jvm/WasmCloseCompiler`), so the spliced
  dispatcher defun is dead. Per name in the COMPUTED set (every registered generic whose
  name is in `ShadowedBuiltins.loweredBuiltinFunctions()`): replace the dead dispatcher
  (found by structural equality against a regenerated 2-arg dispatcher, so a user defun of
  the same name is never mistaken for it) with the interpreter's dispatcher body renamed
  `%<name>--dispatch` (`shadowedBuiltinDispatcher`); bind `%<name>--builtin` to a FORWARDER
  defun spelling the original built-in call (`builtinForwarderDefun`; the `&rest` tail is
  dropped -- lite built-ins ignore keyword tails); rewrite call sites and `#'name`
  references onto the dispatcher. The walker skips quoted data, `defmacro`/`macrolet`
  bodies, the generated defuns themselves (the Gray `DISPATCH_DEFUNS` rule -- rewriting the
  forwarder's fallback would recurse) and the non-evaluated positions of
  `let`/`lambda`/`flet`/`do`/`dolist`/`case`/`handler-case`. When `close` is shadowed,
  `with-open-file`/`with-open-stream`/`with-*-to-string` are pre-expanded
  (`unwindProtect=true`) so their implicit `(close f)` routes through the dispatcher -- side
  effect: such a WASM module is always in EH mode.
- **The name set**: `BuiltinFunctionWrappers.names()` minus `%`-internals, minus
  `NOT_SHADOWABLE` (signal operators, which double as handler-case clause heads, plus
  `make-instance`/`class-of`), minus `EXPANSION_LOWERED` (names the INTERPRETER evaluates
  via `evalCons`/macro expansion rather than a global `LispFunction`), plus
  `LOWERED_WITHOUT_WRAPPER` (`close` first). **Pinned by `ShadowedBuiltinsTest`: every member
  must be a Java-backed `LispFunction` in a fresh global environment** -- the exact
  interpreter stash criterion, so the two halves keep one boundary.
- **Remaining divergences (triggers)**: (1) a plain `(defun close (x) ...)` is ignored by
  the compile paths while the interpreter honors it at call sites; (2) `EXPANSION_LOWERED`
  names (mapcar, sort, format, ...) are un-dispatchable everywhere -- move them out of the
  exclusion if the interpreter ever routes them through global function bindings;
  (3) under `--component` with sockets spliced, `WasmSocketsRewrite` runs BEFORE this pass
  and has already turned `close`/`listen`/... into `rontolisp::%io-*` calls -- the pass
  COMPOSES via `WasmSocketsRewrite.builtinDispatchAliases` (alias heads rewrite onto the
  dispatcher; the forwarder's fall-through calls the `%io-*` defun). Without it an instance
  reaching `%io-close` was a wasm CAST-FAILURE trap. Still open: the ASYNC read promotions
  (`read-line`/`read-char`/`read-byte` -> `(await (%*-future ...))`) bypass a user method;
  (4) a runtime designator (`(funcall 'close x)`, `symbol-function`) does not dispatch on
  the compile paths.

Tests: `LispEvaluatorTest#defmethodOnABuiltinName*`/
`#defmethodOnAVariadicBuiltinNameForwardsTheKeywordTail`/
`#defgenericOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod`,
`JvmLispCompilerTest#compileAndRunDefmethodOnABuiltinName*`/
`#compileAndRunDefgenericOnABuiltinNameKeepsTheBuiltinAsTheDefaultMethod`,
`WasmLispCompilerIntegrationTest#defmethodOnABuiltinName*`, ci-spec
`defmethod-on-a-builtin-name-keeps-the-builtin`, `FastIoCircularStreamsE2eTest`.

## The instance-initialization protocol

`initialize-instance`, `reinitialize-instance`, `shared-initialize` are CL symbols
(`PackageRegistry.CL_FUNCTIONS`, like `print-object`): CL has ONE generic each, so a method
in any `(:use :cl)` package joins the same generic. Otherwise each package mints its own and
`make-instance`'s chain -- which matches generics by PLAIN name, first hit -- runs one
package's chain while another's hooks never fire. Pinned by
`LispEvaluatorTest#initProtocolGenericsAreSharedAcrossPackages`.

They have no system primary in the static subset (the constructor already fills slots), so
`expandDefmethod` SYNTHESIZES one the first time any method is defined on one of them, plus
the CL chain: `initialize-instance`'s and `reinitialize-instance`'s defaults are
`(apply #'shared-initialize instance t/nil initargs)`, `shared-initialize`'s returns the
instance. When no `shared-initialize` generic exists it is CREATED here; its dispatcher is
emitted by the caller (the compile path reserves a slot for every registered generic in
`addExpandedDefinition`; the interpreter defines missing dispatchers after each
`defmethod`). `expandMakeInstance` calls `shared-initialize` DIRECTLY (slot-names `t`) when
only that generic exists.

**The synthesis condition is the absence of an ALL-DEFAULT primary, not of any primary**: a
class-specialized primary must not displace the system method a sibling's
`(call-next-method)` has to find.

Three congruence rules:

- **A `&key` method never rejects a sibling's keyword.** CL takes the UNION of the generic's
  and every applicable method's keyword set; the lite model instead appends
  `&allow-other-keys` to every `&key` method lambda list.
- **A defclass constructor tolerates extra initargs** (`&allow-other-keys` on
  `%make-<name>`): a non-slot initarg belongs to an init-protocol method.
- **A bare `(call-next-method)` forwards the WHOLE original argument list**:
  `rewriteNextMethod` emits `apply` over the method's `&rest` variable, injecting one
  (`%method-args`) when the method declares a `&key` tail without a `&rest`. **`&optional`
  needs more than the rest variable** -- `&rest` binds what is left AFTER the optionals, so
  forwarding it alone silently drops them. A method that chains has every `&optional` entry
  normalized to `(var init supplied-p)` (synthesizing the supplied-p variable) and the bare
  call becomes `(apply %next-method req... (append (if sp1 (list o1) nil) ...
  %method-args))` -- CL's rule that an UNSUPPLIED optional is not passed on. Gated on the
  body mentioning `call-next-method`/`next-method-p`, so other methods' code is unchanged.
  Pinned against SBCL over six tail shapes (none, `&rest`, `&key`, `&optional`,
  `&optional &rest`, `&optional &key`) for `:around`->primary and primary->primary.

**Cold-branch tolerance**: on the COMPILE paths only, `expandMakeInstance(cons, registry,
true)` lowers an unknown class to a runtime `error` instead of failing the compile (the
compilers expand every branch eagerly). The interpreter passes `false`. Same tolerance
`format` has for a cold-branch control string.

## Ambiguous slot writes and runtime `typep`

- `(setf (slot-value obj 'NAME) v)` with NAME at DIFFERENT indexes in unrelated types does
  not error at compile time: `expandAmbiguousSlotSet` emits an instance-TAG dispatch writing
  the type-correct index. Routing through the reader generic only worked when the class
  declared an `:accessor`.
- `(typep x COMPUTED-SPEC)` does not error. The INTERPRETER re-expands per call through
  `expandRuntimeTypep`: a `cond` on the specifier symbol, one arm per registered layout
  (canonical name AND plain member name) plus one per built-in atomic type name
  (`RUNTIME_TYPEP_BUILTINS`), each arm carrying the very test the literal specifier would
  have compiled to. An unrecognized specifier -- a COMPOUND one included -- yields nil
  rather than signalling.
  **A runtime specifier is matched by SPELLING**; the reverse of the member-name fallback (a
  QUALIFIED specifier against a class registered under a PLAIN name) is deliberately not
  emitted. The only plainly-registered classes are the seeded ones, whose names are `cl`
  symbols, so a `(:use #:cl)` package's `'type-error` IS the plain spelling
  (`.kb/error-handling.md`, `.kb/packages.md`). **Trigger**: a plainly-registered class whose
  name is not a `cl` symbol.
- **The COMPILE paths must not inline that dispatch**: its size is proportional to the
  registered-class count, and at cl-postgres scale (165 layouts, ~68 KB of AST per call
  site) three sites overflowed the JVM's signed-16-bit branch offsets
  (`StackMapAugmenter: Index -31123 out of bounds`). `expandTypep(cons, registry, false)` --
  what `Jvm/WasmExprCompiler` call -- emits `(%typep-runtime value spec)`, and
  `expandTopLevelDefinitions` injects, once the registry is complete, the `%typep-runtime`
  defun (built-in arms plus a table scan) plus the `%typep-tag-table%` data table -- quoted
  data mapping each type name's spellings to the instance tags it accepts, emitted as
  CHUNKED top-level forms (a defvar plus `(setq .. (append 'chunk ..))` continuations, 48
  entries each). Same for `%subtypep-runtime` (grouped ancestor sets moved into
  `%subtypep-ancestor-table%`; the inline shape had reached 59 KB) and for a computed
  `error` condition-type datum WITH initargs: `(error (get-error-type code) :code ...)`
  lowers to `(%error-runtime datum (list args...))`, dispatching over small per-condition
  helpers (`%ERROR-RT-n`, the literal typed expansion over `getf` reads with the slot
  `:initform` as the getf default) -- the old per-site inline reached 90 KB, past the JVM
  64 KB hard method limit. Non-condition class names fall to the object-designator path.

Tests: `JvmLispCompilerTest#compileAndRunTypepWithComputedSpecifier` /
`#compileAndRunErrorWithComputedConditionType`,
`WasmLispCompilerIntegrationTest#typepWithComputedSpecifierAndRuntimeErrorType`, ci-spec
`runtime-type-dispatch-and-symbol-designators`.

## Runtime slot names + class introspection on the compiled backends

- `expandClassSlotDefs` lowers `%class-slot-defs` to the SHARED
  `%class-slot-defs-runtime` defun, injected by `expandTopLevelDefinitions` (gated on a
  reference) with its `%class-slot-defs-table%` data table -- one entry per registered
  LAYOUT (classes AND structs, since `ClosRegistry.slotDefs` is the one resolver both it and
  the interpreter use). Designators: the instance tag (`%class-NAME`/`%struct-NAME`, what
  `%class-designator` yields), the plain name, and a class metaobject (its name slot is
  read). Answers `((slot-name declared-type) ...)`; a struct's types all read `T`; anything
  else nil. A struct answering here is what lets json.lisp's `%json-out-instance` treat a
  struct like a CLOS instance. The old per-site inline cond hit the JVM 65535-byte method
  ceiling (70178 bytes). The table is chunked by cons-node budget
  (`nodeBudgetedTableForms`), not entry count.
- `expandSlotValue` with a non-literal name falls to `expandRuntimeSlotValue`: a NAME
  dispatch over every slot name any layout declares. Names at the same index everywhere
  share one `member` arm; a name at differing indexes gets an inner `%obj-is` TAG dispatch.
  Only an unknown name signals at run time.
- `expandSlotBoundp` with a non-literal name falls to `expandRuntimeSlotBoundp`:
  instance-tag dispatch, each arm a `member` of the runtime name over that type's declared
  slot names.

Tests: ci-spec `runtime-type-dispatch-residue`, `JzonE2eTest`'s CLOS stringification case.

## A RUNTIME class designator: both colon spellings

The compile paths carry no package registry at run time, so `intern` always assembles the
single-colon EXTERNAL spelling `PKG:MEMBER` (`.kb/symbol-runtime-api.md`) while the class is
registered canonically as `PKG::MEMBER`. Every generated designator dispatch matches by
SPELLING, so such a lookup used to miss a class the program did not EXPORT
(`%MOP-MAKE-INSTANCE: not an instantiable class: SPEC-REP`) while the interpreter resolved
it (`ClosRegistry.normalize` folds `:` to `::`).

The fix is at the LOOKUP: `LispMacroExpander.addDesignatorSpellings` is the ONE place
turning a registered name into the spellings that designate it (canonical plus, for a
qualified name, the single-colon one), and every generated table/dispatch built from a
class, struct, condition or `(setf find-class)` alias name goes through it:
`%class-meta-table%`, `%mop-make-instance` (and its `%MMI-REFILL` arms),
`%allocate-instance`, `%class-direct-subclasses`, the runtime `typep` tag table, the
`subtypep` universe, and the condition-class dispatch of `error`/`signal`
(`%error-runtime`). One package cannot house two distinct symbols with one member name, so
the added spelling can never designate another class; a `cl-user` program is byte-identical.

Tests: `LispEvaluatorTest#evalRuntimeClassDesignatorResolvesAnInternedNonExportedName`,
`JvmLispCompilerTest#compileRuntimeClassDesignatorResolvesAnInternedNonExportedName`,
`WasmLispCompilerIntegrationTest#runtimeClassDesignatorResolvesAnInternedNonExportedName`,
ci-spec `runtime-class-designator-spellings`.

## `change-class` -- in place, on all four backends

`(change-class instance 'name initarg value ...)` mutates IN PLACE: object identity and
every shared slot survive, slots the new class adds are filled from initforms, supplied
initargs stored, the instance is the value.

- `expandChangeClass` emits: capture the OLD tag -> `(%obj-become obj '%class-T)` -> a
  `cond` on the captured tag filling `[slotCount(source), slotCount(target))` from the
  target's initforms -> the initarg stores -> the instance. **The tag is captured BEFORE the
  swap** (the fill must know which class the instance WAS but can only store into the WIDER
  layout).
- **`LispLayout.capacity()` is why this works on the JVM**, where an instance IS its
  `Object[]` and cannot grow without losing identity: every ancestor of a `change-class`
  target reserves the target's slot count at construction.
  `expandTopLevelDefinitions` scans the program for targets (`registerChangeClassTargets` --
  the form lives in a body, not at top level) and calls `applyChangeClassCapacities()` once
  the registry is complete. `%obj-slots` and the printers bound themselves by the LAYOUT's
  slot count, never the array length.
- **The WASM instance struct's field 0 is MUTABLE** for this, making it structurally
  identical to `TYPE_P1_FUTURE` `{mut i32, mut eq}` -- so its rec group carries a second,
  never-instantiated empty struct: wasm canonicalizes a rec GROUP as a whole and a 2-member
  group can never equal a 1-member one, so `ref.test` keeps telling an instance from a
  future (`INSTANCE_TYPE_COUNT = 2`). **Do not "simplify" it.**
- The interpreter needs no reservation (`LispInstance.becomeLayout` grows the array; the
  LispInstance is the identity); the reservation is harmless there and keeps ONE expansion
  for all four backends.

**The class argument may be COMPUTED**: a runtime symbol or a class metaobject. The
interpreter resolves natively (`LispEvaluator.resolveChangeClassDesignator`: evaluates
instance then designator in CL order, folds a metaobject to its name slot, re-enters the
static expansion). The compile paths lower to `(%change-class-runtime obj cls (list
initargs...))`; `expandTopLevelDefinitions` (gate `needsChangeClassRuntime`) generates the
dispatch -- metaobject-name normalization preamble, then one spelling-matched `%CC-<n>` arm
per registered NON-SEEDED class (`addDesignatorSpellings`, `equal` compares -- content-safe
on WASM), each arm the static expansion's shape with initargs read out of the plist via
`getf` against a private marker. Because any class can be the runtime target, every
non-seeded class joins the change-class CAPACITY reservation (`registerChangeClassTarget`
over the whole registry) -- the computed shape pays instance arrays sized to the widest
reachable layout. Tests:
`LispEvaluatorTest#changeClassAcceptsAComputedClassDesignator`,
`JvmLispCompilerTest#compileAndRunChangeClassWithComputedDesignator`,
`WasmLispCompilerIntegrationTest#reinitializeInstanceAndComputedChangeClass`, ci-spec
`clos-computed-change-class-442`.

## Real slot unboundness

**A slot written with no `:initform` starts UNBOUND, not nil.** Reading signals
`unbound-slot`; `slot-boundp` says nil; `slot-makunbound` puts it back. jzon's
`coerced-fields` and json.lisp's `%json-out-instance` SKIP an unbound slot.

- The marker is an instance of a LAYOUT-ONLY internal type, `ClosRegistry.UNBOUND_TAG` =
  `%class-%UNBOUND%` (registered in the `ClosRegistry` constructor, deliberately NOT in
  `classes()`: a class there would join every typep tag table, `class-slot-defs` answer and
  `standard-object` descendant set). Testing for it is the same one-compare `%obj-is`. It
  prints `#<%UNBOUND%>` if it escapes.
- `SlotSpec.initformSupplied` records whether the source wrote an `:initform`; `initform`
  then holds `(%obj-new '%class-%UNBOUND%)`.
- Every read goes through ONE out-of-line helper `(%slot-read value instance 'NAME)`, with
  `(%slot-bound-p value)` for the `slot-boundp` arms (`LispMacroExpander.slotUnboundDefuns`,
  emitted when `needsSlotUnboundHelper`; the interpreter defines them on first resolution).
  **They are calls, not inlined `%obj-is` + `if`, for a size reason**: an inline instance-of
  test is ~60 bytes of JVM bytecode, and one per accessor and per `slot-value` ran the
  ci-spec corpus past the 64 KB method limit
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- In a GENERATED accessor body the `'NAME` rides in `%unspelled-quote`
  (`LispMacroExpander.checkedSlotRead`), so it must not arm the funcall-dispatch gate's name
  probes ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)); a
  user-written `slot-value` keeps the plain quote.
- `expandSlotValue` is the CHECKED read; `expandSlotValueRaw` the unchecked one the `setf`
  place expansion needs. A RUNTIME slot name keeps the raw read.
- `unbound-slot` is seeded under `cell-error` (which gained CL's `name` slot, so
  `cell-error-name` works and `unbound-variable`/`undefined-function` inherit it) with an
  extra `instance` slot and a `:report` LAMBDA built in Java
  (`ClosRegistry.unboundSlotReport`, over `%obj-ref` indexes rather than `slot-value`, which
  would drag the ambiguous-name dispatch into every program). `type-error-datum`,
  `type-error-expected-type`, `cell-error-name`, `unbound-slot-instance` are prelude defuns
  (`LispPreludeLibrary`).

## Inherited-slot shadowing, `:default-initargs`, `with-slots`/`with-accessors`

- **A subclass may re-declare an inherited slot** (CLHS 7.5.3): STORAGE stays the one
  inherited slot (descendants keep the baked index) while the subclass spec overrides
  initform/initarg and ADDS its readers/accessors (`LispMacroExpander.shadowSlot`; "written
  or not" survives parsing as `ParsedSlot.initargSupplied` + `SlotSpec.initformSupplied`).
- **`(:default-initargs :arg form)` overrides the matching slot's effective initform**,
  which is where BOTH construction paths read it (the constructor's keyword default and the
  registry initform `buildTypedConstruct` fills unsupplied slots from). Before this it only
  reached the constructor, so `(error 'my-cond)` ignored it.
- **`with-accessors`** is the accessor-call twin of `with-slots` (`expandWithAccessors`),
  needing no registry. **`with-slots` resolves `defstruct` slots too**: `expandSlotValueRaw`
  picks the index out of the LAYOUT registry (`uniqueLayoutSlotIndex`, both kinds) instead
  of the class-only `slotPosition` map, falling back to the tag dispatch when types
  disagree.
- **`with-slots`' entry-time fallback binding is BOUNDNESS-GUARDED**: besides substituting
  each slot variable textually, `expandWithSlots` `let`-binds it to an entry-time read so
  code GENERATED inside the body (a user macro whose template mentions a slot variable)
  still resolves. That read is `(if (slot-boundp obj 'slot) (slot-value obj 'slot) nil)`,
  never a bare `slot-value` (`LispMacroExpander.boundOrNil`). **The guard is not cosmetic**:
  `with-slots` BINDS, never reads, so a body that only ASSIGNS a slot declared without an
  `:initform` must not signal on entry (fast-io's `(with-slots (buffer) self (setf buffer
  ...))` inside `initialize-instance`). A read the body REALLY performs still signals. Cost:
  one extra dispatch per named slot per entry on the compile paths. **`with-accessors`'
  fallback is NOT guarded** -- an accessor is a generic with no boundness twin, and wrapping
  it would force EH mode on WASM for every `with-accessors`; a write-only `with-accessors`
  over an unbound slot still signals. Pinned by
  `LispEvaluatorTest#withSlotsBindsAWriteOnlyUnboundSlot` +
  `#withSlotsStillSignalsWhenTheBodyReadsAnUnboundSlot`,
  `JvmLispCompilerTest#compileAndRunWithSlotsWriteOnlyUnboundSlot`,
  `WasmLispCompilerIntegrationTest#multiParameterDispatchVariadicGenericsAndDefaultInitargs`,
  ci-spec `with-slots-write-only-unbound-slot-and-missing-slot`.
- **The instance temp is named PER FORM, so a NESTED `with-slots`/`with-accessors` cannot
  capture the enclosing one's** (`LispMacroExpander.freshObjVar`). With the old fixed
  `__with_slots_obj` name an inner form rebound it and every outer read inside the inner
  body resolved against the INNER instance, silently, at whatever slot the outer's name sits
  at in the inner layout. The name is chosen by scanning the whole form and stepping past
  (`__with_slots_obj`, `__with_slots_obj2`, ...) -- a pure function of the form, no counter,
  so the same program emits the same bytes (`.kb/emitted-output-determinism.md`). It also
  covers an inner `with-slots` produced only by a later user-macro expansion: the outer
  substitution PLANTS its temp name in the body it wraps. Pinned by
  `LispEvaluatorTest#aNestedWithSlotsDoesNotCaptureTheEnclosingInstance` +
  `#aNestedWithAccessorsDoesNotCaptureTheEnclosingInstance`, ci-spec
  `array-operations-enablement-language-group`.
- **A `slot-value` naming a slot NO registered class declares is a RUN-time error**
  (`LispMacroExpander.missingSlotStub`): subforms evaluate for effect, then
  `(error "The slot X is missing")` -- read side and `setf` place alike. It used to throw
  out of the expander, failing the whole BUILD on the eagerly expanding compile paths over a
  read that may never execute. Signalling also makes it a condition `handler-case` can see,
  like CL's `slot-missing` protocol. `slot-boundp` on an undeclared slot answers nil.

## The CLOS surface batch

- **The instance-initialization generics are CALLABLE with no user method.**
  `LispMacroExpander.synthesizeCalledInitProtocolGeneric` creates the plainly-named generic
  and runs the SAME default synthesis (`synthesizeInitProtocolDefault`, chain included). The
  compile path calls it from `expandTopLevelDefinitions` for any of the three names the
  program references without a registered generic, reserving dispatcher slots; the
  interpreter calls it from `resolveFunction`'s tail. Consequence: a program that merely
  CALLS `reinitialize-instance` now has a `shared-initialize` generic, so
  `expandMakeInstance` routes construction through the protocol chain -- same values,
  different emitted shape.
- **`:writer`** (`SlotSpec.writers`): a symbol defines a two-argument new-value-first
  generic; `(setf place)` is stored pre-mangled as the `%setf-place` writer-generic name and
  the emission registers the place under `SETF_FUNCTION_MARKER`. Writers merge on shadowing
  (`shadowSlotSpec`) and are covered by the MI shifted-accessor synthesis.
- **`:allocation :class`** (`SlotSpec.sharedCellVar`): the value lives in ONE `defvar`'d
  global cell per DECLARING class (`%CLASS-CELL-<class>-<slot>%`), initialized at
  class-definition time; a subclass re-declaring with `:allocation :class` mints a new cell,
  one re-declaring WITHOUT reverts the slot to `:instance` (CLHS 7.5.3). The slot KEEPS a
  layout index -- a dead mirror -- so index computations, `slot-exists-p` and
  `%class-slot-defs` are untouched; every ACCESS routes to the cell: the effective spec's
  `initform` IS the cell symbol (so the constructor default, `change-class`'s fill and
  `%mop-fill-slots` read the current value); the constructor stores `(setq <cell> <var>)`;
  generated reader/accessor/writer methods use `checkedCellRead` and a re-declaring subclass
  re-emits the EFFECTIVE merged method set specialized on itself (inherited methods bake the
  ancestor's cell); literal-name `slot-value`/`setf`/`slot-boundp` route through
  `classSlotAccessByTag` (null for every ordinary program, keeping the historical expansion
  byte-identical), and the runtime-name dispatches carry the same cell arms via
  `sharedCellsByTag`; the interpreter's `instanceSlotRef` answers a `CellSlotRef` over the
  global environment, covering `slot-makunbound`.
  **Residual**: the instance PRINTER shows the mirror (stale after a shared write), and
  `equalp`/`%obj-slots` walk mirrors.
- **`standard-class` as a type specifier** in `typep`/`typecase`: `makeTypeTest` calls
  `ClosRegistry.ensureMopClassesSeededFor` on an unrecognized symbol specifier before the
  registry lookup, so `(typecase (find-class 'c) (standard-class ...))` expands to the
  ordinary descendant-tag test on every backend. `structure-class`/`built-in-class` keep
  their deliberate empty tests (a struct's metaobject IS a standard-class here).

Tests: `LispEvaluatorTest#reinitializeInstanceIsCallableWithNoUserMethod` /
`#defclassWriterSlotOptionDefinesTheWriterGeneric` /
`#defclassClassAllocationSharesOneCellPerDeclaringClass` /
`#typecaseStandardClassMatchesAClassMetaobject`, the JVM
`compileAndRun{ReinitializeInstanceWithNoUserMethod,DefclassWriterAndClassAllocation}`
twins, the WASM `reinitializeInstanceAndComputedChangeClass` /
`defclassWriterClassAllocationAndStandardClassTypecase` twins, ci-spec
`clos-reinitialize-442` / `clos-slot-options-and-metaobject-types-442`.

## `print-object` -- the printer consults it

A `defmethod print-object` makes the printing operators render that type through the
generic. **Gated on the program defining a method** (plus the condition and `*print-case*`
gates below): with none, every printing operator compiles exactly as before.

**The DIRECT call always works.** `(print-object x s)` resolves to the ordinary generated
dispatcher defun named `PRINT-OBJECT`. `synthesizePrintObjectDefault` registers CL's SYSTEM
method -- `(write-string (if *print-escape* (%prin1-to-string o) (%princ-to-string o)) s)`,
answering the object -- from `expandDefmethod` when the FIRST method is defined (same hook
and `hasDefaultPrimary` condition the init-protocol generics use) and from
`expandTopLevelDefinitions` / `LispEvaluator.resolveFunction` when the program only CALLS
the name (`isCallableSystemGenericName` / `synthesizeCalledSystemGeneric`).

- It renders through the RAW `%prin1-to-string`/`%princ-to-string`, not `%print-object-str`,
  as a **correctness requirement**: `printObjectTags` collects specializers from EVERY
  parameter while a dispatcher dispatches on the FIRST, so a method specialized on its
  STREAM parameter puts that class in the routed set and a routed default would recurse
  forever. Cost: a nested instance inside a value handed to a DIRECT call gets the raw
  rendering.
- **Two compile-path scans had to learn the name** (the synthesized defun does not exist
  when they run): the `expandTopLevelDefinitions` fast path (which returns early for a
  program with no definition to splice) and `injectMvSpillGlobal`'s `*print-escape*` gate
  (which counts `print-object` as a reference like `print-unreadable-object`). Without the
  second the default read an unseeded global and printed the `princ` spelling.
- `printObjectTags(registry)` is the routed tag set (class specializers and `defstruct`
  ones -- a struct name parses as a TYPE specializer carrying the struct name).
- `expandPrintObjectHook` rewrites `princ-to-string`/`prin1-to-string`/`write-to-string`
  (and the internal unwrapped piece spellings `%princ-piece`/`%prin1-piece`,
  `.kb/string-write-runtime.md`) to `(%print-object-str x escape)` and
  `print`/`princ`/`prin1` to a `write-string` of it (+ `terpri` for `print`). `format`'s
  `~A`/`~S` need no case of their own.
- The generated `%print-object-str` falls back to `%princ-to-string`/`%prin1-to-string` --
  INTERNAL ALIASES of the same two functions; without them the fallback would re-enter the
  rewrite that produced it. The interpreter INLINES the renderer instead of calling the
  defun, because it re-expands per call and must see a `defmethod` that follows the first
  print. The raw renderer carries the shared cycle guard (`.kb/pretty-printer.md`, "A cyclic
  value prints finitely"), and the walk's cons and vector arms carry the guard's Lisp twin
  (`%pos-walk` threads the rendering path and depth through itself, `%pos-chain-stop` is
  Floyd over the cdr chain), so a cyclic cons prints the same finite text routed and
  unrouted.
- **`*print-escape*` is BOUND around the method call** (`printObjectCall` wraps it in a
  `let`), `t` for prin1/print/`~S`, `nil` for princ/`~A`, so a portable method branching on
  `(and (null *print-readably*) (null *print-escape*))` behaves as in CL.
  `*print-escape*`/`*print-readably*` are `CL_VARIABLES` with CL's defaults; the interpreter
  seeds both into `specialVars`, and the compile path injects `(defvar ...)` from
  `LispMacroExpander.injectMvSpillGlobal`, which runs AFTER `expandTopLevelDefinitions` and
  therefore sees the route's own reference. **That ORDER is load-bearing**: a `setq` would
  not proclaim the name special, and injecting earlier would miss the reference the
  expansion creates.
- **Gate 2 is a condition's `:report`** (`.kb/error-handling.md`): the same rewrite fires for
  a program that can build a condition, and the escape-off arm of `%print-object-str`
  renders one through `%condition-report-str`. A `print-object` method on a condition class
  still wins (the method route is tested first). **Gate 3 is `*print-case*`**: the rewrite
  fires for a program that MENTIONS the variable, and the leaf every arm falls back to
  becomes `(%print-cased x escape)` -- the shared prelude renderer applying the variable to
  each symbol spelling, whose own leaves are the two raw aliases; it sits UNDER the method
  route (`.kb/pretty-printer.md`). There is exactly one place that decides what text a
  printing operator writes.
- **The method is consulted for a NESTED value too**: the generated renderer is a PAIR --
  `%print-object-str` walks a cons and a general rank-1 vector element-wise by recursing
  into itself, `%print-object-leaf` is the routing half (method / condition report / raw
  fallback). One Lisp-level walk rather than a hook in each backend's list renderer, the
  same choice `%print-cased` made; the two guards are twins to be read together.
  - **The walk must reproduce the raw renderer byte for byte** for what it does NOT route.
    Two shapes only (`.kb/pretty-printer.md` has the table): a cons (one space before every
    element but the first, `" . "` before a non-nil tail) and `#(...)`. No `'x` / `#'f`
    abbreviation and no `#*` bit-vector syntax exists here.
  - **The vector guard excludes what it cannot spell**: a string, an array of rank != 1
    (`#nA(...)`), a packed FLOAT array (`#d(...)`/`#f(...)`). Written as "the element type is
    not `single-float`/`double-float`", NOT "is `t`" -- the general answer is a `T` SYMBOL in
    the interpreter and the `t` VALUE on the JVM, which no single `eq` spans. A packed
    INTEGER vector is deliberately walked.
  - **The vector arm is emitted only when `programUsesGeneralArrayOp` answers true**, so a
    print-object/condition program with no array pulls no array runtime. An
    under-approximation costs the OLD behavior for a vector, never wrong output.
  - **The interpreter stopped INLINING the renderer** (a recursive walk cannot be) and
    (re)generates the defun pair whenever the routing moves --
    `LispEvaluator.ensurePrintObjectRuntimeLoaded`, stamped on the tag set +
    `routesConditionReports` + `*print-case*`. That is what lets a `defmethod print-object`
    evaluated AFTER the first print take effect.
  - **Still not walked (trigger)**: a value in a STRUCTURE or class SLOT, a hash table, an
    array of rank != 1 -- `#S(BOX :ITEM #S(NODE :VALUE 9))` where CL prints
    `#S(BOX :ITEM #<NODE 9>)`. Closing it means rendering the `#S(...)`/`#<...>` frame in
    Lisp too, keeping the pathname and condition arms.
  - **Cost**: O(n^2) in string concatenation, like `%print-cased`'s, on the path of every
    print in a program that merely CAN build a condition. Re-evaluate with
    `.kb/pretty-printer.md`'s "stream with no column" trigger.
- `print-unreadable-object`'s `:type t` prints the type NAME with the `%struct-`/`%class-`
  tag prefix stripped INLINE (`typeNameOf`), not via the prelude's `type-of`: this expansion
  runs inside the compilers, after the prelude pre-pass, and a direct compiler invocation has
  no prelude pass. The separating space is written only when a body follows. `:identity` is
  accepted and prints NO address (no object-identity token exists in the value model).
- **That type designator follows `*print-escape*`** (CLHS 22.1.3.3 drops a symbol's package
  qualifier when escape is off): `prin1` of a `quri:uri` gives `#<QURI:URI ...>`, `princ`
  gives `#<URI ...>` (SBCL-checked). No qualifier strip is needed -- the tag prefix attaches
  to the PACKAGE half (`%struct-MAP-SET:MAP-SET`), so a qualified name's member part is
  already the bare type name and only the unqualified spelling reaches the prefix-stripping
  cond. The reference is late (Pass 2), so `injectMvSpillGlobal` counts an un-expanded
  `print-unreadable-object` operator as the mention declaring `*print-escape*`.

## Short-form `:method-combination`

`(defgeneric g (x) (:method-combination NAME [:most-specific-first |
:most-specific-last]))`, NAME one of `ClosRegistry.SHORT_FORM_COMBINATIONS` --
`progn`/`and`/`or`/`+`/`list`/`nconc`/`append`/`max`/`min`. The CLHS **long** form
(`define-method-combination`) is out of scope; a NAME outside the set is REJECTED at
`defgeneric` time, not silently ignored.

- `GenericInfo` gains `methodCombination` + `mostSpecificLast`.
  `LispMacroExpander.registerDefgeneric` parses and records the option BEFORE the inline
  `(:method ...)` clauses expand -- an inline `(:method progn ...)` is a plain `defmethod`
  and must already see the combination.
- `expandDefmethod` reads the combination for the legal qualifier set: the combination NAME
  becomes a primary qualifier, `:around` stays legal, `:before`/`:after` are REJECTED
  (CLHS). A combination generic whose `defmethod` carries no qualifier is rejected too.
- `LispMacroExpander.shortFormEffectiveMethod` builds `(NAME (m1 nil args...) (m2 nil
  args...) ...)` over EVERY applicable method of that qualifier in branch-specificity order,
  reversed under `:most-specific-last`. The `next` argument is nil throughout -- CLHS gives
  short-form primaries no `call-next-method`; only an `:around` has one, and its next is the
  combined form (`buildNextChain`/`callWithNext` shared with the standard combination).
- No applicable method is the ordinary `noApplicableMethod` error, NOT an empty `(progn)`:
  an empty `(and)` answers `t` and an empty `(+)` zero, hiding a missing method behind a
  plausible value.

Tests: `LispEvaluatorTest#evalShortFormMethodCombination*`,
`JvmLispCompilerTest#compileAndRunShortFormMethodCombination`,
`WasmLispCompilerIntegrationTest#shortFormMethodCombination`, ci-spec
`defgeneric-short-form-method-combination`.

## MOP boundary -- what is IN

The STATIC metaobject subset is IN: `find-class` AND `class-of` answer a real memoized
`standard-class` instance on all four backends, `eq` to each other for the same class.

- **Interpreter**: Java built-ins over `ClosRegistry.classMetaobject` (designator-aware:
  plain names AND instance tags; struct layouts answer too -- a struct class is a
  `standard-class` instance, `structure-class` does not exist) and
  `ClosRegistry.builtinClassMetaobject` (`BUILTIN_CLASS_NAMES` = exactly the
  `%class-designator` result set, `T` for everything else). Seeding is lazy
  (`ensureMopClassesSeeded()`) -- **NEVER seed unconditionally**: that joins every runtime
  dispatch table and once pushed the ci-spec corpus over the JVM 64 KB method ceiling.
- **Compile paths**: `expandTopLevelDefinitions`, gated on the program referencing
  `find-class` OR `class-of`, injects a `%class-meta-table%` data table
  (node-budget-chunked, one entry per registered class AND struct layout: spellings -- the
  instance TAG among them -- / superclass / effective-slot data) plus the generated
  `%find-class` (table scan, then the built-in class fallback, CL errorp semantics) +
  `%find-class-materialize`. The public `find-class` defun is a thin wrapper injected only
  when the program references it without defining it, so a user `find-class` never changes
  what `class-of` answers. `(class-of x)` expands to
  `(%find-class <%class-designator dispatch> t)`.
- The OLD tag/type-name view lives on as the internal `%class-designator`, ridden by the
  light consumers (prelude `type-of`, `print-unreadable-object :type`, the
  no-applicable-method message, json.lisp's `%json-out-instance`) -- they drag no metaobject
  runtime in and keep every non-MOP program byte-identical. `%class-slot-defs` accepts a
  class metaobject as designator (`%class-slot-defs-runtime` gains that preamble only when
  `standard-class` is registered).
- **`typep`/`subtypep` take a class METAOBJECT wherever a type specifier is expected.** ONE
  normalization rule -- "an instance tagged as a `standard-class` descendant continues as
  its slot-0 name" -- with three emission sites: `subtypep` folds it in Java for the
  interpreter (`LispMacroExpander.classMetaobjectDesignator`, AHEAD of the `t`/`nil`
  constant edges), and the emitted `%typep-runtime` (the specifier) / `%subtypep-runtime`
  (BOTH arguments) carry `LispMacroExpander.metaobjectNameNormalization`, the
  `(if (%obj-is v '<standard-class descendants>) (setq v (%obj-ref v 0)))` preamble.
  A metaobject is always a RUNTIME value, so the literal fold is untouched.
- `class` is a SEEDED slot-less class (`ClosRegistry.CLASS_NAME`), superclass of
  `standard-class` and hence of every user metaclass, so `(typep x 'class)` is the
  metaobject predicate through the ordinary ancestor machinery. Never instantiated.
- **Two traps from LAZY seeding**: (a) the preamble's tag list is
  `descendantTags(STANDARD-CLASS)`, and an EMPTY one means "no metaobject can exist" on the
  compile paths (final registry) but NOT in the interpreter, where the `(find-class 'c)` in
  the test's own argument seeds AFTER the expansion -- hence the `liveRegistry` flag, which
  falls back to the constant `%class-STANDARD-CLASS` tag instead of dropping the preamble;
  (b) a type SPECIFIER naming a MOP base class is itself a seeding trigger
  (`ClosRegistry.ensureMopClassesSeededFor`, from the interpreter's `typep`/`subtypep`),
  or `(typep (find-class 'c) 'class)` as a program's first MOP form folds to constant nil.
- The built-in `T` class's name slot holds the boolean `t`, not the symbol, so the runtime
  `typep` universal-type arms match BOTH spellings (`universalTypeMatchTest`, plus the
  instance branch's `(member tn '(t atom))`); `subtypep`'s `(eq b t)` edge already did.
- `#'class-of`'s wrapper is REFERENCE_GATED in `BuiltinFunctionWrappers` -- ungated it
  referenced `%find-class` in programs the injection scan said needed no runtime.
  `class-name` is a prelude defun over metaobject slot 0.
- **Metaobject slot order is a `%obj-ref` index contract** shared with the closer-mop shim --
  append, never reorder: class = name, direct-superclasses, direct-slots, effective-slots,
  finalized-p; slot-definition = name, initargs, initform, type, readers, initfunction (5).
- **`allocate-instance`** is IN: an instance of a registered CLOS class (metaobject or name
  designator) with EVERY slot the unbound marker -- no initforms, no `initialize-instance`;
  initargs accepted and ignored. Interpreter: a registry-backed built-in. Compile paths:
  `%obj-new` needs a LITERAL tag, so `LispMacroExpander.allocateInstanceDefuns` (gated by
  `needsAllocateInstanceRuntime`; a user defun wins) emits one construction arm per
  registered class, chunked into `%ALLOC-INST-<n>` helpers by cons-node budget, plus the
  public `allocate-instance` defun over an `or`-chain. It does NOT seed the MOP classes.
  Built-in and struct classes signal.
- The `closer-common-lisp` package table.lisp `:use`s is a resolver-level flat re-export
  (`.kb/packages.md`).

### The metaclass protocol (Phase B)

A `defclass` carrying `(:metaclass M)` -- `M` must be registered and descend from
`standard-class` -- keeps its full static expansion (constructor, accessors, registry entry;
instances stay ordinary) and additionally emits ONE
`(%ensure-class-with-metaclass 'name 'M '(supers) (list slot-specs...) (list
class-initargs...))` driver call as its last generated form.

- Unknown CLASS options become metaclass initargs whose value is the option TAIL list
  (`(:table-name "u")` -> `:table-name ("u")`, AMOP canonicalization); unknown SLOT options
  are collected per slot (single occurrence each) and ride the canonical
  `(:name .. :initargs .. :initform .. :type .. :readers ..)` spec plist as
  `direct-slot-definition-class` initargs.
- The driver + system defaults for `closer-mop:{validate-superclass (permissive t),
  direct-slot-definition-class, effective-slot-definition-class,
  compute-effective-slot-definition, finalize-inheritance}` are Lisp source:
  `macro/mop-protocol.lisp` via `MopProtocol.forms()` (the `FormatRenderer` pattern),
  SELF-CONTAINED over the `%obj-ref` index contract -- no closer-mop shim dependency,
  defMETHODs only (no defgenerics) so user hook methods defined before OR after merge into
  the same generics.
- The dynamic-extent contract postmodern relies on holds: the default
  `compute-effective-slot-definition` calls `effective-slot-definition-class` and
  instantiates its answer INSIDE the user override's `call-next-method`.
- `finalize-inheritance` runs EAGERLY at definition time (documented divergence).
- The protocol runs "in the evaluator at hand": the interpreter's `evalDefclass` loads it
  once (`ensureMopProtocolLoaded`), which also covers the compile paths' macro-time
  evaluator; the compiled program runs the driver call at program start in top-level order.
- Compile-path gate (`usesMetaclassProtocol` / `namesMopBaseSuperclass`): PREPENDS
  `MopProtocol.forms()` (before the reference scans, so the driver's own `find-class` use
  switches the metaobject runtime on) and appends `seededMopConstructorDefuns` (keyword
  constructors for the three seeded MOP base classes, whose defclass never ran), the
  generated `%mop-make-instance` (designator -> name -> per-class `apply` of the constructor
  + the initialization generic; a METAOBJECT-ancestored class's arm allocates UNBOUND for
  the chain fill instead; arms bounded to METAOBJECT-ancestored classes, WIDENED to every
  program-registered class -- seeded condition classes excluded, no keyword constructor --
  and chunked into `%MMI-<n>` helpers with the init call hoisted, whenever the program takes
  `#'make-instance` as a value) and `%register-class-metaobject` (prepends onto
  `%class-metaobjects%` so the driver-built instance shadows the materialized plain view --
  the memo scan takes the first hit; the interpreter twin primes
  `ClosRegistry.classMetaobjects` via `registerClassMetaobject`).
- **Lite divergence + the initarg RE-FILL repair.** For REGULAR (non-metaobject) classes,
  shared-initialize hooks run AFTER constructor slot-filling, which IS observable (upstream
  dao-class's `shared-initialize :before` RESETS its `direct-keys` slot and counts on CL's
  :before -> initarg-fill order). Repair: for a REGULAR class specialized by a `:before`
  method on initialize-instance/shared-initialize (`ClosRegistry.initRefillTargets`,
  ancestor-inclusive via `needsInitRefill`), make-instance re-sets every DECLARED-initarg
  slot the call supplies (`SlotSpec.initargSupplied`; the slot-name-default keyword an
  `:initarg`-less slot gets is deliberately NOT refilled) after the initialization generic
  returns, leftmost initarg wins. Three emission sites, one semantic: `expandMakeInstance`
  folds it statically per literal call site (`%obj-set` with the baked index; the
  interpreter's `%mop-make-instance`/`#'make-instance` builtin re-enters this expansion with
  quoted args, so `literalKeyword` unwraps both spellings), and the generated
  `%mop-make-instance` runtime carries `%MMI-REFILL` (one cond bounded by the
  :before-specialized class set) + `%MMI-INIT-TAIL` (plist scan), called inside the
  per-class arm right after the initialization generic.
  **Residual (accepted)**: a specialized PRIMARY without call-next-method should suppress
  the fill entirely, and an `:after` writing a supplied declared-initarg slot on a
  refill-target class would be re-clobbered -- both need the fill INSIDE the generic chain,
  which the static constructor model cannot do. Pinned by
  `defclassMetaclassSharedInitializeBeforeRunsBeforeInitargFilling` (all three suites) and
  the PostmodernE2eTest DAO leg.
- Other lite divergences: inherited effective slots are reused from the superclass
  metaobject unless shadowed (the direct-definition list handed to
  compute-effective-slot-definition is the shadowing definition alone); validate-superclass's
  default is permissive.

### MOP widening (mito shapes)

- **`ensure-class-using-class` routing.** `%ensure-class-with-metaclass` applies
  `closer-mop:ensure-class-using-class` with the EXISTING driver-built metaobject (nil on
  first definition), the name, `:metaclass`/`:direct-superclasses` (NAMES -- resolved inside
  the chain, after user `:around`s may munge the list)/`:direct-slots` + the class initargs.
  The system default takes the make path for nil and the reinitialize path for a class, so a
  user `:around` on a metaclass fires on REdefinition, per AMOP. "Existing" is tracked in the
  protocol's own `%mop-ensured-classes%` alist, deliberately NOT via find-class: the static
  class table answers a materialized plain view for a class whose driver call has not run.
- **Chain-fill initialization of METAOBJECT instances.** For a class whose ancestors include
  a seeded MOP base class, and only when `ClosRegistry.isMopProtocolActive`,
  `expandMakeInstance` and the generated `%mop-make-instance` arms allocate the instance
  UNBOUND instead of calling the keyword constructor, then run the initialization generic.
  The system `shared-initialize` primaries (on `standard-class` and the two slot-definition
  base classes) fill via `%mop-fill-slots` (interpreter: registry-backed builtin; compile
  paths: generated per-class dispatch chunked into `%MOP-FILL-<n>` helpers; slot-names nil =
  supplied initargs only, non-nil = plus initforms for still-unbound slots --
  unsupplied-no-initform stays UNBOUND). This is what makes a user
  `initialize-instance :around`'s MUNGED initargs take effect; CL's ordering holds natively
  here, so metaobject classes are EXCLUDED from the initarg re-fill replay. The
  standard-class primary additionally -- INSIDE the chain -- resolves `:direct-superclasses`
  designators into metaobjects on slot 1 and converts `:direct-slots` canonicalized spec
  plists into direct-slot-definition metaobjects on slot 2 through
  `direct-slot-definition-class` + `%mop-make-instance` (recursing into the same chain).
- **Class REdefinition (metaclass classes).** Re-evaluating a `defclass ... (:metaclass M)`
  reinitializes the SAME metaobject (identity survives), re-registers it in the find-class
  memo and re-finalizes. **Divergence**: the INTERPRETER does real redefinition; the COMPILE
  paths keep the LAST definition in the static tables (`expandTopLevelDefinitions` nil-s the
  earlier defclass-generated constructor defun via `classDefunSlots`; two same-name defuns in
  one class file are a JVM ClassFormatError) while BOTH driver calls still run in top-level
  order. Existing instances are not updated (`update-instance-for-redefined-class` is out),
  and a slot whose INDEX changes between definitions poisons the shared `slotPositions` map.
- **Slot-definition contract: index 5 = INITFUNCTION** (append-only). The driver builds each
  canonicalized spec with `list` -- fresh cells per evaluation, because mito's
  add-referencing-slots `rplacd`s ghost markers into them -- carrying
  `:initfunction (lambda () initform)`; the default `compute-effective-slot-definition`
  copies it onto the effective slot. Filled only on DRIVER-built definitions: the
  materialized plain views (`classMetaobject`, `%find-class-materialize`) answer nil (the
  meta table is quoted data and cannot carry a live thunk). Shim additions (closer-mop.lisp):
  `slot-definition-readers` (4), `slot-definition-initfunction` (5), `class-direct-slots` (2),
  `class-direct-subclasses`.
- **`class-direct-subclasses`** rides `%class-direct-subclasses`: interpreter =
  `ClosRegistry.directSubclassNames` -- the metaobject memo's direct-superclass lists FIRST
  (a driver-built instance may carry a superclass a user `:around` INJECTED), then the static
  registry's declared superclasses; compile paths = a generated defun (gated on the
  reference; forces the metaobject runtime, whose `%class-metaobjects%` memo it scans) plus
  chunked static-arm helpers (`%CDS-<n>`), names materialized through `%find-class`.

Tests: `defclassMetaclassEnsureClassUsingClassAndInitargMunging` (`LispEvaluatorTest`,
`compile`-prefixed in `JvmLispCompilerTest`, same name in
`WasmLispCompilerIntegrationTest` -- all three share `MopWideningFixture.MITO_SHAPE_SOURCE`),
ci-spec `mop-widening-for-mito`.

### The mito-core integration batch

- **Metaobject slots are read/written BY NAME, never by `%obj-ref` index.** The seeded index
  contract holds only for the seeded base classes themselves: a user slot-definition class
  may inherit the base through MULTIPLE inheritance with its own mixin FIRST (mito's
  `table-column-class`), putting the mixin's slots ahead of the base's.
  `mop-protocol.lisp` and the closer-mop shim spell every access `(slot-value x 'name)` /
  `(setf (slot-value x 'name) v)`; only the slot NAMES are a contract. The `%obj-ref` index
  contract remains valid for Java/generated code that CONSTRUCTS instances of the seeded
  layouts (`%find-class-materialize`, `newSeededInstance`).
- **An AMBIGUOUS literal slot name outlines onto the shared runtime dispatch.**
  `expandAmbiguousSlotRead`/`expandAmbiguousSlotSet` emit `(%slot-value-runtime obj 'name)`
  / `(%slot-value-set-runtime obj 'name v)` instead of a per-site registry-proportional tag
  dispatch (six such reads in `finalize-inheritance` crossed the JVM 64 KB method limit at
  ~250 layouts). `needsRuntimeSlotNameDispatch`/`needsRuntimeSlotSetDispatch` take the
  registry and gate on ambiguous literal names too. The INTERPRETER defines both
  `%slot-value(-set)-runtime` as registry-backed builtins.
- **Injected direct superclasses reconcile the STATIC registration.** A user
  `initialize-instance :around` munging `:direct-superclasses` at ensure-class time is
  invisible to the static layout/ancestors/dispatch/constructor. The driver-built metaobject
  is the truth: `evalDefclass` (interpreter) re-registers the class from
  `LispMacroExpander.widenDefclassToMetaobjectSupers` (the metaobject's superclass list minus
  the `standard-object` default, plus per-slot `:initarg`s a slot-definition `:around` pushed
  -- ONLY onto specs that declare none) without re-running the driver;
  `UserMacroExpander.widenMetaclassDefclasses` (compile paths) rewrites the EMITTED defclass
  form the same way after the macro-time evaluator ran it. The runtime driver re-runs the
  `:around`, whose contains-checks make the injection idempotent.
- **A driver-built class with no direct superclasses defaults to `(standard-object)`**, per
  AMOP -- the walk shape mito's `map-all-superclasses` relies on. `standard-object` resolves
  through find-class ONLY (`ClosRegistry.FIND_CLASS_ONLY_CLASS_NAMES` + the generated
  `%find-class` fallback list): `class-of` never answers it and the typep/subtypep
  special-casing keeps winning. `STANDARD-OBJECT`/`STANDARD-CLASS` are in
  `PackageRegistry.CL_TYPES` -- the compiled `%find-class` matches SPELLINGS, so a
  package-local `MITO...::STANDARD-CLASS` resolution otherwise missed the seeded entry.
- **The synthesized `shared-initialize` system default FILLS** (it used to return the
  instance unchanged): mito's `make-dao-instance` is `allocate-instance` +
  `(shared-initialize obj nil initargs)`, where the default IS the fill. It calls
  `%mop-fill-slots` (slot-names nil = supplied declared initargs only), whose generated
  dispatch covers EVERY registered class and is injected whenever the expanded output
  references it. The interpreter builtin answers an UNREGISTERED instance untouched instead
  of throwing, matching the generated dispatch's fall-through.
- **`slot-exists-p`** (all four backends): true when the instance's layout declares the slot,
  regardless of boundness. Interpreter = `instanceSlotRef != null`; compile paths = a
  `%obj-is` membership test over the declaring layouts for a literal name, the shared
  `%slot-exists-p-runtime` dispatch (own gate) for a runtime name.

Tests: `slotExistsPAnswersDeclaredSlotsRegardlessOfBoundness` (`LispEvaluatorTest` +
JVM/WASM twins), ci-spec `mito-core-enablement-language-group`. The end-to-end
`(ql:quickload '("mito-core" "dbd-postgres"))` + DAO CRUD run is manual on the interpreter,
the JVM and the WASM component.

### Definition-time method construction (Phase C)

The `(funcall (compile nil `(lambda () ,code)))` idiom of postmodern's `build-dao-methods`
(`%eval`), where `code` is a `let*`/`labels` form whose nested `defmethod`s carry the class
METAOBJECT spliced as a literal specializer (plus `(eql (class-name ,class))`).

- The evaluator's `compile` built-in (`LispEvaluator`; CL semantics otherwise: coerce a
  literal lambda to a function in the null lexical env, a non-nil name installs and returns
  the name) intercepts a NO-ARGUMENT definition containing a defmethod and folds the
  metaobject literals first (`macro/MopEvalCapture`): specializer position -> the class name,
  `(eql (class-name <inst>))` -> `(eql 'name)`, every other occurrence ->
  `(find-class 'name)`. **Load-bearing ordering**: valid during finalization only because the
  driver registers the metaobject BEFORE `finalize-inheritance` (mop-protocol.lisp, like CL's
  ensure-class).
- The live interpreter returns a function evaluating the folded body in place. The compile
  paths' MACRO-TIME evaluator records the body into the splice sink `UserMacroExpander`
  attaches (`setMopEvalSpliceSink`), and the pass -- also activated by a bare `:metaclass`
  defclass -- splices the folded forms right after the triggering defclass, where
  `expandLetNestedDefmethods` (defmethods at ANY depth, quote-skipped, byte-identical for the
  shallow case) registers them statically and the nested method-body defuns compile to
  global-closure setqs.
- Run-time re-execution in a compiled program goes through the generated `compile` runtime
  (`macro/CompileRuntime` + `compile-runtime.lisp`, injected gated on a compile reference
  without a user defun, registered in the native-image resource-config): a
  defmethod-containing definition answers a do-nothing function, anything else signals -- so
  a method-defining form built from RUNTIME data is silently absorbed rather than signalled,
  the one soft edge. A method under a false definition-time guard (`when key-fields`) still
  registers in the dispatcher; calling it fails on the unassigned body global instead of
  no-applicable-method.

MOP tests: `LispEvaluatorTest`/`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`
`*FindClass*` + `*CloserMopShim*` +
`classOf*`/`compileAndRunClassOf`/`classOfAndSlotAccessors` +
`allocateInstance*`/`compileAllocateInstance*` +
`typepAndSubtypepAcceptClassMetaobjectsAsTypeSpecifiers` (`compile`-prefixed on the JVM) +
`defclassMetaclass*`/`compileDefclassMetaclass*`/`defclassMetaclassRunsTheClassDefinitionProtocol`
(WASM), `compileCoercesALambdaExpressionToAFunction` +
`compileInterceptsDefinitionTimeMethodConstruction` (all three suites), ci-spec
`find-class-metaobject-substrate` (raw metaobject print shape included),
`defclass-metaclass-protocol`, `compile-definition-time-method-construction`.

### Registry-growth lesson

The RUNTIME-slot-name `slot-value`/`slot-boundp` dispatch used to be inlined per call site
and grows with every layout times its slots; five extra ci-spec classes pushed a corpus
dolist body past the JVM's SIGNED 16-BIT branch encoding (32 KB, hit before the 64 KB method
cap). It is now outlined into the shared
`%slot-value-runtime`/`%slot-boundp-runtime`/`%slot-value-set-runtime` defuns
(`runtimeSlotValueDefuns` etc., gated on a non-literal-name site,
`needsRuntimeSlotNameDispatch` / `needsRuntimeSlotSetDispatch`; the interpreter resolves
runtime names natively and never calls the read pair, serving the set twin as a builtin).
The defuns are CHAINED-CHUNKED by cons-node budget (`chainedDispatchDefuns`: overflow arms
call `%SVR-<n>`/`%SBR-<n>`/`%SVW-<n>` helpers; a dispatch that fits stays one defun,
byte-identical to the pre-chunking shape). Top-level compile crashes name the offending form
(`JvmLispCompiler` chunk-loop wrapper).

## Out of scope / known gaps

- Qualifier combination is for class + default methods; eql/type-specialized qualified
  methods combine only with same-specializer primaries + the default method.
- Still OUT of the MOP (classes are compile-time-static; `--optimize` DCE and the dispatch
  tables depend on it): runtime class construction (`ensure-class` from computed data, a
  non-top-level `defclass`), `add-method`, `compute-applicable-methods`,
  `update-instance-for-*`. `remove-method` EXISTS as a `cl` name and SIGNALS when called: a
  method here is a registry row plus a generated defun, never a first-class object, and
  without `find-method` no caller can name the method it means. Re-evaluate with
  `add-method` if method metaobjects land. Class REdefinition of a statically-known name is
  IN; redefinition from computed data is out.
- **Known static-model seam**: on the compile paths `find-class`/`class-of` see the WHOLE
  program's classes regardless of form order, while the interpreter only knows classes
  already defined at call time.
- `change-class` is the ONE runtime exception and is not MOP (both classes are literal, so
  the whole change is a static expansion).
- eql specializers on strings.
- The `:type` slot option is RECORDED (`SlotSpec.type`, plain name, `"t"` when omitted;
  still a checking no-op).
- Compiled runtime `eval`: generated functions are callable; defining classes/methods or
  using `make-instance`/`slot-value` inside `eval` is not
  (doc/en/guides/eval-limitations.md).
- `--no-gc` rejects via its generic top-level error, like defstruct.
- `defclass`/`defgeneric`/`defmethod` are in `PackageRegistry.CL_SPECIAL_FORMS`;
  `make-instance`/`slot-value`/`with-slots`/`with-accessors`/`change-class` in `CL_MACROS`
  -- pinned in ci-spec (`rontolisp-package-introspection`), the three backend tests and the
  doc pages; update all together.

## Core pinning tests

`LispEvaluatorTest#defgeneric*`/`defclass*`/`defmethod*`/`closInUserPackage`,
`JvmLispCompilerTest#compileAndRunDefgeneric*`/`compileAndRunDefclass*`/
`compileAndRunMacroCallingGenericAtExpansionTime`/`compileNestedDefmethodFails`,
`WasmLispCompilerIntegrationTest#compileAndRunDefgeneric*`/`compileAndRunDefclass*`,
`UserMacroExpanderTest#defmethodLambdaListStaysVerbatim*`/`defclassKeepsNamesAndOptions*`/
`macroBodyMayCallAGenericFunctionAtExpansionTime`,
`LispMacroExpanderTest.theDispatcherLastResortIsOneCallOfTheSharedNoApplicableMethodSignal`,
`WasmLispCompilerTest.aSlotAccessorDispatcherDoesNotCarryItsOwnCopyOfTheNoApplicableMethodTail`,
`JvmLispCompilerTest.compileAndRunApplyAlignedVariadicTarget` /
`WasmLispCompilerIntegrationTest.applyAlignedVariadicTarget`,
`LispEvaluatorTest.evalPackageIsADefmethodSpecializer` /
`...SpecializerOutranksKeywordAndSymbol` + `compileAndRun` twins,
`LispEvaluatorTest.defmethodEqlSpecializerNamingAConstantDispatchesOnItsValue` /
`...NamingNoConstantStaysTheSymbol` +
`compileAndRunDefmethodEqlSpecializerNamingAConstant` twins.

ci-spec: `clos-defgeneric-defmethod-eql-dispatch`,
`clos-defclass-slots-inheritance-and-dispatch`,
`clos-method-qualifiers-and-call-next-method`, `package-defmethod-specializer`,
`clos-defmethod-eql-specializer-over-a-constant`. Plus the five `doc/*/reference/**` pages
via `DocExamplesTest`. Stage 3:
`LispEvaluatorTest#{defmethodBeforeAndAfterQualifiersRunAroundThePrimary,
callNextMethodChainsPrimariesAndNextMethodP,aroundMethodWrapsAndCallNextMethodInvokesTheCore,
callNextMethodWithNewArguments,callNextMethodWithNoNextMethodSignals}` and the
`compileAndRun{MethodQualifiersAndCallNextMethod,AroundMethodAndNextMethodP}` JVM/WASM tests.
