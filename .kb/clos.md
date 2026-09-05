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
  defmethods.

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
  is the symbol, and a bare symbol naming no constant stands for itself.

### The two dispatch bodies
- `simpleDispatchBody` (one call per branch) when the generic has NO qualifier and NO
  `call-next-method` usage; otherwise `combinedDispatchBody` = one branch per distinct
  specializer (`specKeyOf`, qualifier-independent) plus the default fallback, each branch's
  value `effectiveMethod(branchRep, ...)`.
- `applicableMethods` filters by `appliesToBranch`: default methods always apply; a class
  method applies to a class branch whose class has it as an ancestor; a STRUCT method applies
  to a branch struct that `:include`-descends from it (`descendantStructTags`); eql and
  built-in type methods apply only to their exact-same branch.
- **Plus one branch per position-wise MEET of two INCOMPARABLE specializer vectors**
  (`addMeetBranches`/`specializerMeet`) — one branch per method suffices only while the
  vectors form a chain, because `appliesToBranch` is a SUBSET test. Quadratic but run to
  fixpoint only over compatible AND incomparable pairs, so a generic with none is
  byte-identical. **Trigger**: the JVM 64 KB method ceiling
  (`.kb/jvm-method-size-limits.md`) if a library ever has many incomparable methods.
- Composition: `:around` (most specific first) wrap a `coreThunk`; the core runs `:before`
  (msf, for effect), the primary chain (msf, value kept via a `%clos-result` let), then
  `:after` (LEAST specific first). `buildNextChain` builds nested
  `(lambda (params) (%m next params))` literals — NO free-variable capture; base next = `nil`
  for the innermost primary, the `coreThunk` for the innermost around.

## Multiple inheritance
`ClassInfo` carries `superclasses` (local precedence order), `cpl` (CLHS 4.3.5 topological
sort in `computeCpl`; inconsistent local orders are an `IllegalArgumentException`) and
`directSlots`, beside the effective `slots` and the ancestor SET.
- **Layout rule**: the FIRST superclass's effective slots keep their indexes, each later
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
  `registerExtraAncestors`); `change-class`'s initform fill SKIPS a source whose layout is not
  a base-name prefix of the target; `%class-meta-table%`'s superclass column is a LIST;
  mop-protocol.lisp's `finalize-inheritance` merges inherited effective slots across ALL
  direct supers (first name wins).

## Where each path hooks in
- **Interpreter**: `evalDefclass` (expands + evals defuns, then REGENERATES every dispatcher
  with a class-specialized method), `evalDefstruct` (likewise, `GenericInfo.hasStructMethod`),
  `evalDefgeneric`, `evalDefmethod`. A `#'generic` captured BEFORE a later defmethod is NOT
  stale: `evalFunction` answers a LATE-BOUND wrapper for any name `findGeneric` knows.
- **Compilers**: `expandTopLevelDefinitions(program, structAccessors, closRegistry)` sits at
  the old `expandTopLevelDefstructs` slot (after `flattenTopLevel`, before
  `LambdaLists.desugarProgram` — the constructors use `&key`). It walks the whole program
  collecting classes/methods, splices defuns in place, and inserts each generic's dispatcher
  at its defgeneric's position (or the first defmethod's) AFTER the walk, so descendant and
  method sets are complete regardless of definition order. Non-top-level
  defclass/defgeneric/defmethod -> "only supported as a top-level form".
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
  Only the ALIASING shape is accepted; an unknown target throws `there is no class named`; a
  NON-top-level alias is an error (`markClassMetaTableEmitted` makes `registerClassAlias`
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
- **Compile paths** (`compiler/ShadowedBuiltins`, run by BOTH backends right after
  `expandTopLevelDefinitions`): `(close X)` is compiler-lowered whatever defuns exist, so the
  spliced dispatcher defun is dead. Per name in the COMPUTED set: replace the dead dispatcher
  (found by structural equality against a regenerated 2-arg dispatcher, so a user defun of the
  same name is never mistaken for it) with the interpreter's body renamed `%<name>--dispatch`;
  bind `%<name>--builtin` to a FORWARDER defun of the original built-in call (`&rest` tail
  dropped); rewrite call sites and `#'name` references onto the dispatcher. The walker skips
  quoted data, `defmacro`/`macrolet` bodies, the generated defuns themselves (the Gray
  `DISPATCH_DEFUNS` rule — rewriting the forwarder's fallback would recurse) and the
  non-evaluated positions of `let`/`lambda`/`flet`/`do`/`dolist`/`case`/`handler-case`. When
  `close` is shadowed, `with-open-file`/`with-open-stream`/`with-*-to-string` are pre-expanded
  (`unwindProtect=true`) — side effect: such a WASM module is always in EH mode.
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
  a wasm CAST-FAILURE trap); still open, the ASYNC read promotions bypass a user method;
  (4) a runtime designator (`(funcall 'close x)`) does not dispatch on the compile paths.

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
  `&allow-other-keys` to every `&key` method lambda list instead of taking CL's union.
- **A defclass constructor tolerates extra initargs** (`&allow-other-keys` on `%make-<name>`).
- **A bare `(call-next-method)` forwards the WHOLE original argument list**:
  `rewriteNextMethod` emits `apply` over the method's `&rest` variable, injecting one
  (`%method-args`) when the method declares a `&key` tail without a `&rest`. **`&optional`
  needs more than the rest variable** — `&rest` binds what is left AFTER the optionals, so
  forwarding it alone silently drops them; a method that chains has every `&optional` entry
  normalized to `(var init supplied-p)` and the bare call becomes
  `(apply %next-method req... (append (if sp1 (list o1) nil) ... %method-args))`, CL's rule
  that an UNSUPPLIED optional is not passed on. Gated on the body mentioning
  `call-next-method`/`next-method-p`; pinned against SBCL over six tail shapes.

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
  registered-class count, and at cl-postgres scale (165 layouts, ~68 KB of AST per site) three
  sites overflowed the JVM's signed-16-bit branch offsets (`StackMapAugmenter: Index -31123
  out of bounds`). `expandTypep(cons, registry, false)` emits `(%typep-runtime value spec)` and
  `expandTopLevelDefinitions` injects the defun plus the `%typep-tag-table%` data table —
  quoted data mapping each type name's spellings to the tags it accepts, emitted as CHUNKED
  top-level forms (a defvar plus `(setq .. (append 'chunk ..))` continuations, 48 entries
  each). Same for `%subtypep-runtime` (`%subtypep-ancestor-table%`; the inline shape had
  reached 59 KB) and for a computed `error` condition-type datum WITH initargs
  (`(%error-runtime datum (list args...))` over per-condition `%ERROR-RT-n` helpers — the old
  inline reached 90 KB, past the 64 KB hard limit).
- `expandClassSlotDefs` lowers `%class-slot-defs` to the SHARED `%class-slot-defs-runtime`
  defun with `%class-slot-defs-table%` — one entry per registered LAYOUT (classes AND structs,
  since `ClosRegistry.slotDefs` is the one resolver). Designators: instance tag, plain name,
  class metaobject. Answers `((slot-name declared-type) ...)`, a struct's types all `T` —
  which is what lets json.lisp's `%json-out-instance` treat a struct like a CLOS instance. The
  old per-site inline cond hit the JVM 65535-byte ceiling (70178 bytes). Chunked by cons-node
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
Object identity and every shared slot survive, slots the new class adds are filled from
initforms, supplied initargs stored, the instance is the value.
- `expandChangeClass`: capture the OLD tag -> `(%obj-become obj '%class-T)` -> a `cond` on the
  captured tag filling `[slotCount(source), slotCount(target))` from the target's initforms ->
  the initarg stores. **The tag is captured BEFORE the swap.**
- **`LispLayout.capacity()` is why this works on the JVM**, where an instance IS its
  `Object[]`: every ancestor of a `change-class` target reserves the target's slot count at
  construction. `expandTopLevelDefinitions` scans for targets (`registerChangeClassTargets` —
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
  reason**: an inline instance-of test is ~60 bytes of JVM bytecode and one per accessor ran
  the ci-spec corpus past the 64 KB method limit
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- In a GENERATED accessor body the `'NAME` rides in `%unspelled-quote` (`checkedSlotRead`), so
  it must not arm the funcall-dispatch gate's name probes
  ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)); a user-written
  `slot-value` keeps the plain quote. `expandSlotValue` is the CHECKED read,
  `expandSlotValueRaw` the unchecked one the `setf` place expansion (and a runtime name) needs.
- `unbound-slot` is seeded under `cell-error` (which gained CL's `name` slot) with an extra
  `instance` slot and a `:report` LAMBDA built in Java (`ClosRegistry.unboundSlotReport`, over
  `%obj-ref` indexes rather than `slot-value`). `type-error-datum`,
  `type-error-expected-type`, `cell-error-name`, `unbound-slot-instance` are prelude defuns.

## Slot shadowing, `:default-initargs`, `with-slots`/`with-accessors`, slot options
- **A subclass may re-declare an inherited slot** (CLHS 7.5.3): STORAGE stays the one
  inherited slot (descendants keep the baked index) while the subclass spec overrides
  initform/initarg and ADDS its readers/accessors (`shadowSlot`).
- **`(:default-initargs :arg form)` overrides the matching slot's effective initform**, which
  is where BOTH construction paths read it; before this it only reached the constructor, so
  `(error 'my-cond)` ignored it.
- **`with-accessors`** is the accessor-call twin of `with-slots` (`expandWithAccessors`),
  needing no registry. **`with-slots` resolves `defstruct` slots too**: `expandSlotValueRaw`
  picks the index out of the LAYOUT registry (`uniqueLayoutSlotIndex`), falling back to the
  tag dispatch when types disagree.
- **`with-slots`' entry-time fallback binding is BOUNDNESS-GUARDED**: besides substituting each
  slot variable textually, `expandWithSlots` `let`-binds it to
  `(if (slot-boundp obj 'slot) (slot-value obj 'slot) nil)` (`boundOrNil`) so code GENERATED
  inside the body still resolves. **Not cosmetic**: `with-slots` BINDS, never reads, so a body
  that only ASSIGNS an initform-less slot must not signal on entry (fast-io's
  `(with-slots (buffer) self (setf buffer ...))`); a read the body REALLY performs still
  signals. **`with-accessors`' fallback is NOT guarded** — an accessor is a generic with no
  boundness twin, and wrapping it would force EH mode on WASM.
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
  an unrecognized symbol specifier before the registry lookup.
  `structure-class`/`built-in-class` keep their deliberate empty tests.

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
  value in a STRUCTURE or class SLOT, a hash table, an array of rank != 1. **Cost**: O(n^2) in
  string concatenation, like `%print-cased`'s.
- `print-unreadable-object`'s `:type t` prints the type NAME with the `%struct-`/`%class-` tag
  prefix stripped INLINE (`typeNameOf`), not via the prelude's `type-of`: this expansion runs
  inside the compilers, after the prelude pre-pass. The separating space is written only when a
  body follows; `:identity` prints NO address. **That designator follows `*print-escape*`**
  (CLHS 22.1.3.3): `prin1` of a `quri:uri` gives `#<QURI:URI ...>`, `princ` `#<URI ...>`; the
  tag prefix attaches to the PACKAGE half, so only the unqualified spelling reaches the
  prefix-stripping cond. The reference is late (Pass 2), so `injectMvSpillGlobal` counts an
  un-expanded `print-unreadable-object` as the mention declaring `*print-escape*`.

## Short-form `:method-combination`
`(defgeneric g (x) (:method-combination NAME [:most-specific-first | :most-specific-last]))`,
NAME one of `ClosRegistry.SHORT_FORM_COMBINATIONS` —
`progn`/`and`/`or`/`+`/`list`/`nconc`/`append`/`max`/`min`. The CLHS **long** form
(`define-method-combination`) is out of scope; a NAME outside the set is REJECTED at
`defgeneric` time.
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
`standard-class` instance on all four backends, `eq` to each other for the same class.
- **Interpreter**: Java built-ins over `ClosRegistry.classMetaobject` (designator-aware: plain
  names AND instance tags; a struct class is a `standard-class` instance, `structure-class`
  does not exist) and `builtinClassMetaobject` (`BUILTIN_CLASS_NAMES` = exactly the
  `%class-designator` result set, `T` for everything else). Seeding is lazy
  (`ensureMopClassesSeeded()`) — **NEVER seed unconditionally**: that joins every runtime
  dispatch table and once pushed the ci-spec corpus over the JVM 64 KB method ceiling.
- **Compile paths**: `expandTopLevelDefinitions`, gated on the program referencing `find-class`
  OR `class-of`, injects the node-budget-chunked `%class-meta-table%` (one entry per registered
  class AND struct layout: spellings — the instance TAG among them — / superclass /
  effective-slot data) plus `%find-class` + `%find-class-materialize`. The public `find-class`
  defun is a thin wrapper injected only when the program references it without defining it.
  `(class-of x)` expands to `(%find-class <%class-designator dispatch> t)`.
- The OLD tag/type-name view lives on as the internal `%class-designator`, ridden by the light
  consumers (prelude `type-of`, `print-unreadable-object :type`, the no-applicable-method
  message, `%json-out-instance`) — they drag no metaobject runtime in.
- **`typep`/`subtypep` take a class METAOBJECT wherever a type specifier is expected.** ONE
  rule — "an instance tagged as a `standard-class` descendant continues as its slot-0 name" —
  with three emission sites: `subtypep` folds it in Java for the interpreter
  (`classMetaobjectDesignator`, AHEAD of the `t`/`nil` constant edges), and the emitted
  `%typep-runtime` (the specifier) / `%subtypep-runtime` (BOTH arguments) carry
  `metaobjectNameNormalization`. A metaobject is always a RUNTIME value, so the literal fold is
  untouched.
- `class` is a SEEDED slot-less class (`ClosRegistry.CLASS_NAME`), superclass of
  `standard-class`, so `(typep x 'class)` is the metaobject predicate through the ordinary
  ancestor machinery. Never instantiated.
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
  finalized-p; slot-definition = name, initargs, initform, type, readers, initfunction (5).
- **`allocate-instance`** is IN: an instance of a registered CLOS class with EVERY slot the
  unbound marker — no initforms, no `initialize-instance`; initargs accepted and ignored.
  Interpreter: a registry-backed built-in. Compile paths: `%obj-new` needs a LITERAL tag, so
  `allocateInstanceDefuns` (gated by `needsAllocateInstanceRuntime`; a user defun wins) emits
  one arm per registered class, chunked into `%ALLOC-INST-<n>`, plus the public defun over an
  `or`-chain. It does NOT seed the MOP classes; built-in and struct classes signal.
- `closer-common-lisp` is a resolver-level flat re-export (`.kb/packages.md`).

### The metaclass protocol (Phase B) and the MOP widening
A `defclass` carrying `(:metaclass M)` — `M` registered and descending from `standard-class` —
keeps its full static expansion and additionally emits ONE
`(%ensure-class-with-metaclass 'name 'M '(supers) (list slot-specs...) (list class-initargs...))`
driver call as its last generated form. Unknown CLASS options become metaclass initargs whose
value is the option TAIL list (AMOP canonicalization); unknown SLOT options ride the canonical
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
- **Slot-definition contract: index 5 = INITFUNCTION** (append-only). The driver builds each
  canonicalized spec with `list` — fresh cells per evaluation, because mito's
  add-referencing-slots `rplacd`s ghost markers into them — carrying
  `:initfunction (lambda () initform)`; the materialized plain views answer nil (the meta table
  is quoted data and cannot carry a live thunk). closer-mop.lisp adds
  `slot-definition-readers` (4), `slot-definition-initfunction` (5), `class-direct-slots` (2),
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
  slot-definition `:around` pushed, ONLY onto specs that declare none) and
  `UserMacroExpander.widenMetaclassDefclasses` rewrites the EMITTED defclass form the same way
  on the compile paths. The runtime driver re-runs the `:around`, whose contains-checks make the
  injection idempotent.
- **A driver-built class with no direct superclasses defaults to `(standard-object)`**, per AMOP;
  `standard-object` resolves through find-class ONLY (`FIND_CLASS_ONLY_CLASS_NAMES`).
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
  else signals — so a method-defining form built from RUNTIME data is silently absorbed, the one
  soft edge. A method under a false definition-time guard still registers in the dispatcher;
  calling it fails on the unassigned body global.

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
- Qualifier combination is for class + default methods; eql/type-specialized qualified methods
  combine only with same-specializer primaries + the default method.
- Still OUT of the MOP (classes are compile-time-static; `--optimize` DCE and the dispatch
  tables depend on it): runtime class construction (`ensure-class` from computed data, a
  non-top-level `defclass`), `add-method`, `compute-applicable-methods`,
  `update-instance-for-*`. `remove-method` EXISTS as a `cl` name and SIGNALS when called: a
  method here is a registry row plus a generated defun, never a first-class object, and without
  `find-method` no caller can name the method it means. Class REdefinition of a
  statically-known name is IN; redefinition from computed data is out.
- **Known static-model seam**: on the compile paths `find-class`/`class-of` see the WHOLE
  program's classes regardless of form order, while the interpreter only knows classes already
  defined at call time. `change-class` is the ONE runtime exception and is not MOP.
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
`initProtocolGenericsAreSharedAcrossPackages`.

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
`slotExistsPAnswersDeclaredSlotsRegardlessOfBoundness`. Plus `ShadowedBuiltinsTest`,
`FastIoCircularStreamsE2eTest`, `JzonE2eTest`, the PostmodernE2eTest DAO leg.

ci-spec: `clos-defgeneric-defmethod-eql-dispatch`,
`clos-defclass-slots-inheritance-and-dispatch`, `clos-method-qualifiers-and-call-next-method`,
`clos-multiple-inheritance-cpl-slots-and-dispatch`, `clos-setf-methods-and-setf-generic`,
`clos-computed-change-class-442`, `clos-reinitialize-442`,
`clos-slot-options-and-metaobject-types-442`, `clos-defmethod-eql-specializer-over-a-constant`,
`package-defmethod-specializer`, `defmethod-on-a-builtin-name-keeps-the-builtin`,
`defgeneric-short-form-method-combination`, `find-class-metaobject-substrate`,
`defclass-metaclass-protocol`, `mop-widening-for-mito`,
`compile-definition-time-method-construction`, `runtime-type-dispatch-and-symbol-designators`,
`runtime-type-dispatch-residue`, `runtime-class-designator-spellings`,
`with-slots-write-only-unbound-slot-and-missing-slot`,
`array-operations-enablement-language-group`, `mito-core-enablement-language-group`. Plus the
five `doc/*/reference/**` pages via `DocExamplesTest`.
