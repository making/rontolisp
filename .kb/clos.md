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

## The instance-initialization protocol (todo-173)

`initialize-instance`, `reinitialize-instance` and `shared-initialize` have no
system-supplied primary method in the static subset (the generated constructor
already fills the slots), so `expandDefmethod` SYNTHESIZES one the first time a
program defines any method on one of them — and the CL chain with it:
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
- `(typep x COMPUTED-SPEC)` no longer errors: `expandRuntimeTypep` emits a
  `cond` keyed on the specifier symbol, one arm per registered layout (matched by
  canonical name AND plain member name) plus one per built-in atomic type name
  (`RUNTIME_TYPEP_BUILTINS`), each arm carrying the very test the literal
  specifier would have compiled to. An unrecognized specifier — a COMPOUND one
  included — yields nil rather than signalling: this is the lite runtime-dispatch
  model, not a real type table.

## Runtime slot names + class introspection on the compiled backends (todo-146)

jzon's `coerced-fields` walk (`(slot-value obj (c2mop:slot-definition-name s))`
over `(c2mop:class-slots (class-of obj))`) forced compile-path support for a
RUNTIME (non-literal) slot name and for `%class-slot-defs`:

- `expandClassSlotDefs` (both compilers dispatch `%class-slot-defs` through it):
  a `member` cond over every registered LAYOUT -- classes AND structs, since
  `ClosRegistry.slotDefs` is the one resolver both it and the interpreter use --
  designators being the instance tag (`%class-NAME`/`%struct-NAME`, what
  `class-of` yields) and the plain name, yielding the quoted
  `((slot-name declared-type) ...)` list (a struct's types all read `T`).
  Anything else (builtin type names included) is nil, the interpreter's
  semantics. A struct answering here is what lets a slot-walking serializer
  (json.lisp's `%json-out-instance`) treat a struct like a CLOS instance.
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
