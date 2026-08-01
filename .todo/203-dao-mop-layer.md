# Milestone: postmodern DAO layer (:postmodern-use-mop) -- the MOP question

Goal: the "highest level" postmodern API -- `table.lisp` (955 lines: DAO
classes, `get-dao`/`select-dao`/`insert-dao`/`update-dao`/`upsert-dao`/
`save-dao`, `dao-table-definition`) and the DAO halves of `deftable.lisp` and
`json-encoder.lisp` -- on all TCP-capable backends. This is the layer the
whole postmodern effort exists for; without it `.todo/202` (landed 2026-07-29)
delivers query/transaction sugar but no object mapping.

## DECIDED (2026-08-01): strategy 1 -- static MOP-subset execution at definition time

Verbatim upstream `table.lisp` runs on a real-but-STATIC metaobject layer. The
class-definition protocol (metaclass instantiation, slot-definition
metaobjects, `finalize-inheritance`, `build-dao-methods`) executes in the live
evaluator every backend already has at DEFINITION time: the interpreter's own
evaluator at load time, and the macro-time evaluator (`UserMacroExpander`'s
`LispEvaluator`) on the compile paths. `build-dao-methods`' `%eval` /
`(compile nil ...)` is intercepted as "expand and splice" (Phase C).

Why strategy 1 over a real runtime MOP (strategy 2), recorded here and to be
written into `.kb/clos.md` when the behavior lands:

- Every input is static in real programs: DAO classes are top-level
  `(defclass foo (...) (:metaclass dao-class))` forms with literal options;
  the `%eval`'d method bodies depend only on the class definition. Strategy 2
  buys generality no known program uses, at the price of exactly the
  interpreter/compiler divergence the working principles warn about.
- One shared mechanism keeps all four backends on one expansion (the governing
  rule). Dispatchers stay static, so `--optimize` and DCE keep working.
- Verbatim upstream sources -- consistent with the `.todo/147` policy and the
  cl-postgres bar; no rewrite shim, so no exception record needed.

Documented divergences to accept (write into the DAO doc page + `.kb/clos.md`):

- DAO classes built from RUNTIME data (a `defclass` with `:metaclass` in a
  non-top-level position, or protocol calls on classes unknown at definition
  time) signal an error instead of working.
- `finalize-inheritance` runs EAGERLY at class-definition time, not lazily at
  first use (upstream fires it from `class-finalized-p` checks inside
  `get-dao`/`select-dao`/...). Inputs are static so results are identical;
  definition-time errors just surface earlier.

## Validated substrate facts (survey 2026-08-01)

- `UserMacroExpander.registerMacroTimeDefinitions` already evals top-level
  `defun`/`defclass`/`defgeneric`/`defmethod` into a full macro-time
  `LispEvaluator` on the compile paths -- `build-dao-methods` and the whole
  table.lisp function set will be PRESENT and callable at expansion time.
- table.lisp's MOP surface is narrower than feared. Package-qualified uses:
  only `closer-mop:classp`/`class-slots`/`slot-definition-name`. Everything
  else arrives unqualified via the `closer-common-lisp` `:use` (CL union
  closer-mop, closer-mop wins collisions; flat re-export package). NOT used
  anywhere: `slot-definition-initargs`, `slot-definition-type`,
  `ensure-class`, `compute-slots`, `class-precedence-list`,
  `slot-value-using-class`, `reinitialize-instance`, funcallable instances,
  class redefinition.
- The protocol hooks table.lisp defines are ordinary methods once the GFs and
  base classes exist: `validate-superclass`, `direct-slot-definition-class`
  (`&key column col-type` -> `direct-column-slot` iff either is set),
  `compute-effective-slot-definition` (binds `*direct-column-slot*` around
  `call-next-method`), `effective-slot-definition-class` (reads it),
  `shared-initialize :before` on the class (parses `:table-name`),
  `shared-initialize :after` on the slot metaobject (computes `sql-name`,
  defaults nullable `:col-default` to `:null`), `finalize-inheritance :after`
  (computes `effective-keys`, then `build-dao-methods`). The contract to
  honor: `effective-slot-definition-class` AND the effective-slot
  `make-instance` (whose `direct-slot` slot has `:initform
  *direct-column-slot*`) must run INSIDE the dynamic extent of
  `compute-effective-slot-definition`'s `call-next-method`.
- `build-dao-methods` (table.lisp:525-671): `%eval` wraps ONE form -- a
  `let*` (fields/key-fields/ghost/value-fields/table-name) + `labels`
  (field-sql-name/test-fields/set-fields/slot-values) enclosing up to 8
  `defmethod`s: `dao-exists-p`, `update-dao`, `upsert-dao`, `delete-dao`,
  `get-dao`, `insert-dao`, `fetch-defaults`, `shared-initialize :after` --
  only the last three unconditional. Method bodies are CLOSURES over
  `sql-template` results. Specializers: the live class OBJECT spliced as a
  literal, and `get-dao`'s `(eql (class-name ,class))` whose form is
  evaluated at definition time (both statically foldable at capture time:
  object literal -> class name, eql form -> the name symbol).
- `dao-from-fields` (694-734) calls `allocate-instance` then
  `initialize-instance` with NO initargs after `setf slot-value`-ing every
  column -- deliberately bypassing initarg defaulting. Unbound slots already
  work (`.todo/199`).
- `define-dao-finalization` -> `defmethod make-dao :around ((class (eql 'name))
  &rest ... &key ... &allow-other-keys)`; the dispatch chain re-enters via the
  symbol method's `(find-class class-name)`.
- Feature gating is narrow and already pinned
  (`AsdfSystemsTest.thePostmodernMopBuildIsAFeatureFlip`): the replacement
  `postmodern-deps.asd` keeps `:if-feature :postmodern-use-mop` on `table` and
  `(:feature :postmodern-use-mop "table" "config")` on `deftable` verbatim.
  It OMITS upstream's `(:feature :postmodern-use-mop "closer-mop")`
  `:depends-on` clause -- add it (or the plain dependency) at flip time.
  `deftable.lisp` gates ONE form (`\!dao-def`), `json-encoder.lisp` gates ONE
  form (`encode-json` on `standard-object`, needs only
  `class-slots`/`slot-definition-name` + `find-symbol` probing).
- Today: `find-class` = prelude stub returning nil; `class-of` = tag-symbol
  stub; closer-mop shim = 4 defuns over the `(name type)` pairs
  `%class-slot-defs` returns; no `closer-common-lisp` package
  (`PackageRegistry` maps nickname `C2CL` onto `CLOSER-MOP` -- upstream C2CL
  is a nickname of closer-COMMON-LISP, re-point it in Phase A).
- The `:rontolisp-features` per-system reader-feature mechanism (from
  `.todo/204`) is the flip switch; additive-only, system-scoped, works on both
  loaders (`LispEvaluator.loadSystem`, `LoadInliner.spliceSystem`).

## Phase plan

### Phase A -- metaobject substrate (all four backends)

Real class metaobjects as ordinary CLOS instances, gated on use so programs
that never touch them stay byte-identical.

Landed so far (2026-08-01, interpreter side):

- `ClosRegistry.ensureMopClassesSeeded()` registers `standard-class` /
  `standard-direct-slot-definition` / `standard-effective-slot-definition`
  (slot order = a documented `%obj-ref` index contract). LAZY on purpose:
  the first, constructor-seeded attempt grew every runtime dispatch table and
  pushed the ci-spec corpus over the JVM 64 KB method ceiling
  (`JvmClassShakerCorpusTest`, 68235 bytes). Triggers: `classMetaobject()`,
  the closer-mop system load (interpreter loader), and a closer-mop defun in
  the program (`definesCloserMopFunction` in `expandTopLevelDefinitions`,
  ahead of the fast path -- the loaders that splice the shim have no registry
  in scope).
- `ClosRegistry.classMetaobject(name)`: memoized standard-class instance
  (name, direct-superclass metaobjects, direct-slots nil for now, effective
  slots as standard-effective-slot-definition instances, finalized-p t);
  invalidated on class re-registration. `isClassMetaobject` = ancestor test.
- Interpreter `find-class` (evaluator-defined, ahead of the prelude nil
  stub) with CL errorp semantics.
- closer-mop shim grew: `classp` (= `(typep x 'standard-class)`, compiles on
  every backend with zero new primitives), `class-name`,
  `class-direct-superclasses`, `class-finalized-p`,
  `slot-definition-initargs`; `class-slots`/`slot-definition-name`/
  `slot-definition-type` serve BOTH metaobjects and the legacy
  `(name type)` pairs (jzon's tag-designator path unchanged).
- Tests: 5 new `LispEvaluatorTest` cases (metaobject identity/shape, errorp,
  seeded conditions, shim both-generations).

Landed 2026-08-01, compile-path slice (all four backends now agree on
find-class):

- `expandTopLevelDefinitions` injects, gated on a `find-class` reference in the
  program (`needsFindClassRuntime`; a user/library `defun find-class` wins and
  suppresses it), the generated metaobject runtime: `%class-meta-table%`
  (chunked quoted data, one entry per registered class: spellings list /
  canonical superclass / per-slot `(name initargs initform type readers)`),
  the `%class-metaobjects%` memo global, and the `find-class` +
  `%find-class-materialize` defun pair. Materialization recurses through
  `find-class` for the superclass and memoizes under the canonical name (car of
  the spellings), so the answer is eq-stable across calls AND spellings.
- The gate seeds `ensureMopClassesSeeded()` BEFORE the walk (typep tables and
  %obj-new layouts see one registry) and joins the fast-path disjunction. The
  unknown-name signal is a literal-control `(error "FIND-CLASS: there is no
  class named ~A" sym)` -- the plain-message path, so no condition machinery is
  dragged in.
- The prelude's always-nil `find-class` stub is DELETED (LispPreludeLibrary).
- Ceiling fallout fixed in the same pass: seeding the three MOP classes grew the
  per-call-site `%class-slot-defs` inline dispatch past the JVM 65535-byte
  method limit in the ci-spec corpus (70178 bytes, `_top$21`). The expansion now
  lowers to a shared `%class-slot-defs-runtime` defun + node-budget-chunked
  `%class-slot-defs-table%` (same shape as `%typep-runtime`), injected by
  `expandTopLevelDefinitions` gated on a `%class-slot-defs` reference.
  `am.ik.jvm.MethodsDef` now names the offending method in the ceiling error.
- Static-model seam (documented in `.kb/clos.md`): compiled find-class sees the
  whole program's classes regardless of form order; the interpreter only knows
  classes already defined at call time.
- Tests: the 5 interpreter cases mirrored on JVM (`JvmLispCompilerTest
  compileFindClass* / compileCloserMopShim*`) and WASM
  (`WasmLispCompilerIntegrationTest`, same names); find-class doc pages +
  curated table rewritten (en+ja).

Landed 2026-08-01, the `class-of` -> metaobject migration (all four backends):

- `class-of` answers the memoized class metaobject for EVERY value: CLOS
  instances, struct instances (a `standard-class` instance too --
  `structure-class` does not exist, documented divergence), and built-in
  classes (`ClosRegistry.BUILTIN_CLASS_NAMES` = the `%class-designator` result
  set; `T` for everything else). `(eq (class-of x) (find-class name))` holds;
  `find-class` resolves struct names and built-in names now too.
- The old tag/type-name view became the internal `%class-designator` (new
  builtin + `expandClassDesignator`); consumer sweep re-pointed prelude
  `type-of`, `print-unreadable-object :type` (`typeNameOf`), the
  no-applicable-method message, and json.lisp's `%json-out-instance` at it --
  none of them drags the metaobject runtime in, outputs unchanged.
- Compile paths: `(class-of x)` = `(%find-class <designator dispatch> t)`;
  runtime restructured into internal `%find-class` (table scan + builtin
  fallback) + public `find-class` wrapper (injected only when referenced and
  not user-defined); `%class-meta-table%` entries gained the instance-tag
  spelling and struct-layout entries (`ClosRegistry.structParent` records the
  direct `:include` parent for the superclass chain).
- `%class-slot-defs` accepts a metaobject designator (name slot);
  `#'class-of`'s wrapper joined `REFERENCE_GATED_FUNCTIONS` (ungated it
  referenced `%find-class` in runtime-less programs). `class-name` added as a
  core prelude defun (CL_SYMBOLS count 347 -> 348).
- Tests: interpreter `classOf*`/`classDesignator*`/`classSlotDefs*` + JVM
  `compileAndRunClassOf`/`compileAndRunClassDesignator*`/... + WASM
  `classOfAndSlotAccessors`; ci-spec `find-class-metaobject-substrate` extended
  (incl. the raw metaobject print shape), `lite-builtins-residue` and
  `instance-print-syntax-and-identity` moved to the class-name view. Docs:
  class-of/find-class/type-of rewritten + new class-name page (en+ja);
  `.kb/clos.md` boundary paragraph rewritten.

Landed 2026-08-01, the Phase A remainder (closer-common-lisp +
allocate-instance -- Phase A COMPLETE):

- `closer-common-lisp` package (nickname `c2cl`, the built-in `C2CL` nickname
  RE-POINTED off CLOSER-MOP): a flat re-export seeded in `PackageRegistry` --
  every member recorded in `LispPackage.imports` pointing at its home package
  (cl or closer-mop, closer-mop wins collisions e.g. `class-name`), so
  resolution stays textual. Using it implies using cl
  (`PackageResolver.withImpliedUses`, applied at defpackage `:use` and
  use-package time); the `resolveUnqualified` use-list loops now redirect
  through a used package's imports (`usedExport`) -- the essential fix, which
  also repairs the latent "re-export inherited through :use spells under the
  re-exporter" bug for user packages; `memberSpelling` (find-symbol) redirects
  the same way. Mechanics + pinning tests: `.kb/packages.md`.
- `allocate-instance` on all four backends: all-slots-unbound instance from a
  metaobject or name designator, initargs ignored; CLOS classes only (built-in
  and struct classes signal). Interpreter = registry-backed built-in beside
  find-class; compile paths = `allocateInstanceDefuns` gated on a reference
  (`%ALLOC-INST-<n>` helpers chunked by cons-node budget -- %obj-new needs a
  literal tag, so the dispatch cannot be table-driven), no MOP seeding of its
  own. `ALLOCATE-INSTANCE` joined `CL_FUNCTIONS` (list-functions 348 -> 349,
  four pins bumped incl. ci-spec). Docs: allocate-instance pages (en+ja) +
  catalog + curated rows; asdf-systems shim table row rewritten. ci-spec
  `find-class-metaobject-substrate` extended with the allocate round-trip.

- A MOP base library (grow `closer-mop.lisp` into it, or a sibling loaded with
  it): `standard-object`, `standard-class`, `standard-direct-slot-definition`,
  `standard-effective-slot-definition` as real registered classes; slots per
  what the protocol reads (name, direct-superclasses, direct-slots,
  effective-slots, finalized-p; slot-definition: name, initargs it was built
  with, initform...). System-supplied default methods for the protocol GFs
  (`validate-superclass`, `direct-slot-definition-class`,
  `effective-slot-definition-class`, `compute-effective-slot-definition`,
  `shared-initialize` on metaobjects, `finalize-inheritance`).
- `find-class` (with errorp semantics), `class-of` returning the metaobject,
  `class-name`, `class-direct-superclasses`, `class-finalized-p`,
  `closer-mop:classp`, `closer-mop:class-slots` returning REAL
  effective-slot-definition metaobjects with `slot-definition-name` /
  `slot-definition-type` methods (audit the current `(name type)`-pair
  consumers: jzon's coerced-fields walk and json.lisp's `%json-out-instance`
  ride `%class-slot-defs` -- keep that primitive as-is underneath).
- `class-of` migration needs a consumer sweep: `type-of` is a prelude defun
  over it, `print-unreadable-object :type`, `%class-slot-defs` designators
  accept the tag it returns today. Decide per consumer: keep the tag as the
  INTERNAL designator, make `class-of` the user-facing metaobject view.
- Compile paths: metaobjects must exist at RUNTIME too (`dao-from-fields`,
  `select-dao` read them). Emit construction/memoization through
  `expandTopLevelDefinitions` the way `%typep-runtime`'s chunked tables are
  emitted, gated on the program (or a loaded library) actually reaching
  `find-class`/`class-of`/`class-slots`.
- `closer-common-lisp` package: flat re-export of CL union closer-mop
  exports, nicknames `c2cl`; re-point the `C2CL` nickname.

### Phase B -- metaclass protocol at class definition -- DONE 2026-08-01

Landed (all four backends agree, byte-identical output; mechanics in
`.kb/clos.md`):

- `expandDefclass` accepts `(:metaclass M)` (M registered + descends from
  standard-class, else error; strict errors stay without a metaclass):
  unknown CLASS options -> metaclass initargs valued with the option TAIL
  list; unknown SLOT options -> per-slot extras (single occurrence each)
  riding the canonical spec plist. Static registration unchanged; the
  expansion emits one `(%ensure-class-with-metaclass ...)` driver call last.
- The driver + protocol defaults (validate-superclass permissive /
  direct-slot-definition-class / effective-slot-definition-class /
  compute-effective-slot-definition with the `*direct-column-slot*`
  dynamic-extent contract / finalize-inheritance EAGER) live in
  `macro/mop-protocol.lisp` (`MopProtocol.forms()`, FormatRenderer pattern),
  self-contained over `%obj-ref` indexes, defmethods only so user hooks merge
  in either order. Defaults answer class NAMES so plain closer-mop programs
  drag no find-class runtime in.
- Interpreter: `ensureMopProtocolLoaded` on the first `:metaclass` defclass
  (+ MOP seeding on a defclass extending a seeded base class); builtins
  `%mop-make-instance` (re-enters ordinary make-instance with quote-wrapped
  args) and `%register-class-metaobject`
  (`ClosRegistry.registerClassMetaobject` primes the memo). Compile paths:
  protocol forms PREPENDED (their find-class use flips the metaobject
  runtime), `seededMopConstructorDefuns` + generated `%mop-make-instance`
  (arms = metaobject-ancestored classes only) + `%register-class-metaobject`
  appended post-walk.
- Ceiling fallout fixed the essential way AGAIN: the runtime-slot-name
  `slot-value`/`slot-boundp` dispatch was inline per call site and grew with
  the registry; the new ci-spec classes pushed a corpus dolist body past the
  JVM signed 16-bit branch encoding. Now outlined into shared
  `%slot-value-runtime`/`%slot-boundp-runtime` defuns (gated on a
  non-literal-name site; the interpreter resolves natively). Bonus: a
  top-level compile crash now names the offending form (JvmLispCompiler
  chunk-loop wrapper). Runtime-name `(setf (slot-value ...))` was and stays
  unsupported.
- Tests: `defclassMetaclassRunsTheClassDefinitionProtocol` (+ the
  requires-a-registered-metaclass error case) on LispEvaluatorTest, mirrored
  on JVM + WASM; ci-spec `defclass-metaclass-protocol`. Docs: defclass page
  (en+ja) gained the metaclass section + example and dropped the stale "no
  find-class" clause. `CLOSER_MOP_EXTERNALS` grew the 5 protocol names + the
  2 slot-definition class names (closer-common-lisp re-exports them).

### Phase C -- build-dao-methods: %eval interception ("expand and splice")

- Add `compile`: `(compile nil form)` = function from the lambda form,
  evaluated in the evaluator at hand (interpreter + macro-time evaluator).
- The `%eval`'d form is captured as AST (backquote has already run, so the
  class-object literal sits in the tree). Transform at capture time:
  class-object specializer -> the class name; `(eql (class-name ,class))` ->
  the folded name symbol; each `defmethod` -> STATIC method registration in
  `ClosRegistry` whose body funcalls a generated global, plus a `setq` of
  that global to `(lambda (params) body)` IN PLACE inside the let*/labels
  form -- so the lexical closure capture (sql templates) rides the existing
  first-class lambda machinery that works on all backends.
- The transformed let*/labels form is then: evaluated immediately on the
  interpreter; spliced as a top-level runtime form on the compile paths
  (macro expansion of `sql-template` etc. happens there like any user macro).
  Dispatchers regenerate/emit through the existing defmethod plumbing.
- Open sub-decision (validate while implementing): intercept specifically at
  `%eval`/`compile` (pattern-targeted), or make "defmethod inside evaluated
  code at definition time" general. Start pattern-targeted; the general form
  has no second consumer today.

### Phase D -- feature flip + integration

- `postmodern-deps.asd`: add `:postmodern-use-mop` to `:rontolisp-features`,
  add the `closer-mop` dependency (upstream carries it as
  `(:feature :postmodern-use-mop "closer-mop")` -- keep that shape).
  `table.lisp` rejoins the build via the verbatim `:if-feature`; postmodern's
  own `defpackage` switches `:use` to `:closer-common-lisp` by itself.
- `save-dao/transaction`, `do-select-dao`, `with-column-writers`,
  `dao-row-reader-with-body`; `handler-case` on `unique-violation` /
  `columns-error` / `unbound-slot`.
- Update `AsdfSystemsTest` pins (`parsesTheBundledPostmodernReplacementAsd`
  expects table.lisp ABSENT today; `thePostmodernMopBuildIsAFeatureFlip`
  stops passing a synthetic feature set once the real one is on).

### Phase E -- E2E + docs + kb

- Extend `PostmodernE2eTest` with a DAO round-trip (deftable + create +
  insert-dao + get-dao + upsert-dao returning `(values dao inserted-p)`),
  byte-identical across interpreter/JVM/WASM-component.
- Docs: DAO pages (en+ja); document the static restrictions above.
- `.kb/clos.md`: replace "MOP permanently out" with the precise new boundary
  (static definition-time MOP subset IN; runtime class construction OUT),
  the why, and the re-evaluation trigger, per the working principles.
