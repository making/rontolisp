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
  registered). `#'class-of`'s wrapper is REFERENCE_GATED in
  `BuiltinFunctionWrappers` -- ungated it referenced `%find-class` in programs
  the injection scan said needed no runtime. `class-name` is core (a prelude
  defun over metaobject slot 0). The metaobject slot order (name,
  direct-superclasses, direct-slots, effective-slots, finalized-p;
  slot-definitions: name, initargs, initform, type, readers) is a `%obj-ref`
  index contract shared with the closer-mop shim -- append, never reorder.
  Pinning tests: `LispEvaluatorTest`/`JvmLispCompilerTest`/
  `WasmLispCompilerIntegrationTest` `*FindClass*` + `*CloserMopShim*` +
  `classOf*`/`compileAndRunClassOf`/`classOfAndSlotAccessors`, ci-spec
  `find-class-metaobject-substrate` (raw metaobject print shape included). Still
  OUT (the divergence's remaining "why": classes are compile-time-static,
  `--optimize` DCE and the dispatch tables depend on it): runtime class
  construction (`ensure-class` from computed data), `add-method`,
  `compute-applicable-methods`, class redefinition, `update-instance-for-*`.
  Known static-model seam: on the compile paths `find-class`/`class-of` see the
  WHOLE program's classes regardless of form order, while the interpreter only
  knows classes already defined at call time.
  `change-class` is the ONE runtime exception and is not MOP: both classes are literal, so
  the whole change is a static expansion (see above).
- Multiple inheritance, `:allocation`/`:writer` slot options, eql specializers
  on strings.
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
