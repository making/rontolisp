# Library defun pruning (AST tree-shaking) + constant-pool deduplication

1. **`LibraryDefunPruner`** (`am.ik.rontolisp.eval`): an AST-level pre-pass dropping spliced
   library definitions unreachable from the user program, before the compilers' Pass 1. The
   library pre-passes splice each library WHOLE, and the `--optimize` shakers can only trim
   after a full serialization.
2. **`ConstantPool` deduplication** (`am.ik.jvm`): the pool builder returns the existing entry
   when an identical constant is added again.

## Where it runs, and what is prunable
`LibraryDefunPruner.prune(program)` runs at the END of the compile-path splice chain:
`RontoLispCli.compileToFile` (skipped under `--dynamic` and `--no-prune`, both in
`CliOptions`, `--no-prune` in `noValueKeys`), `RontoPlayground.compileJvm`/`compileWasm`,
`JvmClassShakerCorpusTest` / `WasmTreeShakerCorpusTest`, `AsdfLibraryE2eSupport.compileProgram`
(the 12 real-library E2E subclasses). NOT the interpreter nor the per-library compiler tests.

- **Part 1 — rontolisp's own libraries**: top-level `defun`/`defparameter`/`defvar`/
  `defconstant` whose name is defined by linalg, torch, vec, json (+ its `#'` wrapper defuns),
  url, or the prelude, collected from their `forms()` (`JsonLibrary.wrapperForms()` /
  `LispPreludeLibrary.names()`).
- **usocket is excluded entirely**: its `with-*` built-in macros synthesize
  `usocket:socket-close` / `usocket::%usock-guard` / `%usock-resignal` calls absent from the
  pre-expansion AST.
- **Part 2 — an ASDF-spliced third-party tree**: the same four kinds for every form
  `LoadInliner` spliced for a system.

## A BUNDLED library's `defstruct` is expanded BEFORE reachability
`BundledStructs.expand` inside `prune()`, right after resolution, expands it into exactly the
defuns the compilers would generate (`LispMacroExpander.expandDefstruct`, `.kb/defstruct.md`)
so every constructor, predicate, copier and accessor prunes INDIVIDUALLY. Deliberately NOT the
third-party `Candidates` keyed-unit rule.

- Recognized by the struct name's PACKAGE (`BUNDLED_STRUCT_PACKAGES`: torch/linalg/vec).
  Extend the set (never widen to `RONTOLISP`) if json/url/prelude gain one; third-party and
  user defstructs stay on the compilers' expansion path.
- The expansion populates compiler-owned state (`structAccessors` incl. the
  `TYPED_VECTOR_SLOT_BASE` encoding, and `ClosRegistry`), so a
  `(%struct-definition (defstruct ...))` marker (`LispNames.STRUCT_DEFINITION`) takes the
  form's place and `expandTopLevelDefinitions` re-runs `expandDefstruct` against the
  compilation's own registries, DISCARDING the regenerated forms; `refreshStructPredicates`
  rebuilds a kept predicate once the registry is known. The marker SURVIVES `PackageResolver`
  (explicit branch) and the reference scan SKIPS it -- its payload spells option names and slot
  initforms that would anchor the very defuns the expansion made prunable.
- A generated `%setf-` writer is keyed under its ACCESSOR too (the writer call is synthesized
  after this pass). **A `(:print-object ...)` struct's generated method is keyed under the
  struct's CONSTRUCTORS** (`BundledStructs.printerKeysAt`) -- the one generated form with no
  definition name; as a nameless root it would anchor its printer and every accessor it reads.
- `resolveSymbolName` passes any `%struct-`-prefixed symbol through (its `::` is not a package
  qualifier); both expansions use the post-resolution export oracle `spellsAsExternal`.
- A defstruct the expansion cannot take stays an unexpanded root; `--dynamic`/`--no-prune` run
  `stripSystemMarkers` instead; `--no-gc` is carved out. **Carve-out**: a `#S(NAME ...)`
  literal is not a reference source, so a program whose ONLY use of a slot initform's callee is
  through `#S` fails loudly at compile.
- Consumer is torch's three records (`.kb/torch.md`); a `(:print-object ...)` struct turns the
  `print-object` seam on program-wide (`.kb/clos.md`), a cost the pruner cannot collect.
- Pins: the `aBundledDefstruct*` / `theStructDefinitionMarkerAnchorsNothingItSpells` /
  `aTypedVectorStructsSetfWriterRidesItsAccessor` / `aUserDefstructIsNeverExpandedOrPruned`
  group in `LibraryDefunPrunerTest`, and
  `Jvm/WasmLispCompiler*Test#compileAndRunAPrunedBundledDefstructThroughTheRegistrationMarker`.

## Reachability (carve-out semantics)
A reference is ANY occurrence of the name in a kept form -- operator, argument, quoted data,
`(function ...)` -- PLUS any string literal CONTAINING a prunable name as a substring (keeps
`(intern "linalg:norm")` idioms working). Fixpoint from the roots = every top-level form that
is not a prunable library definition.

- **Two hardcoded synthetic edges**: `vec:aref` also references `vec:aset`, and
  `torch:no-grad` references `torch::*grad-enabled*` -- both synthesized AFTER the pruner.
  Re-verify if a new built-in macro expansion emits a
  `linalg:`/`torch:`/`vec:`/`rontolisp::%json`/url/prelude name.
- Two spellings widen this for THIRD-PARTY names only: an uninterned `'#:foo` designator, and
  a string literal whose WHOLE content is a canonical or member name. Both hash lookups.
- **Not reference sources**: a `set-dispatch-macro-character` HOOK
  (`LispMacroExpander.isReadtableHookRegistration` / `isDeadReadtableHook`; rontolisp's reader
  is not readtable-driven, so the registration is a no-op -- but a hook argument that is a CALL
  still counts); a top-level `declaim`/`proclaim` (the FORM STAYS for
  `SpecialVarCollector.collectForm`, only its symbol occurrences stop counting).
- **A class header is not a call site**: `collectClassDefinitionReferences` walks
  `defclass`/`define-condition` by POSITION, because the pass has ONE string key space while
  the language has two namespaces (`.kb/lisp2-namespaces.md`). Skipped are only the DEFINING
  positions (class name, slot names, and the `:reader`/`:writer`/`:accessor`/`:initarg`/
  `:allocation`/`:documentation` values); superclass list, `:initform`, `:type` and every other
  option still scan, `(:metaclass ...)` whole. Pins
  `#aDefclassDoesNotKeepTheDefunOfTheSameName`,
  `#aSlotAccessorNameIsADefinitionAndAnInitformIsStillCode`.

## Safety valves
- Analysis runs on a `PackageResolver.resolveProgram` copy (index-aligned 1:1); removal is by
  index on the pre-resolution list, so surviving forms are byte-identical.
- The pass first runs `LispMacroExpander.flattenTopLevel`, or the defuns cl-postgres'
  `deferror`/`define-message` emit inside a `progn` are all roots. Byte-identity relaxes to
  "byte-identical, progn-spliced".
- **Bail (prune nothing)** on a runtime `load`/`require` surviving the `LoadInliner`, a
  resolution throw, or no prunable definition. **The bail is a cliff, not a gradient**: ONE
  quoted `'load` anywhere reverts the whole optimization with no diagnostic.
- **Documented limitation**: a program forging a qualified name at runtime and invoking it via
  `eval`/`apply` gets the ordinary "undefined function" error; `--no-prune`/`--dynamic`
  restores everything. Loud, never silent wrong output. Deliberately NOT a bail condition.

## Third-party provenance
`LoadInliner.spliceSystem` brackets everything it splices with `(%begin-system "NAME")` /
`(%end-system)` (`LispNames.BEGIN_SYSTEM`/`END_SYSTEM`, consumed by `PackageResolver.resolve`,
dropped by the pruner). **The provenance must ride IN the form list**: an identity map dies at
`UserMacroExpander.expand`, an index map dies because five passes between inliner and pruner
insert, expand 1->N, delete and splice N->M. Brackets NEST with `:depends-on`, innermost wins,
and a `BuiltinSystems` splice gets its OWN bracket so it does not inherit a dependent system's
prunability (without it `USOCKET::%USOCK-RESIGNAL` is pruned and a cl-postgres program dies at
the `with-*` guard expansion). Unbalanced brackets disable third-party pruning rather than
guess.

### The CLOS definition kinds are candidates too (third-party provenance only)
`Candidates` in the pruner; the name summaries live BESIDE the expansions they mirror
(`LispMacroExpander.defstructDefinedNames`/`classDefinedNames`/`prunableGenericName`/
`defmethodSpecializerNames`) and must move with them.

- **Keyed kinds**, kept iff ANY defined name is referenced from a kept form:
  `defclass`/`define-condition` under the class name + every `:reader`/`:accessor`; `defstruct`
  under the struct name, every constructor, predicate, copier and accessor (including
  `:include`d ones resolved through the candidate chain; a parent outside the candidate set
  roots the child); `defgeneric` under its setf-normalized name. Generated names carry BOTH
  colon spellings.
- **`defmethod` is gated, per method**: the GENERIC gate (some spelling of the generic name is
  live) plus one SPECIALIZER gate per required parameter naming a candidate class/condition/
  struct, satisfied when an INSTANTIATOR name is live -- an instance can only be made through a
  reference the scan sees, and a live SUBCLASS keeps its ancestors live textually. A kept
  form's OWN keys are excluded from the references it contributes.
- **The generic gate is absent for a CL protocol name** (`PackageRegistry.CL_SYMBOLS`):
  `initialize-instance`/`print-object`/`close`/... are called by SYNTHESIZED code, so those
  methods are kept on the specializer gate alone.
- **The specializer gate applies only when the generic's method set stays closed**: an OWNED
  generic or a CL protocol name. A METHOD-ONLY local generic keeps its methods once the name is
  live, or a kept call site compiles against no definition.
- **What stays a root**: a `defclass` with `(:metaclass ...)`; EVERY class when the program
  mentions `class-direct-subclasses`/`%class-direct-subclasses` (cl-dbi's `find-driver` reaches
  a driver class through subclass ENUMERATION plus a forged string); the top-level
  `let`-over-`defmethod` idiom (not a definition form); `deftype` (`(satisfies F)` expands to a
  literal call).
- **The name-template rule**: a `(concatenate 'string ...)` or `(format nil "..." ...)`
  argument of `intern`/`find-symbol` becomes a TEMPLATE (literal pieces literal, everything
  else and every format directive a hole), and every third-party member name it can produce
  counts as referenced (sxql's `(intern (concatenate 'string "MAKE-" ...))`). Fires only from
  KEPT forms; a piece-less assembly stays the computed-name carve-out.
- **Re-evaluation triggers**: a forged name staged through a variable escapes the one-hop
  template scan; a string-assembly other than `concatenate`/`format nil` (`uiop:strcat`) needs
  its own arm; the `let`-over-`defmethod` root is the residual anchor on the cl-ppcre
  zero-reference probe.
- `defmacro`, `define-compiler-macro`, `define-modify-macro`, `defsetf`,
  `define-setf-expander`, `macrolet` need no rule: `UserMacroExpander` registers and drops them
  first.
- Pins: the full `ci-spec.yaml` native run, the 12 `AsdfLibraryE2eSupport` subclasses,
  `MitoE2eTest` (live PostgreSQL), `ClPostgresE2eTest`, `SxqlE2eTest` (the template rule), and
  the CLOS group in `LibraryDefunPrunerTest` (one test per rule above, e.g.
  `aMethodOnALiveGenericSpecializingADeadClassIsPruned`, `subclassEnumerationRootsEveryClass`,
  `aForgedNameTemplateKeepsEveryDefinitionItCanProduce`).

## The dead-branch stages
Two mechanisms inside `prune()` removing BRANCHES of kept third-party definitions so the
references inside them stop anchoring subtrees. chipz needs both to lose its bzip2 decoder. A
program using neither shape is byte-identical.

### Stage A — `ConstantCaseArmPruner`
In `eval`, called from `prune()` right after package resolution: deletes a `case`/`ecase` arm
inside a third-party form when NO value its subject can take may `eql` any of the arm's keys.

- Monotone least fixpoint over per-REQUIRED-parameter VALUE sets (constants: symbols/integers/
  characters; NON-KEY: struct instances / function objects; TOP otherwise), flowing through
  direct calls, `funcall`/`apply` of a literal `#'f`/`'f` (spread positions TOP), `let`/`let*`
  and expression tails (a tail `(error ...)` contributes nothing). Calls to a `defgeneric`
  family join into EVERY method's parameters -- dispatch is not modeled, only widening. A name
  escapes (parameters AND return TOP) on quoted data, a string spelling it, `#'f` outside
  `funcall`/`apply` head position, a `case` key, or a shadowing `flet`/`labels` local. Deleting
  a never-matching arm preserves EVERY execution.
- **Stage A flows through struct SLOTS too**: every `defstruct` contributes a per-SLOT value
  set shared along the `:include` chain -- a keyword constructor maps `:slot` arguments, a BOA
  one its REQUIRED parameters (any `&`-marker widens every non-required slot to TOP), a
  defaulted slot joins its INITFORM, `(setf (accessor x) v)` joins the written value while RMW
  places and `(setf (slot-value ...))` widen. Parse: `LispMacroExpander.defstructSlotFlow` (a
  `:type` vector layout marks the summary opaque). Escapes: an accessor/constructor spelling in
  quoted data or strings, `#'(setf accessor)`, `with-slots`/`with-accessors`, a `#S` literal, a
  computed-class `make-instance`, and a runtime `read`/`read-from-string`. What sheds the BYTES
  behind a dead labels-state arm is the expansion-time drop of unreferenced locals
  (`.kb/flet-labels.md`).
- **Place semantics are load-bearing in `poisonPlace`, in BOTH directions.** A cons place
  mutates an object and must NOT poison the variable (poisoning every cons place costs the
  whole chipz fold). But the RMW places `expandSetf` lowers onto an INNER place -- `ldb`/
  `mask-field` (arg 2), `getf` (arg 1), `the` (arg 2), `values` (each subplace) -- DO assign
  the variable inside; treating cl-postgres's `(setf (ldb (byte 8 24) result) ...)` as object
  mutation folded `authenticate`'s ecase arms away (`ECASE: no clause matches 10`).
  **`poisonPlace` mirrors `expandSetf`'s case list; a new RMW place family must join both in
  the same commit.** Pinned by `aReadModifyWritePlaceKeepsTheCaseSubjectUnknown`.

### Stage B — instantiator-gated `typecase`/`etypecase` arms
`GatedArm` / `GateContext` in the pruner: an arm whose clause head names a candidate
struct/class contributes its references only once an INSTANTIATOR is live -- same soundness
argument and `gateBySpelling` table as the defmethod specializer gate. An arm still closed at
the end is DELETED (its body may name pruned definitions that would not compile). Deliberately
NOT extended to `case` (a case key needs no instantiator; Stage A is sound there). Gating
applies only to prunable-system provenance, so user code is never rewritten.

Both stages rewrite through `ConstantCaseArmPruner.FormRewriter`: clause conses deleted by
identity with a lockstep walk over the pre-resolution/resolved twins, `LispCons.rebuilt` +
`SourceProvenance.inherit` per spine cell (`.kb/source-positions.md`), original subtree kept on
any structural mismatch. Byte-identity relaxes to "byte-identical minus proven-dead arms".

**Re-evaluation triggers**: Stage A's binder model walks `loop`/`macrolet`/`symbol-macrolet`
bodies under an opaque env and `handler-case` tails are TOP; a `defgeneric` narrowed by
dispatch (`compiler.ArgumentShapes`) would sharpen the join.

Tests: the `aCaseArm*` / `aTypecaseArm*` group in `LibraryDefunPrunerTest` plus its slot-flow
group (`aCaseArmNoStoredSlotValueCanReachIsFolded`,
`aReadModifyWriteOnTheSlotPlaceKeepsEveryArm`, `aRuntimeReadStandsTheSlotTrackingDown`, ...).
`ChipzE2eTest` pins it end to end.

## A third-party variable definition needs a pure initform
Pruned only when its initform is provably a pure value computation
(`hasPrunableInitform`/`isPureValue`: a literal, a variable read, a `quote`/`function`, or a
call to a small allocation/arithmetic/reader set with pure arguments -- deny by default). This
is the one place the pass could produce SILENT wrong output: a
`(defvar *registered* (register-all-types))` nobody reads would lose the registration. The
guard keeps `md5::*t*`, whose `loop` the judgment cannot see through. BUNDLED libraries keep
the unconditional rule they were audited under.

## The matching rule
A `defpackage` `:export` keyword does NOT anchor a library's API: the scan runs on the resolved
copy, where `PackageResolver.resolveDefpackage` has already replaced the form with
`(quote PKG)`. **Load-bearing**: 62% of the dead cl-postgres defuns are exported, so preserving
that clause list would drop the yield 38% -> 14%.
`anExportedButUnreferencedThirdPartyFunctionIsStillPruned` is the tripwire.

**The substring rule is NOT extended to third-party names** (its only hits there are docstring
coincidences): those get exact / `#:`-stripped / whole-string hash lookups, O(1) per literal,
while the ~230-name substring scan stays. **Keywords deliberately do not widen**
(re-evaluation trigger): `(string :foo)` is as valid a designator as `(string '#:foo)`, but
reversing it rescues exactly ONE definition corpus-wide while colliding with 7 unrelated
keyword spellings. One line in `collectReferences`.

## Standing relative to `--optimize`
`--optimize` cannot substitute: the WASM/JVM shakers root at exports, and a defun the
funcall-dispatch gate keeps dispatchable (`.kb/optimize-dead-code-elimination.md`) stays
reachable through the ladders. Pruning at the AST level removes a definition regardless of the
gate, and shrinks the UNOPTIMIZED artifact too. **A "dead top-level `let`" rule was measured
and rejected** (`definitionName` returns null for a `let`; 14 occurrences, two dead blocks, 771
bytes of a 1.55 MB module): a top-level `let` is STILL opaque to the pass.

## The constant-pool dedup
`am.ik.jvm.ConstantPool.add` keys every entry by its serialized bytes (tag + payload) in a
`HashMap` and returns the existing `Constant` on a hit; a composite entry embeds the u2 indexes
of its already-deduplicated components, so sharing cascades. `addLong`/`addDouble` pass a
`twoSlots` flag into the shared `add` so a cache hit does not double-count the second slot;
doubles key by `doubleToLongBits` (`-0.0` and `0.0` stay distinct). Duplicates are legal in the
class format, so dedup needs no flag; `JvmClassShaker` is unchanged; nothing in
`am.ik.jvm`/`codegen.jvm` predicts "the next index will be `size()+1`" (grep-verified) and
`Constant` is immutable, so returning a shared instance is safe.
