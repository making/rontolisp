# Library defun pruning (AST tree-shaking) + constant-pool deduplication

Two compile-path size mechanisms that landed together for todo-118 (the ci-spec
corpus class had reached 65,520 of the 65,534 constant-pool ceiling):

1. **`LibraryDefunPruner`** (`am.ik.rontolisp.eval`): an AST-level pre-pass that
   drops spliced library definitions unreachable from the user program, before
   the compilers' Pass 1.
2. **`ConstantPool` deduplication** (`am.ik.jvm`): the pool builder now returns
   the existing entry when an identical constant is added again.

## Why both

The library pre-passes (`LinalgLibrary.process` &c) splice each library
**whole** -- linalg.lisp alone is ~102 defuns + 3 defparameters, vec.lisp 70,
json.lisp 29+2 wrappers, url.lisp 22 -- and the `--optimize` shakers can only
trim after the full class/module has been serialized once. Pruning fixes that
root cause for every program that uses a library. But measurement on the corpus
showed the pool was dominated by something else entirely: **duplicate
entries** (the builder never deduplicated -- 30,224 of 64,812 entries were
byte-identical repeats; the Utf8 `"t"` alone appeared 4,646 times). Corpus
numbers, plain (un-optimized) compile:

| stage | constant-pool entries |
|---|---|
| before todo-118 | 65,520 / 65,534 (14 free) |
| + LibraryDefunPruner | 64,812 |
| + ConstantPool dedup | **5,647** (91% headroom) |

The corpus only gains ~700 entries from pruning because ci-spec is the feature
catalogue -- it genuinely reaches 163 of the 230 spliced definitions. A typical
program keeps a handful, so the pruner's real win is ordinary artifact size
(and WASM module size, where there is no constant pool but every dead defun
still costs code bytes).

## The pruner

`LibraryDefunPruner.prune(program)` runs at the END of the compile-path splice
chain. Call sites:

- `RontoLispCli.compileToFile` -- after the conditional `VecLibrary.process`,
  skipped under `--dynamic` and `--no-prune` (both flags in `CliOptions`;
  `--no-prune` is in `noValueKeys`, remember the todo-92 dead-flag lesson);
- `RontoPlayground.compileJvm`/`compileWasm` (web playground, `-Pweb`);
- `JvmClassShakerCorpusTest` / `WasmTreeShakerCorpusTest` (they mirror the CLI
  pipeline; the shakers now decode exactly what the CLI emits -- the per-backend
  unit tests keep full-library codegen coverage).

- `AsdfLibraryE2eSupport.compileProgram` -- the 12 real-library E2E subclasses
  (cl-ppcre, ironclad, uax-15, jzon, md5, cl-base64, cl-who, ...) each exercise
  their own API on the JVM and both WASM backends WITH the pruner, which is the
  coverage for pruning a real third-party tree. It was deliberately absent while
  the scope was rontolisp's own libraries (a provable no-op there); it went in
  with the third-party extension, and adding it alone was verified green first.

NOT in: the interpreter (lazy-loads libraries whole at runtime), the
per-library compiler unit tests (they deliberately compile the full splice).

**Prunable set, part 1 -- rontolisp's own libraries**: top-level
`defun`/`defparameter`/`defvar`/`defconstant` forms whose name is
defined by linalg, vec, json (+ its `#'` wrapper defuns), url, or the prelude
(`equalp`/`string<`/... -- note `LispPreludeLibrary.process` selects the entries
to splice to a fixpoint, so a prelude defun pulled in only by ANOTHER prelude
defun, like the string comparison family's `%string-compare`, is present here
and stays reachable through the kept caller). Collected once from the libraries' `forms()` (see the
package-private `JsonLibrary.wrapperForms()` / `LispPreludeLibrary.names()`
accessors). **usocket is excluded entirely**: its `with-*` built-in macros
(`LispMacroExpander`) synthesize `usocket:socket-close` /
`usocket::%usock-guard` / `%usock-resignal` calls that are not textually
present in the pre-expansion AST, and it is only ~13 defuns.

**Prunable set, part 2 -- an ASDF-spliced third-party tree**: the same four
definition kinds, for every form `LoadInliner` spliced for a system. See
"Third-party provenance" below for how the pass knows, and for the definition
kinds that stay roots.

**Reachability (carve-out semantics, user-confirmed 2026-07-12)**: a reference
is ANY occurrence of the name anywhere in a kept form -- operator position,
argument position, quoted data, `(function ...)` -- PLUS any string literal
containing a prunable name as a substring (keeps `(intern "linalg:norm")` /
`read-from-string` idioms working). Fixpoint from the roots = every top-level
form that is not a prunable library definition (the user program is never
pruned). One synthetic edge is hardcoded: `vec:aref` also references
`vec:aset`, because `(setf (vec:aref ...))` expands to `vec:aset`
(`LispMacroExpander.expandSetf`) AFTER the pruner runs -- the only place in the
whole codebase where a library-qualified name is synthesized rather than
written (verified by grep; re-verify if a new built-in macro expansion ever
emits a `linalg:`/`vec:`/`rontolisp::%json`/url/prelude name).

Two spellings widen this, for THIRD-PARTY names only (see "Third-party
provenance" for why the substring rule does not): an uninterned `'#:foo`
designator and a string literal whose WHOLE content is a definition's canonical
or member name. Both are hash lookups, not scans.

A `set-dispatch-macro-character` HOOK is not a reference source either
(`LispMacroExpander.isReadtableHookRegistration` / `isDeadReadtableHook`, and the
expansion drops the same argument rather than evaluating it): rontolisp's reader
is not readtable-driven, so the registration is a no-op and nothing can call the
hook back. A `#'name` or `(lambda ...)` argument is a pure reference, so dropping
it has no other effect; a hook argument that is a CALL still counts, because a
call can do something else too. Worth exactly one definition -- ironclad's
`array-reader` for `#@` -- and that one mattered out of all proportion: its body
was the ONLY `read` in a whole postmodern program, so pruning it also stops the
embedded reader runtime from being emitted (`.kb/optimize-dead-code-elimination.md`).

A top-level `declaim`/`proclaim` is NOT a reference source. Both expand to
`LispNil` on every backend (`LispMacroExpander.expandDeclaim`/`expandProclaim`),
so nothing they name can be called through them, but `(declaim (inline F))` /
`(declaim (ftype (function ...) F))` would otherwise anchor F. The FORM STAYS in
the program -- `SpecialVarCollector.collectForm` reads a top-level
`(declaim (special ...))` -- only its symbol occurrences stop counting. Worth 26
definitions in the ironclad slice and 98 in the cl-postgres stack.

**Safety valves**:

- analysis runs on a `PackageResolver.resolveProgram` copy (index-aligned 1:1),
  so `in-package`/nickname/bare-exported spellings match canonical names.
  Definition NAMES are read from that resolved copy too (a third-party
  `(defun scan ...)` under `(in-package :cl-ppcre)` is only `CL-PPCRE:SCAN`
  there); removal happens by index on the pre-resolution list, so surviving
  forms are byte-identical (the `PackageResolverTest` fixed-point invariants are
  untouched);
- the pass first runs `LispMacroExpander.flattenTopLevel`, so a `progn` a macro
  produced does not hide its definitions. `UserMacroExpander` flattens BEFORE
  expanding, and nothing between it and the pruner flattens again, so without
  this the 71 defuns cl-postgres' `deferror`/`define-message` macros emit inside
  a `progn` are all roots. Every backend flattens as the first step of
  `compile()`, so this only aligns the pruner with what is actually compiled --
  but it does relax "surviving forms are byte-identical" to "byte-identical,
  progn-spliced";
- bail (prune nothing) when a runtime `load`/`require` survives the
  `LoadInliner` (loaded code can call anything by name), when resolution throws
  (the compiler reports it), or when no prunable definition is present. NOTE the
  bail is a cliff, not a gradient: ONE quoted `'load` anywhere reverts the whole
  optimization with no diagnostic (measured on a linalg program: 218,886 ->
  567,014 bytes). No vendored library contains one today (grep-verified), but the
  surface grew with the third-party extension;
- the CLI skips the pass under `--dynamic` (late binding resolves names at
  runtime) and `--no-prune` (pure escape hatch, no other codegen change), and
  runs `LibraryDefunPruner.stripSystemMarkers` instead so those two paths emit
  the artifact they emitted before the provenance markers existed (verified
  byte-identical on the cl-ppcre program).

**Documented limitation**: a program that forges a library function's qualified
name at runtime from computed strings (no textual/string-literal occurrence)
and invokes it via the eval family (`eval`/`apply`/computed `fboundp`) gets the
ordinary "undefined function" error for a pruned function. `--no-prune` or
`--dynamic` restores every library definition. This mirrors standard AOT
tree-shaking; the failure is loud, never silent wrong output. Deliberately NOT
a bail condition: `eval`/`apply`/`boundp`/... occurrences do not disable
pruning (the ci-spec corpus uses all of them and still prunes) -- quoted
symbols and string literals are counted as references instead.

Pinned by `LibraryDefunPrunerTest` (closure, RNG-seed defparameters,
vec:aset edge, `#'`/quoted/string references, in-package resolution, load
bail, usocket exclusion, order preservation; and for third-party trees: the
provenance-survives-macro-expansion case, the macro-produced `progn`, the
exported-but-dead tripwire, `'#:`/whole-string designators, the declaim rule,
the built-in-dependency exclusion and the vendored cl-ppcre) plus the corpus
tests' behavior identity + constant-pool headroom guard (<= 52,000) and the 12
`AsdfLibraryE2eSupport` subclasses.

## Third-party provenance: how the pass knows, and where the line is

`LoadInliner.spliceSystem` brackets everything it splices for a system with
`(%begin-system "NAME")` / `(%end-system)`, the same in-stream marker idiom as
`%push-package` (`LispNames.BEGIN_SYSTEM`/`END_SYSTEM`, consumed by
`PackageResolver.resolve`, dropped by the pruner). **The provenance has to ride
IN the form list**, and that is the whole reason for the markers rather than a
side table:

- an identity map dies at `UserMacroExpander.expand`, which returns a FRESH cons
  for any form containing a macro call (and `JsonLibrary.process` rebuilds every
  form in a json-using program) -- i.e. it would silently lose provenance for
  exactly the library defuns worth pruning, with no way to detect the loss;
- an index map dies too: five passes between the inliner and the pruner insert
  at the front, expand 1->N, delete, and splice N->M.

Brackets NEST with `:depends-on` and the innermost wins; a `BuiltinSystems`
splice (usocket, uiop, closer-mop, flexi-streams, ...) gets its own bracket
precisely so it does NOT inherit the prunability of a third-party system that
depends on it -- measured: without that, `USOCKET::%USOCK-RESIGNAL` is pruned
and a cl-postgres program dies with `Cannot compile: USOCKET::%USOCK-RESIGNAL`
at the `with-*` guard expansion. Unbalanced brackets disable third-party
pruning rather than guess.

**The CLOS definition kinds are candidates too (2026-08-09; third-party
provenance only).** `defclass`/`defgeneric`/`defmethod`/`define-condition`/
`defstruct` used to be unconditional roots, and the cost was measured at
823,589 B for one loaded-but-unreferenced CLOS-heavy library (cl-ppcre on the
routed-Worker probe) -- every method body survived into codegen, was
materialized dispatchable, and the module shakers were RIGHT to keep it, so
only this pass could collect it. Now (`Candidates` in the pruner; the name
summaries live BESIDE the expansions they mirror --
`LispMacroExpander.defstructDefinedNames`/`classDefinedNames`/
`prunableGenericName`/`defmethodSpecializerNames`, and must move with them):

- **keyed kinds** -- kept iff ANY defined name is referenced from a kept form:
  `defclass`/`define-condition` under the class name + every `:reader`/
  `:accessor` name (a reference to an accessor needs the class kept for the
  reader generic to exist); `defstruct` under the struct name, every
  constructor (BOA ones by their own names), the predicate, the copier and the
  accessors -- including accessors over `:include`d slots, resolved through the
  candidate chain (`.include` parents outside the candidate set root the
  child); `defgeneric` under its (setf-normalized) generic name. Generated
  names carry BOTH colon spellings (the pruner has no export oracle).
- **`defmethod` is gated, per method**: the GENERIC gate (some spelling of the
  generic name is live) plus one SPECIALIZER gate per required parameter that
  names a candidate class/condition/struct -- satisfied when an INSTANTIATOR
  name of that definition is live (the class name; a struct's name or
  constructors). The soundness argument: an instance the method could apply to
  can only be made through a reference the scan sees -- `make-instance 'c`,
  `error 'c`, a constructor call -- and a live SUBCLASS keeps its ancestors
  live textually (its defclass form names them), so applicability propagates
  up the inheritance chain for free. When a kept form is scanned, its OWN keys
  are excluded from the references it contributes -- a defclass header spells
  its own name and accessors, and counting those would make an accessor-kept
  class read as instantiable.
- **the generic gate is absent for a CL protocol name** (member in
  `PackageRegistry.CL_SYMBOLS`): `initialize-instance`/`shared-initialize`/
  `print-object`/`close`/... are called by SYNTHESIZED expansion code with no
  textual reference (`expandMakeInstance`, the printer hook, `with-open-*`'s
  close), so such methods are kept on the specializer gate alone -- ironclad's
  `initialize-instance :after` methods leave exactly when their classes do.
- **the specializer gate applies only when the generic's method set stays
  closed**: for an OWNED generic (its defgeneric is a candidate, so a live
  name keeps a dispatcher even when every method is gated away) and for a CL
  protocol name (the built-in is the last resort). A METHOD-ONLY local generic
  -- `(defmethod (setf title) ...)` with no defgeneric anywhere -- keeps its
  methods once the name is live, or a kept call site/setf place would compile
  against no definition at all.

**What stays a root, each for a stated reason**: a `defclass` carrying
`(:metaclass ...)` (the ensure-class driver runs user `:around` code at load
time -- arbitrary side effects); EVERY class when the program mentions
`class-direct-subclasses`/`%class-direct-subclasses` (cl-dbi's `find-driver`
reaches the dbd-postgres driver class through subclass ENUMERATION plus a
forged string -- no name reference at all -- and `make-instance`s the
metaobject; name-level reachability is unsound for classes then, while defuns,
structs and the method gates keep working); the top-level
`let`-over-`defmethod` idiom (not a definition form -- cl-ppcre's
`build-replacement-template`, whose binding initform calls `create-scanner` at
load time); `deftype` (worth 0-13 definitions corpus-wide, and `(satisfies F)`
expands to a literal `(F value)` call). CLOS candidates need no
initform-purity judgment: a slot `:initform`/`:default-initargs` runs at
INSTANCE creation, not at definition time.

**The name-template rule** (same commit): sxql resolves its struct
constructors as `(symbol-function (intern (concatenate 'string "MAKE-"
(symbol-name name) suffix) package))` -- a name assembled from literal pieces
and computed holes, textually spelled nowhere. A `(concatenate 'string ...)`
or `(format nil "..." ...)` argument of `intern`/`find-symbol` therefore
becomes a TEMPLATE (literal pieces literal, everything else `.*`; every format
directive is a hole), and every third-party member name it can produce counts
as referenced -- `^MAKE-.*$` keeps all of sxql's op/clause/statement
constructors and their defstructs. A piece-less assembly stays the documented
computed-name carve-out, and the template only fires from KEPT forms, so a
pruned `find-constructor` anchors nothing. The rule applies to every
third-party candidate kind, defuns included (the same forge can target a
defun; before this pass widened, defstruct roots merely hid the constructor
case).

**What it is worth** (2026-08-09, `--no-wasi --optimize=size`, node-verified
request-for-request against same-day baselines): every clack Worker shed ~30%
-- `hello-clack` 365,865 -> 248,356 B raw (75,334 gzip), `httpbin-clack`
378,768 -> 264,277, `hello-tiny-routes` 388,925 -> 271,963,
`httpbin-tiny-routes` 403,456 -> 289,068 -- because `lack-util` depends on
`ironclad/core` for its session-id generator, and a Worker that never calls it
still carried ironclad's 29 condition classes, 42 defgenerics and the digest
method surface as roots (29 of the baseline module's 73 class layouts were
ironclad's). `httpbin` (no third-party CLOS) stays BYTE-identical. The full
`ci-spec.yaml` native run (1,300 cases, 4 backends), the 12
`AsdfLibraryE2eSupport` subclasses, `MitoE2eTest` (9/9, live PostgreSQL) and
`ClPostgresE2eTest` are the behavioral pins; `postgres-hello --component
--optimize` moved 2,484,611 -> 2,288,983 (-7.9%). The routed-Worker
zero-reference ceiling this section used to cite is MOSTLY collected: the same
probe's engine now leaves except what the `let`-over-`defmethod` root anchors
(the full-tiny-routes `httpbin` variant is 799,880 B where the /lite build is
289,068).

Pruner-side pinning tests (`LibraryDefunPrunerTest`):
`unreferencedClosDefinitionsArePrunedAndTheirDefunClosureWithThem`,
`aMethodOnALiveGenericSpecializingADeadClassIsPruned`,
`aLiveSubclassKeepsTheMethodsOnItsSuperclass`,
`aProtocolMethodIsGatedByItsSpecializerAlone`,
`anAccessorReferenceKeepsTheDefiningClassButProvesNoInstance`,
`structGeneratedNamesEachKeepTheDefstruct`,
`anIncludingStructsInheritedAccessorKeepsIt`,
`aConditionIsKeptByItsSignallingReferenceOrItsReader`,
`aMetaclassDefclassStaysARoot`, `aSetfMethodIsKeyedUnderItsPlaceName`,
`aMethodOnlySetfGenericKeepsItsMethodOnThePlaceReferenceAlone`,
`aMethodOnlyLocalGenericKeepsItsMethodsOnceTheNameIsLive`,
`aForgedNameTemplateKeepsEveryDefinitionItCanProduce`,
`subclassEnumerationRootsEveryClass`,
`defgenericInlineMethodsFallAndStayWithTheGeneric`; `SxqlE2eTest` is the
end-to-end pin of the template rule (its whole API resolves through
`find-constructor`).

**Re-evaluation triggers**: a library that stages its forged name through a
variable (`(let ((n (format ...))) (intern n))`) escapes the one-hop template
scan -- widen the scan to the binding if one ever does; a string-assembly
spelling other than `concatenate`/`format nil` (`uiop:strcat`, ...) needs its
own template arm; and the `let`-over-`defmethod` root is the residual anchor
on the cl-ppcre zero-reference probe -- gate it like a method group (every
body member a defmethod, binding initforms judged) if a real program class
ever needs those bytes.

`defmacro`, `define-compiler-macro`, `define-modify-macro`, `defsetf`,
`define-setf-expander` and `macrolet` need no rule at all: `UserMacroExpander`
registers and drops them before this pass, measured to 0 occurrences
post-expansion in every corpus.

**A third-party variable definition is pruned only when its initform is provably
a pure value computation** (`hasPrunableInitform`/`isPureValue`: a literal, a
variable read, a `quote`/`function`, or a call to a small allocation/arithmetic
/reader set, with pure arguments -- deny by default). This is the one place the
pass could otherwise produce SILENT wrong output rather than the loud "undefined
function" the carve-out documents: dropping a definition drops its initform, and
a `(defvar *registered* (register-all-types))` whose value nobody reads would
lose the registration. Every dead variable measured across the vendored corpus
IS pure -- the `(setf (gethash oid *sql-readtable*) ...)` registration idiom the
filed item worried about lives in separate top-level `setf`/`set-sql-reader`
forms, which are roots and name their table textually -- so the guard costs
almost nothing (it keeps `md5::*t*`, whose `loop` the judgment cannot see
through) while the 61 dead literal `defconstant`s of the cl-postgres stack still
go. The BUNDLED libraries keep the unconditional rule they were audited under.

**The matching rule, and the two things the filed item got wrong.** The item
predicted that allowing member-level matching would let every `defpackage`
`:export` keyword anchor a library's whole API, collapsing the dead-code figures
from 6/23/34% to 3/4/11%. It does not: the scan runs on the resolved copy, where
`PackageResolver.resolveDefpackage` has already replaced the entire form with
`(quote PKG)`, so an `:export` clause is physically absent. The measured
collapse came from an unbounded `endsWith` test (`"CL-PPCRE::PARSE-STRING"`
ends with `"STRING"`), and no boundary-respecting rule reproduces it. **This is
load-bearing**: 62% of the dead cl-postgres defuns are exported, so if
`resolveDefpackage` ever preserved its clause list the yield would drop 38% ->
14%. `LibraryDefunPrunerTest.anExportedButUnreferencedThirdPartyFunctionIsStillPruned`
is the tripwire.

The item also predicted the prunable set would reach ~3,000 names and need a
trie / Aho-Corasick for the string-literal carve-out. It does not, because the
substring rule is NOT extended to third-party names -- measured over the
vendored trees its only hits there are docstring coincidences ("...use
md5sum-string instead..." keeps `md5:md5sum-string` and, transitively, 16 more
definitions; 34 such names in a 10-system program, not one of them a real
reference). Third-party names get exact / `#:`-stripped / whole-string hash
lookups instead, all O(1) per literal, and the ~230-name substring scan stays as
it was (measured 1.6 ms on the cl-ppcre program). If a future library ever DOES
bundle megabytes of literals AND the substring scan is widened, an array-based
Aho-Corasick was prototyped at ~14x the loop's speed with a byte-identical hit
set -- but nothing today needs it.

**Keywords deliberately do not widen** (re-evaluation trigger): `(string :foo)`
is as valid a designator as `(string '#:foo)`, so this is a judgment, not a
principle. Measured price of reversing it: it would rescue exactly ONE
definition across the whole vendored corpus while colliding with 7 unrelated
keyword spellings (`:of-type` is the LOOP keyword, `:nfc`/`:nfd`/... are uax-15
API keywords). Flip it in one line in `collectReferences` if a library is ever
found dispatching a defun purely off a keyword.

**What it is worth** (2026-07-27, `.class` via the CLI, default vs `--no-prune`,
output byte-identical in every row):

| demo program | before | after |
| --- | --- | --- |
| cl-base64 | 0.0% | **-14.0%** |
| cl-who | -1.3% | **-12.7%** |
| ironclad | -0.3% | **-9.0%** |
| jzon | 0.0% | **-6.2%** |
| uax-15 | -0.2% | **-5.7%** |
| cl-utilities | -0.8% | **-4.5%** |
| md5 | -3.3% | **-4.0%** |
| cl-ppcre | -0.2% | **-2.8%** |
| assoc-utils | 0.0% | **-0.8%** |
| parse-number | -4.1% | -4.1% |
| split-sequence | 0.0% | 0.0% |

`(asdf:load-system :cl-ppcre)` + one `scan`: wasm 1,551,268 -> 1,468,585
(-5.3%), `.class` 2,043,896 -> 1,945,286 (-4.8%), constant pool 6,527 -> 6,282.
The filed item predicted -3.0% from hand-removing cl-ppcre's 13 statically dead
forms; the extra came from `defconstant`, the `declaim` rule and the
`progn` flattening, none of which were in the plan. split-sequence stays 0% and
that is correct -- all 18 of its definitions are genuinely reachable.

`--optimize` cannot substitute for any of this: the WASM/JVM shakers root at
exports, and a defun the funcall-dispatch gate keeps dispatchable
(`.kb/optimize-dead-code-elimination.md`) stays reachable through the ladders —
pruning at the AST level is what removes a definition regardless of how the
gate classifies it, and it also shrinks the UNOPTIMIZED artifact.

### Why the "dead top-level `let`" rule was rejected

A rule was proposed for the top-level `(let ((x ...)) (defun ...))` idiom -- delete
the block when every definition inside it is dead -- on the premise that the pruner
"already knows" uax-15's `get-illegal-char-list` is unreachable. **The premise was
false and the rule was measured and rejected (2026-07-26).** Kept here so the idea
is not re-proposed:

- The pruner could not know it. `definitionName` returns null for a `let`, so a defun
  inside one is not a definition at all; and uax-15 is third-party, which at the time
  was out of scope entirely. The motivating block is also gone -- `eval/Uax15Tables`
  replaces that whole `let` at the source level (`.kb/asdf.md`).
- Across every loadable library (vendored + the quicklisp cache) the idiom occurs 14
  times and only TWO blocks are dead: cl-ppcre's and cl-who's `hyperdoc-lookup`,
  whose bodies are a `loop ... being the external-symbols` that rontolisp already
  lowers to an empty iteration. Deleting both is worth **771 bytes of a 1.55 MB
  module (0.05%)**. Worse, neither passes the existing purity judgment
  (`UserMacroExpander.isPure` rejects `loop` and any user call), so collecting the
  771 bytes would first require widening that allow-list.

Note that a top-level `let` is STILL opaque to the pass -- the third-party
extension changed which forms are prunable, not which shapes are recognized as
definitions. The 771 bytes are still on the table and still not worth it.

## The constant-pool dedup

`am.ik.jvm.ConstantPool.add` keys every entry by its serialized bytes
(tag + payload) in a `HashMap` and returns the existing `Constant` on a hit.
Because a composite entry (Class/String/NameAndType/Field-/Methodref) embeds
the u2 indexes of its already-deduplicated components, structural sharing
cascades -- after Utf8 dedup, identical Methodrefs become byte-identical and
collapse too (that cascade is why the corpus dropped 91%, not just the 43%
that raw Utf8 duplicates accounted for). `addLong`/`addDouble` pass a
`twoSlots` flag into the shared `add` so a cache hit does not double-count the
second slot; doubles key by `doubleToLongBits` (so `-0.0` and `0.0` stay
distinct entries and NaNs share their canonical serialized pattern --
serialization itself is unchanged from before, only the sharing is new).
Duplicates are legal in the class format, so dedup is purely a size
optimization and needs no flag. Every compiled `.class` shrinks;
`JvmClassShaker` still works unchanged (its compaction is sharing-agnostic).

Nothing in `am.ik.jvm`/`codegen.jvm` predicts "the next index will be
`size()+1`" (grep-verified), and `Constant` is immutable, so returning a shared
instance is safe.
