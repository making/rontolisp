# CLOS static subset — defclass / defgeneric / defmethod / make-instance / slot-value

User-facing behavior: `doc/en/reference/special-forms/{defclass,defgeneric,defmethod}.md`,
`doc/en/reference/macros/{make-instance,slot-value}.md`, and the missing-features
guide (what is out of scope: multiple inheritance, MOP/runtime class ops
permanently). Stages 1+2+3 DONE (2026-07-06): dispatch + standard method
combination (`:before`/`:after`/`:around` + `call-next-method`/`next-method-p`).

## Design: the defstruct pattern, one shared registry, one shared dispatcher generator

Everything expands to plain defuns via `LispMacroExpander` (no backend codegen):

- `expandDefclass(cons, closRegistry, structAccessors)` — registers a
  `ClosRegistry.ClassInfo` and generates the internal keyword constructor
  `%make-<name>` (`&key ((:initarg slot) initform)...` over the FULL slot list)
  plus one defun per `:reader`/`:accessor`; `:accessor` names also go into the
  existing `structAccessors` map, so setf/incf/push places work with zero new
  setf code. An instance is `(%class-<name> v1 v2 ...)`; layout = superclass
  slots (inheritance order) + own slots, so single inheritance keeps positions
  stable in all descendants.
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
  nested-if chain testing arg 1, most specific first (`specializerRank`: eql 0,
  classes 10..99 by descending ancestor-set size = subclass first, built-in
  types 200s with subtypes like `null`/`keyword`/`integer` before
  `symbol`/`number`/`list`, default 1000; stable sort keeps definition order
  within a rank). Falls back to the default method or
  `(error "No applicable method: <name>")`. eql/built-in tests reuse
  `makeTypeTest`-family helpers (`makeEqlSpecializerTest`: symbols/keywords
  compare with `equal` — content-safe on WASM — numbers/characters with `eql`);
  a class test is `(if (consp x) (or (equal (car x) '%class-C) ...) nil)` over
  the statically-known descendant tags. The dispatcher is an ordinary defun, so
  `#'name`/`funcall`/mapcar work with no `BuiltinFunctionWrappers` entry.
  - Two bodies: `simpleDispatchBody` (unchanged single-call-per-branch) is used
    when the generic has NO qualifier and NO `call-next-method` usage; otherwise
    `combinedDispatchBody` emits standard method combination. Combined = one
    branch per distinct specializer (`specKeyOf`, qualifier-independent) plus the
    default fallback; each branch's value is `effectiveMethod(branchRep, ...)`.
  - `effectiveMethod` collects the applicable methods per role (`applicableMethods`
    filters by `appliesToBranch`: default methods always apply; a class method
    applies to a class branch whose class has it as an ancestor; eql/type methods
    apply only to their exact-same branch — cross-type subtyping is out of scope),
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

## Where each path hooks in

- **Interpreter**: `evalDefclass` (expands + evals the defuns, then REGENERATES
  every dispatcher that has a class-specialized method — the new class may
  extend a descendant set), `evalDefgeneric` (register + eval dispatcher),
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
- **`make-instance`/`slot-value`** are macro-classified (`CL_MACROS`, no
  function value) and expand at the three dispatch sites (`evalCons` + both
  ExprCompilers) through the registry; both require literal quoted names.
  `expandSetf` gained a third parameter (`closRegistry`) with a SLOT_VALUE
  place case; the three setf dispatch sites pass it.
- **`UserMacroExpander`** (cl-who-critical): top-level
  defclass/defgeneric/defmethod are `macroEval.eval`'d into the macro-time
  evaluator (so a defmacro body can CALL a generic at expansion time — cl-who's
  `process-tag` -> `convert-tag-to-string-list` chain) AND kept in the program
  for the compilers. Walker cases keep defmethod lambda lists (specializers)
  and defclass names/options verbatim; only defmethod bodies and defclass
  `:initform` values are walked.

## Name resolution gotcha

`defclass`/specializer class names are package-resolved (canonical, e.g.
`zoo::dog`) but the quoted name in `(make-instance 'dog)` is NOT.
`ClosRegistry.findClass` therefore falls back from the exact normalized key to a
UNIQUE base-name match across packages; two packages defining the same class
name make the bare spelling unresolvable (qualify it). `slot-value` matches by
slot base name for the same reason. `defmethod` stores the specializer as the
FOUND class's canonical name (not the spelling at the method site).

## Out of scope / known gaps

- Qualifiers (`:before`/`:after`/`:around`) + `call-next-method`/`next-method-p`:
  DONE (Stage 3, 2026-07-06). Combination is for class + default methods;
  eql/type-specialized qualified methods combine only with same-specializer
  primaries + the default method (cross-type subtyping among specializers is not
  computed).
- MOP / runtime class ops (`find-class`, `change-class`, `add-method`,
  `compute-applicable-methods`, class redefinition, `update-instance-for-*`):
  permanently out (contradicts the static compile model + `--optimize`).
- Multiple inheritance, `:allocation`/`:writer` slot options, eql specializers
  on strings. `slot-boundp`/`slot-makunbound` exist as LITE interpreter
  built-ins (slots are always initialized -- nil default, no unbound state).
  The `:type` slot option is RECORDED since 2026-07-18 (`SlotSpec.type`, plain
  name, `"t"` when omitted; still a checking no-op) so introspection can
  report it.
- Compiled runtime `eval`: generated functions are callable; defining
  classes/methods or using `make-instance`/`slot-value` inside `eval` is not
  (doc/en/guides/eval-limitations.md).
- `--no-gc` rejects via its generic top-level error, like defstruct.
- `defclass`/`defgeneric`/`defmethod` are in `PackageRegistry.CL_SPECIAL_FORMS`,
  `make-instance`/`slot-value` in `CL_MACROS` — pinned in ci-spec
  (`rontolisp-package-introspection`), the three backend tests, and the doc
  pages; update all together if those sets change again.

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
