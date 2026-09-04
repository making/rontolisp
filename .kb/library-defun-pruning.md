# Library defun pruning (AST tree-shaking) + constant-pool deduplication

1. **`LibraryDefunPruner`** (`am.ik.rontolisp.eval`): an AST-level pre-pass dropping spliced library definitions unreachable from the user program, before the compilers' Pass 1. The library pre-passes (`LinalgLibrary.process` &c) splice each library WHOLE, and the `--optimize` shakers can only trim after a full serialization.
2. **`ConstantPool` deduplication** (`am.ik.jvm`): the pool builder returns the existing entry when an identical constant is added again.

## Where the pruner runs

`LibraryDefunPruner.prune(program)` runs at the END of the compile-path splice chain:

- `RontoLispCli.compileToFile`, after the conditional `VecLibrary.process`; skipped under `--dynamic` and `--no-prune` (both in `CliOptions`; `--no-prune` is in `noValueKeys`).
- `RontoPlayground.compileJvm`/`compileWasm` (`-Pweb`).
- `JvmClassShakerCorpusTest` / `WasmTreeShakerCorpusTest` (they mirror the CLI pipeline).
- `AsdfLibraryE2eSupport.compileProgram` — the 12 real-library E2E subclasses (cl-ppcre, ironclad, uax-15, jzon, md5, cl-base64, cl-who, ...): the coverage for pruning a real third-party tree.

NOT in: the interpreter (lazy-loads libraries whole), the per-library compiler unit tests (they compile the full splice deliberately).

## Prunable set

**Part 1 — rontolisp's own libraries**: top-level `defun`/`defparameter`/`defvar`/`defconstant` whose name is defined by linalg, torch, vec, json (+ its `#'` wrapper defuns), url, or the prelude. `LispPreludeLibrary.process` selects entries to a fixpoint, so a prelude defun pulled in only by another (the string family's `%string-compare`) is present and stays reachable through the kept caller. Collected once from the libraries' `forms()` (package-private `JsonLibrary.wrapperForms()` / `LispPreludeLibrary.names()`).

**usocket is excluded entirely**: its `with-*` built-in macros synthesize `usocket:socket-close` / `usocket::%usock-guard` / `%usock-resignal` calls absent from the pre-expansion AST.

**Part 2 — an ASDF-spliced third-party tree**: the same four definition kinds, for every form `LoadInliner` spliced for a system (see "Third-party provenance").

## A BUNDLED library's `defstruct` is expanded BEFORE reachability

`BundledStructs.expand` inside `prune()`, right after resolution, expands it into exactly the defuns the compilers would generate (`LispMacroExpander.expandDefstruct`; a defstruct has no backend codegen, `.kb/defstruct.md`), so every constructor, predicate, copier and accessor prunes INDIVIDUALLY. Deliberately NOT the third-party `Candidates` keyed-unit rule (kept-iff-any-name-referenced would make one accessor keep the whole surface).

- Recognized by the struct name's PACKAGE (`BUNDLED_STRUCT_PACKAGES`: torch/linalg/vec) — name-not-origin, like the defun rule. Extend the set (never widen to `RONTOLISP`) if json/url/prelude ever gain a defstruct. Third-party (`%begin-system`-bracketed, builtin brackets included) and user defstructs stay on the compilers' expansion path.
- The expansion populates compiler-owned state (`structAccessors` — `expandSetf`'s place registry incl. the `TYPED_VECTOR_SLOT_BASE` encoding — and `ClosRegistry` layout/predicate/slot-type registrations), so a `(%struct-definition (defstruct ...))` marker (`LispNames.STRUCT_DEFINITION`) takes the form's place in the stream and `expandTopLevelDefinitions` consumes it: re-runs `expandDefstruct` against the compilation's own registries and DISCARDS the regenerated forms. A `(:print-object ...)` struct's synthesized defmethod rides the stream as a raw defmethod; `refreshStructPredicates` rebuilds a kept predicate once the registry is known.
- The marker SURVIVES `PackageResolver` (an explicit branch resolves its payload, keeps the head), and the pruner's reference scan SKIPS it — the payload spells option names and slot initforms that would anchor exactly the defuns the expansion made prunable.
- Generated names join the bundled prunable set. A generated `%setf-` writer (typed-vector structs) is keyed under its ACCESSOR too, because `(setf (acc x) v)` / `#'(setf acc)` spell only the accessor and the writer call is synthesized after this pass.
- **A `(:print-object ...)` struct's generated `print-object` method is keyed under the struct's CONSTRUCTORS** (`BundledStructs.printerKeysAt`) — the one generated form with no definition name; as a nameless root it would anchor its printer defun and every accessor the printer reads. Gate: a method only applies to an instance, an instance only comes from a constructor call the scan sees.
- Name spelling agrees on both sides because both expansions run with a post-resolution resolver's export oracle (`spellsAsExternal`; bundled packages are builtin). The struct tag `'%struct-PKG::NAME` meets the compilers' re-resolution, so `resolveSymbolName` passes any `%struct-`-prefixed symbol through (its `::` is not a package qualifier).
- A defstruct the expansion cannot take (malformed, an `:include` parent outside the expanded set) stays an unexpanded root. `--dynamic`/`--no-prune` run `stripSystemMarkers` instead. `--no-gc` is carved out (it rejects defstruct anyway).
- **Carve-out**: a `#S(NAME ...)` literal is not a reference source, so a program whose ONLY use of a slot initform's callee is through `#S` fails loudly at compile ("Cannot compile: undefined"); `--no-prune` restores everything.
- Consumer is torch's three records (`.kb/torch.md`). A `(:print-object ...)` struct turns the `print-object` seam on for the whole program (`.kb/clos.md`), a fixed cost the pruner cannot collect.
- Pinned by `LibraryDefunPrunerTest#aBundledDefstructsGeneratedDefunsPruneIndividually` / `#aBundledDefstructsPrinterMethodLeavesWithItsConstructor` / `#theStructDefinitionMarkerAnchorsNothingItSpells` / `#aBundledDefstructIncludeChainPrunesInheritedAccessorsIndividually` / `#aTypedVectorStructsSetfWriterRidesItsAccessor` / `#aUserDefstructIsNeverExpandedOrPruned` / `#withoutPruningABundledDefstructStaysUntouched`, and `Jvm/WasmLispCompiler*Test#compileAndRunAPrunedBundledDefstructThroughTheRegistrationMarker`.

## Reachability (carve-out semantics)

A reference is ANY occurrence of the name in a kept form — operator position, argument position, quoted data, `(function ...)` — PLUS any string literal containing a prunable name as a substring (keeps `(intern "linalg:norm")` / `read-from-string` idioms working). Fixpoint from the roots = every top-level form that is not a prunable library definition.

**Two hardcoded synthetic edges**: `vec:aref` also references `vec:aset` (`(setf (vec:aref ...))` expands to it AFTER the pruner), and `torch:no-grad` references `torch::*grad-enabled*` (`expandTorchNoGrad` synthesizes the `let` after the pruner). These are the only places a library-qualified name is synthesized rather than written; re-verify if a new built-in macro expansion emits a `linalg:`/`torch:`/`vec:`/`rontolisp::%json`/url/prelude name.

Two spellings widen this for THIRD-PARTY names only: an uninterned `'#:foo` designator, and a string literal whose WHOLE content is a definition's canonical or member name. Both are hash lookups.

**Not reference sources:**
- A `set-dispatch-macro-character` HOOK (`LispMacroExpander.isReadtableHookRegistration` / `isDeadReadtableHook`; the expansion drops the argument): rontolisp's reader is not readtable-driven, so the registration is a no-op. A `#'name`/`(lambda ...)` argument is a pure reference; a hook argument that is a CALL still counts. It buys ironclad's `array-reader` for `#@`, whose body was the ONLY `read` in a whole postmodern program, so pruning it also stops the embedded reader runtime being emitted.
- A top-level `declaim`/`proclaim` (both expand to `LispNil`): `(declaim (inline F))` / `(declaim (ftype ...))` would otherwise anchor F. The FORM STAYS (`SpecialVarCollector.collectForm` reads `(declaim (special ...))`); only its symbol occurrences stop counting.

### A class header is not a call site

A `defclass`/`define-condition` is walked by POSITION (`LibraryDefunPruner.collectClassDefinitionReferences`): the pass has ONE string key space while the language has two namespaces (`.kb/lisp2-namespaces.md`), so a class header spelling a `defun`'s name read as a call to it (`geom:bounds` is both, and geom's class forms are unkeyed ROOTS, being bundled rather than `%begin-system`-bracketed).

Skipped are only the DEFINING positions: class name, slot names, and the `:reader` / `:writer` / `:accessor` / `:initarg` / `:allocation` / `:documentation` values. Everything that can hold an expression or a TYPE name still scans — superclass list, `:initform`, `:type`, every class option including `(:default-initargs ...)` and a condition's `(:report ...)`. A `(:metaclass ...)` form is walked whole (its unknown slot options are initargs the metaclass protocol EVALUATES). A malformed slot spec falls back to the whole-form walk.

Pinned by `#aDefclassDoesNotKeepTheDefunOfTheSameName` and `#aSlotAccessorNameIsADefinitionAndAnInitformIsStillCode`. Knock-on: `geom::%vertex-extremes` can now ARM the JVM geom kernel bridge (`.kb/geom.md`).

## Safety valves

- Analysis runs on a `PackageResolver.resolveProgram` copy (index-aligned 1:1), so `in-package`/nickname/bare-exported spellings match canonical names; definition NAMES are read from that copy too. Removal is by index on the pre-resolution list, so surviving forms are byte-identical.
- The pass first runs `LispMacroExpander.flattenTopLevel` (`UserMacroExpander` flattens BEFORE expanding and nothing between it and the pruner flattens again, so without this the defuns cl-postgres' `deferror`/`define-message` macros emit inside a `progn` are all roots). Byte-identity relaxes to "byte-identical, progn-spliced".
- **Bail (prune nothing)** when a runtime `load`/`require` survives the `LoadInliner`, when resolution throws, or when no prunable definition is present. **The bail is a cliff, not a gradient**: ONE quoted `'load` anywhere reverts the whole optimization with no diagnostic. No vendored library contains one today.
- Under `--dynamic`/`--no-prune` the CLI runs `LibraryDefunPruner.stripSystemMarkers` instead, so those paths emit the artifact they emitted before provenance markers existed.

**Documented limitation**: a program that forges a library function's qualified name at runtime from computed strings (no textual/string-literal occurrence) and invokes it via `eval`/`apply`/computed `fboundp` gets the ordinary "undefined function" error; `--no-prune`/`--dynamic` restores everything. Loud, never silent wrong output. Deliberately NOT a bail condition: `eval`/`apply`/`boundp` occurrences do not disable pruning — quoted symbols and string literals count as references instead.

Pinned by `LibraryDefunPrunerTest` (closure, RNG-seed defparameters, vec:aset edge, `#'`/quoted/string references, in-package resolution, load bail, usocket exclusion, order preservation; third-party: provenance-survives-macro-expansion, the macro-produced `progn`, the exported-but-dead tripwire, `'#:`/whole-string designators, the declaim rule, the built-in-dependency exclusion, the vendored cl-ppcre) plus the corpus tests' behavior identity + constant-pool headroom guard (<= 52,000) and the 12 `AsdfLibraryE2eSupport` subclasses.

## Third-party provenance

`LoadInliner.spliceSystem` brackets everything it splices with `(%begin-system "NAME")` / `(%end-system)` — the `%push-package` marker idiom (`LispNames.BEGIN_SYSTEM`/`END_SYSTEM`, consumed by `PackageResolver.resolve`, dropped by the pruner). **The provenance must ride IN the form list**: an identity map dies at `UserMacroExpander.expand`, which returns a FRESH cons for any form containing a macro call (and `JsonLibrary.process` rebuilds every form in a json-using program); an index map dies because five passes between the inliner and the pruner insert at the front, expand 1->N, delete, and splice N->M.

Brackets NEST with `:depends-on`, innermost wins. A `BuiltinSystems` splice (usocket, uiop, closer-mop, flexi-streams, ...) gets its OWN bracket so it does not inherit a dependent third-party system's prunability — without it `USOCKET::%USOCK-RESIGNAL` is pruned and a cl-postgres program dies with `Cannot compile: USOCKET::%USOCK-RESIGNAL` at the `with-*` guard expansion. Unbalanced brackets disable third-party pruning rather than guess.

### The CLOS definition kinds are candidates too (third-party provenance only)

`defclass`/`defgeneric`/`defmethod`/`define-condition`/`defstruct` used to be unconditional roots; the module shakers were RIGHT to keep an unreferenced CLOS-heavy library's methods, so only this pass could collect them. `Candidates` in the pruner; the name summaries live BESIDE the expansions they mirror (`LispMacroExpander.defstructDefinedNames`/`classDefinedNames`/`prunableGenericName`/`defmethodSpecializerNames`) and must move with them.

- **Keyed kinds**, kept iff ANY defined name is referenced from a kept form: `defclass`/`define-condition` under the class name + every `:reader`/`:accessor` name; `defstruct` under the struct name, every constructor (BOA ones by their own names), the predicate, the copier and the accessors — including accessors over `:include`d slots resolved through the candidate chain (`:include` parents outside the candidate set root the child); `defgeneric` under its (setf-normalized) generic name. Generated names carry BOTH colon spellings (no export oracle here).
- **`defmethod` is gated, per method**: the GENERIC gate (some spelling of the generic name is live) plus one SPECIALIZER gate per required parameter naming a candidate class/condition/struct, satisfied when an INSTANTIATOR name is live (the class name; a struct's name or constructors). Soundness: an instance can only be made through a reference the scan sees (`make-instance 'c`, `error 'c`, a constructor call), and a live SUBCLASS keeps its ancestors live textually. When a kept form is scanned its OWN keys are excluded from the references it contributes, or an accessor-kept class would read as instantiable.
- **The generic gate is absent for a CL protocol name** (in `PackageRegistry.CL_SYMBOLS`): `initialize-instance`/`shared-initialize`/`print-object`/`close`/... are called by SYNTHESIZED expansion code with no textual reference, so those methods are kept on the specializer gate alone.
- **The specializer gate applies only when the generic's method set stays closed**: an OWNED generic (a live name keeps a dispatcher even when every method is gated away) or a CL protocol name. A METHOD-ONLY local generic — `(defmethod (setf title) ...)` with no defgeneric — keeps its methods once the name is live, or a kept call site compiles against no definition.

**What stays a root, each for a stated reason**: a `defclass` with `(:metaclass ...)` (the ensure-class driver runs user `:around` code at load time); EVERY class when the program mentions `class-direct-subclasses`/`%class-direct-subclasses` (cl-dbi's `find-driver` reaches the dbd-postgres driver class through subclass ENUMERATION plus a forged string, so name-level reachability is unsound for classes then); the top-level `let`-over-`defmethod` idiom (not a definition form — cl-ppcre's `build-replacement-template`, whose initform calls `create-scanner` at load time); `deftype` (`(satisfies F)` expands to a literal `(F value)` call). CLOS candidates need no initform-purity judgment: a slot `:initform`/`:default-initargs` runs at INSTANCE creation.

**The name-template rule**: sxql resolves its struct constructors as `(symbol-function (intern (concatenate 'string "MAKE-" (symbol-name name) suffix) package))`, spelled nowhere. A `(concatenate 'string ...)` or `(format nil "..." ...)` argument of `intern`/`find-symbol` becomes a TEMPLATE (literal pieces literal, everything else `.*`; every format directive is a hole), and every third-party member name it can produce counts as referenced. A piece-less assembly stays the documented computed-name carve-out; the template fires only from KEPT forms. Applies to every third-party candidate kind, defuns included.

Behavioral pins for the CLOS rules: the full `ci-spec.yaml` native run, the 12 `AsdfLibraryE2eSupport` subclasses, `MitoE2eTest` (live PostgreSQL), `ClPostgresE2eTest`. Pruner-side: `unreferencedClosDefinitionsArePrunedAndTheirDefunClosureWithThem`, `aMethodOnALiveGenericSpecializingADeadClassIsPruned`, `aLiveSubclassKeepsTheMethodsOnItsSuperclass`, `aProtocolMethodIsGatedByItsSpecializerAlone`, `anAccessorReferenceKeepsTheDefiningClassButProvesNoInstance`, `structGeneratedNamesEachKeepTheDefstruct`, `anIncludingStructsInheritedAccessorKeepsIt`, `aConditionIsKeptByItsSignallingReferenceOrItsReader`, `aMetaclassDefclassStaysARoot`, `aSetfMethodIsKeyedUnderItsPlaceName`, `aMethodOnlySetfGenericKeepsItsMethodOnThePlaceReferenceAlone`, `aMethodOnlyLocalGenericKeepsItsMethodsOnceTheNameIsLive`, `aForgedNameTemplateKeepsEveryDefinitionItCanProduce`, `subclassEnumerationRootsEveryClass`, `defgenericInlineMethodsFallAndStayWithTheGeneric`. `SxqlE2eTest` pins the template rule end to end.

**Re-evaluation triggers**: a library staging its forged name through a variable (`(let ((n (format ...))) (intern n))`) escapes the one-hop template scan; a string-assembly spelling other than `concatenate`/`format nil` (`uiop:strcat`) needs its own arm; the `let`-over-`defmethod` root is the residual anchor on the cl-ppcre zero-reference probe — gate it like a method group if a real program needs those bytes.

`defmacro`, `define-compiler-macro`, `define-modify-macro`, `defsetf`, `define-setf-expander`, `macrolet` need no rule: `UserMacroExpander` registers and drops them first (0 occurrences post-expansion in every corpus).

## The dead-branch stages

Two mechanisms inside `prune()` removing BRANCHES of kept third-party definitions so the references inside them stop anchoring subtrees. chipz needs both to lose its bzip2 decoder: `make-dstate`'s `case` arm names `make-bzip2-state` (Stage A), `decompress-fun-for-state`'s `typecase` arm names `%bzip2-decompress` (Stage B). A program using neither shape is byte-identical.

### Stage A — `ConstantCaseArmPruner`

In `eval`, called from `prune()` right after package resolution: deletes a `case`/`ecase` arm inside a third-party form when NO value its subject can take may `eql` any of the arm's keys.

- Monotone least fixpoint over per-REQUIRED-parameter VALUE sets (constants: symbols/integers/characters; NON-KEY: struct instances / function objects, which can never `eql` a literal key; TOP otherwise), flowing through direct calls, `funcall`/`apply` of a literal `#'f`/`'f` (spread positions TOP), `let`/`let*` bindings and expression tails (`case`/`cond`/`if` join their REACHABLE arm tails; a tail `(error ...)` contributes nothing — that is what turns `make-inflate-state`'s `(case f ...)` value into `{GZIP}` and folds the second-hop zlib/deflate arms, taking adler32 with them).
- Calls to a `defgeneric` family join into EVERY method's parameters — dispatch is not modeled, only widening.
- A name escapes (parameters AND return TOP) on quoted data, a string spelling it, `#'f` outside `funcall`/`apply` head position, a `case` key, or a shadowing `flet`/`labels` local.
- Deleting a never-matching arm preserves EVERY execution (an `ecase`'s deleted keys still fall to its error); the one defeat is the computed-name forgery, landing on the `(t (error ...))` arm.

**Stage A flows through struct SLOTS too.** chipz stores the format symbol into `inflate-state`'s `data-format` slot through the BOA constructor `(%make-inflate-state f)` and dispatches on the slot READ inside `%inflate`'s labels, so the parameter-only analysis stopped one hop short. Every `defstruct` now contributes a per-SLOT value set, shared along the `:include` chain:

- a keyword-constructor call maps `:slot` arguments; a BOA constructor maps its REQUIRED parameters to same-named slots (any `&`-marker widens every non-required slot to TOP); a defaulted slot joins its INITFORM's value; `(setf (accessor x) v)` joins the written value while RMW places (`incf`/`push`/`ldb`/... on the slot place) and `(setf (slot-value ...))` widen. A slot read answers with the join over every store the program can perform.
- Parse: `LispMacroExpander.defstructSlotFlow`, mirroring `expandDefstruct`'s option grammar (a `:type` vector layout, whose storage plain `aref` can write, marks the summary opaque = all slots TOP).
- Escapes: an accessor or constructor spelling in quoted data/strings, `#'(setf accessor)`, `with-slots`/`with-accessors`, a `#S` literal, a computed-class `(make-instance ...)`, and a runtime `read`/`read-from-string` (which can construct `#S` syntax no visible store performed — a whole-program cliff on the slot tracking only).
- What sheds the BYTES behind a dead labels-state arm is the expansion-time drop of unreferenced locals (`.kb/flet-labels.md`): the arm deletion removes the `#'state` reference, the expansion stops constructing the closure, and the shakers collect its body.

**Place semantics are load-bearing in `poisonPlace`, in BOTH directions.** A cons place mutates an object and leaves value classifications intact (`(setf (dstate-checksum state) ...)` must NOT poison STATE — poisoning every cons place costs the whole chipz fold, since `make-inflate-state`'s returned STATE goes TOP and the recursive `apply` feeds TOP into `decompress`'s format parameter). But the RMW places `expandSetf` lowers onto an INNER place — `ldb`/`mask-field` (arg 2), `getf` (arg 1), `the` (arg 2), `values` (each subplace) — DO assign the variable inside: cl-postgres's generated `read-uint4` uses `(setf (ldb (byte 8 24) result) ...)`, and treating that as object mutation folded `authenticate`'s ecase arms 3/10 away (`ECASE: no clause matches 10`, caught by `ClPostgresE2eTest`). **`poisonPlace` mirrors `expandSetf`'s case list; a new RMW place family must join both in the same commit.** Pinned by `aReadModifyWritePlaceKeepsTheCaseSubjectUnknown`.

### Stage B — instantiator-gated `typecase`/`etypecase` arms

`GatedArm` / `GateContext` in the pruner: an arm whose clause head names a candidate struct/class contributes its references only once an INSTANTIATOR is live — same soundness argument, monotone re-join and `gateBySpelling` table as the defmethod specializer gate. An arm still closed at the end is DELETED from the surviving form (its body may name pruned definitions — `#'chipz::%bzip2-decompress` — that would not compile). Deliberately NOT extended to `case`: a case key is a symbol and needs no instantiator; Stage A is the sound mechanism there. Gating applies only to prunable-system provenance, so user code is never rewritten.

Both stages rewrite through `ConstantCaseArmPruner.FormRewriter`: clause conses deleted by identity with a lockstep walk over the pre-resolution/resolved twins, `LispCons.rebuilt` + `SourceProvenance.inherit` per spine cell (`.kb/source-positions.md`), original subtree kept on any structural mismatch. Byte-identity relaxes to "byte-identical minus proven-dead arms".

Tests: `aCaseArmNoCallerConstantCanReachIsFoldedAndItsTreeWithIt`, `aCaseArmSurvivesWhenTheDispatchArgumentEscapes`, `theCallerConstantFlowsThroughAGenericAndApplyIntoTheCaseFold`, `aTypecaseArmOnAnUninstantiatedStructIsDeletedAndItsBodyPrunedWithIt`, `aTypecaseArmOpensWhenAnInstantiatorOfItsHeadGoesLive`; slot flow by `aCaseArmNoStoredSlotValueCanReachIsFolded`, `aDefaultedSlotContributesItsInitformValue`, `aSetfOfTheSlotJoinsTheWrittenValue`, `aWriteThroughAChildConstructorReachesTheParentAccessorsRead`, `aReadModifyWriteOnTheSlotPlaceKeepsEveryArm`, `anEscapedAccessorNameKeepsEveryArm`, `aRuntimeReadStandsTheSlotTrackingDown`. `ChipzE2eTest` pins it end to end.

**Re-evaluation triggers**: Stage A's binder model walks `loop`/`macrolet`/`symbol-macrolet` bodies under an opaque env (every name TOP), and `handler-case` tails are TOP — model them if a library dispatches a caller-constant through one. A `defgeneric` whose method set could be narrowed by dispatch (`compiler.ArgumentShapes`) would sharpen the join. Stage B's gate list is `Candidates`' `gateBySpelling`.

## A third-party variable definition needs a pure initform

Pruned only when its initform is provably a pure value computation (`hasPrunableInitform`/`isPureValue`: a literal, a variable read, a `quote`/`function`, or a call to a small allocation/arithmetic/reader set, with pure arguments — deny by default). This is the one place the pass could produce SILENT wrong output: dropping a definition drops its initform, and a `(defvar *registered* (register-all-types))` nobody reads would lose the registration. Every dead variable in the vendored corpus IS pure (the `(setf (gethash oid *sql-readtable*) ...)` registration idiom lives in separate top-level forms, which are roots naming their table textually); the guard keeps `md5::*t*`, whose `loop` the judgment cannot see through. BUNDLED libraries keep the unconditional rule they were audited under.

## The matching rule

Member-level matching does NOT let a `defpackage` `:export` keyword anchor a library's API: the scan runs on the resolved copy, where `PackageResolver.resolveDefpackage` has already replaced the form with `(quote PKG)`, so the clause is physically absent. **Load-bearing**: 62% of the dead cl-postgres defuns are exported, so if `resolveDefpackage` ever preserved its clause list the yield would drop 38% -> 14%. `anExportedButUnreferencedThirdPartyFunctionIsStillPruned` is the tripwire. (An earlier collapse came from an unbounded `endsWith` test — `"CL-PPCRE::PARSE-STRING"` ends with `"STRING"` — which no boundary-respecting rule reproduces.)

**The substring rule is NOT extended to third-party names**: over the vendored trees its only hits there are docstring coincidences ("...use md5sum-string instead..." keeps `md5:md5sum-string` and transitively 16 more). Third-party names get exact / `#:`-stripped / whole-string hash lookups, O(1) per literal; the ~230-name substring scan stays. If a library ever bundles megabytes of literals AND the substring scan is widened, an array-based Aho-Corasick was prototyped at ~14x the loop's speed with a byte-identical hit set.

**Keywords deliberately do not widen** (re-evaluation trigger): `(string :foo)` is as valid a designator as `(string '#:foo)`, so this is a judgment. Reversing it rescues exactly ONE definition corpus-wide while colliding with 7 unrelated keyword spellings (`:of-type` is the LOOP keyword, `:nfc`/`:nfd` are uax-15 API keywords). One line in `collectReferences`.

## Standing relative to `--optimize`

`--optimize` cannot substitute: the WASM/JVM shakers root at exports, and a defun the funcall-dispatch gate keeps dispatchable (`.kb/optimize-dead-code-elimination.md`) stays reachable through the ladders. Pruning at the AST level removes a definition regardless of the gate, and shrinks the UNOPTIMIZED artifact too.

**A "dead top-level `let`" rule was measured and rejected**: `definitionName` returns null for a `let`, so a defun inside one is not a definition at all; across every loadable library the idiom occurs 14 times with only TWO dead blocks (cl-ppcre's and cl-who's `hyperdoc-lookup`), worth 771 bytes of a 1.55 MB module, and neither passes `UserMacroExpander.isPure` (which rejects `loop` and any user call). A top-level `let` is STILL opaque to the pass.

## The constant-pool dedup

`am.ik.jvm.ConstantPool.add` keys every entry by its serialized bytes (tag + payload) in a `HashMap` and returns the existing `Constant` on a hit. A composite entry (Class/String/NameAndType/Field-/Methodref) embeds the u2 indexes of its already-deduplicated components, so structural sharing cascades — identical Methodrefs collapse after Utf8 dedup. `addLong`/`addDouble` pass a `twoSlots` flag into the shared `add` so a cache hit does not double-count the second slot; doubles key by `doubleToLongBits` (`-0.0` and `0.0` stay distinct, NaNs share their canonical pattern). Duplicates are legal in the class format, so dedup needs no flag. `JvmClassShaker` is unchanged (its compaction is sharing-agnostic). Nothing in `am.ik.jvm`/`codegen.jvm` predicts "the next index will be `size()+1`" (grep-verified) and `Constant` is immutable, so returning a shared instance is safe.
