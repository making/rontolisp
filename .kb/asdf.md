# asdf (limited system-definition subset)

An API-compatible mini-ASDF, **not a port**. Shared core `eval/AsdfSystems.java` parses
`defsystem` forms and whole `.asd` files **as plain data — never evaluated**.

Surface: `asdf:defsystem` + `asdf:load-system` + `asdf:test-system`, with component
METAOBJECTS real at run time. `asdf:find-system` answers a memoized `asdf:system` CLOS
instance per name (`eq` across calls); the classes (`component`,
`child-component`/`parent-component`, `module`, `system`, `package-inferred-system`,
`source-file`, `cl-source-file`, `static-file`) are real on every backend so
`typecase`/`typep`/defmethod specializers work; the readers
(`component-name`/`-pathname`/`-children`/`-sideway-dependencies`/`-parent`/`-system`,
`registered-systems`, `*user-cache*` = nil) walk the model. `test-op` is the ONE op with
machinery. **No general CLOS `operate`/`perform`, no `compile-op`/fasl
output-translations.** Docs: `doc/*/guides/asdf-systems.md`,
`reference/functions/asdf-{defsystem,load-system}.md`.

## What a `.asd` may contain

Top-level forms recognized (anything else is a hard error naming the form — deny by
default):

- `defsystem` in any package spelling.
- `register-system-packages` — RECORDED into the loader's package -> system map. Only one
  of real ASDF's two consumers reaches here: a package-inferred system translating a
  `defpackage` dependency into a system name. (The `find-package`-miss autoload needs
  nothing: a package is located by its own `defpackage` and nicknames.)
- `in-package`, `defpackage` — skipped (the system-definition-package header idiom).
- top-level `defparameter` — evaluated into a parse-time env by `evalDataForm` when the
  value is pure data (literals, keyword self-eval, references to earlier defparameters,
  `quote`/`if`/`or`/`and`/`not`). **An IMPURE value does NOT fail the file**: the name is
  recorded as unevaluable (`defineParameter` fills a sibling `unevaluable` name -> reason
  map) and only a later form that actually READS it errors, naming the parameter.
- top-level `progn` — **FLATTENED**: body forms are spliced back onto the worklist so each
  subform hits the same recognizer; nested `progn` recurses, an empty one is a no-op, and an
  unsupported form inside still errors by its OWN name, never `PROGN`'s.
- `(eval-when (SITUATION...) (pushnew :F *features*))` or a bare top-level `pushnew`/`push`
  — see "Feature announcements" below.
- component-class `defclass` and any top-level `defmethod` — see "Component-class surface".

### Options

Ordering and layout:

- `:components` ordered by a stable topological sort of `:depends-on`; `:serial` = implicit
  dep on the previous sibling; `:module` = path prefix with files kept contiguous;
  `:static-file` = ordering-only.
- A SYSTEM-level `:pathname "dir"` prefixes every component (lack.asd's shape: `:pathname
  "src"` once, then bare names). An empty string adds no level.
- A component-level `:pathname "dir"` decouples the component's NAME (its identity in the
  sibling dependency graph) from the path it contributes (quri's `uri-classes` module lives
  in `src/uri/`). On a `:file` the namestring is used verbatim when it carries an extension
  and gets `.lisp` appended otherwise. An EMPTY module `:pathname` adds no directory level.
- **A computed `:pathname` is a hard error** — ASDF-as-data has no pathname machinery.
- A component NAME accepts a string OR a symbol (real ASDF's `coerce-name` downcases a
  symbol), so `(:module :t ...)` names `t/`.
- `locate()` finds `NAME.asd` by *attempting to read* each search dir through `SourceLoader`
  — no existence check, so the browser playground's in-memory loader works. Secondary names
  (`lib/tests`) map to the primary `.asd`.

Features (`parseAsdSource`/`parseDefsystem` take a `reader.Features`: backend features via
`LoadInliner.Ctx` on the compile path, `INTERPRETER` in the evaluator):

- component-level `:if-feature EXPR` keeps the component in the dependency graph but drops
  its files when the expression fails.
- a `:depends-on` entry may be `(:feature EXPR DEPENDENCY-DEF)` (`dependencyName`; dropped
  when EXPR fails). A *surviving* `(:require MODULE)` is a hard error.
- component-level `:depends-on` also accepts `(:feature ...)`; elements past the dependency
  are IGNORED, matching `resolve-dependency-combination`.
- `(:version NAME "1.2.3")` entries resolve to the plain dependency; the constraint is NOT
  checked (`:version` is parsed-and-ignored metadata).
- `#:lib` designators are stripped in `designator`/`symbolName`.
- **`:rontolisp-features (...)`** — a rontolisp-added option, parsed by
  `AsdfSystems.declaredFeatures` **before** the option loop and recorded on
  `LispSystem.features()`. Each loader WIDENS its own base set with it (`Features.with(...)`,
  **additive only**, so nothing can switch a backend feature off): the interpreter reads
  components with `Features.INTERPRETER.with(system.features())` (the 4-arg
  `LispEvaluator.loadFile` overload) and `LoadInliner.spliceSystem` builds a `Ctx` copy with
  `ctx.features().with(...)` for its own components only (the record copy shares every
  mutable registry). A DEPENDENCY keeps the outer set. Exists because a real `.asd` pushes a
  feature at LOAD time while component conditionals resolve at READ time, and a push is only
  read back inside the file that writes it (`.kb/reader-features.md`).
- **`:defsystem-depends-on`** parsed (`dependencyNames`, the same entry shapes) and recorded
  on `LispSystem.defsystemDependsOn`; both loaders resolve it through the ordinary
  shim/built-in/real ladder BEFORE the system's own `:depends-on`. Deliberately NOT merged
  into `dependsOn` — real ASDF does not make it a sideway dependency, so
  `component-sideway-dependencies` must not list it (pinned on all four backends).
- `:class :package-inferred-system` — the ONE `:class` value implemented; see below.
  `:default-component-class` — see "Component-class surface".

Metadata:

- **`IGNORED_OPTIONS` — never resolved, never mentioned**: `:name :description
  :long-description :version :author :maintainer :license :licence :homepage :bug-tracker
  :source-control :mailto :long-name`, plus `:in-order-to`/`:perform`. Nothing reads these,
  so nothing may complain about them. This is most of the `#.` in a real dist (the cl-project
  skeleton's `:long-description #.(with-open-file …)`, `(intern #.(string :run-test-system)
  …)` in `*-test.asd` `:perform` bodies).
- `:version` is on that list even though `asdf:component-version` reads it BACK: what is
  recorded is the value AS WRITTEN when it is a plain STRING; every other spelling (including
  `(:read-file-form ...)`) answers nil. It lives in a `version` slot on `asdf:component`,
  plus a sixth element on the per-backend record.
- **The default direction is deliberate**: `IGNORED_OPTIONS` is the CLOSED list, so an
  option added later is load-bearing without anyone remembering to say so.

### `#.` in a `.asd` — the CONSUMER decides, never the evaluator

- The tolerant lexer (`LispLexer` with `tolerateReadEval`, used only by
  `readAllSkippingReadEval` and `readFirstForm`) re-lexes the raw-skipped datum into a
  `(%read-eval datum)` marker (`LispNames.READ_EVAL`, validated by
  `LispReader.parsesAsExpressions`), or, when it cannot re-lex at all, a
  `(%read-eval-unreadable "RAW TEXT")` marker (`LispNames.READ_EVAL_UNREADABLE`). **One
  datum either way**, so a `#.` inside a plist/alist never shifts the surrounding pairing.
- **Neither marker is resolved where it is read.** `parseDefsystem`'s option loop resolves
  per option (`resolveReadEval`, against the enclosing `.asd`'s defparameter env + path,
  threaded as the private `AsdContext`; the other `parseDefsystem` callers —
  `LoadInliner`/`LispEvaluator` for a `defsystem` in a `.lisp` file — pass `AsdContext.NONE`).
- **Everything outside `IGNORED_OPTIONS` is resolved, and unresolvable is a HARD ERROR**
  naming the `.asd` and the clause: `:depends-on :components :serial :pathname :class
  :rontolisp-features :default-component-class`, the system name, and everything nested
  inside `:components`. A silent nil here drops a dependency or a source file (cl-postgres'
  `(:file #.*string-file*)`, cffi-toolchain's `:if-feature (#.(if (version< …) :or :and))`)
  and surfaces much later as an undefined symbol far from the cause.
- **Do not teach `evalDataForm` `with-open-file`/`uiop:read-file-string`** — a `.asd` is
  parsed as data on purpose. A top-level marker of either kind is ignored whole (the ASDF
  version-guard idiom). Source (non-`.asd`) files support `#.` everywhere
  (`.kb/reader-features.md`).
- Pinned by `AsdfSystemsTest`: `unresolvableReadEvalInIgnoredMetadataIsSilent`,
  `theClProjectLongDescriptionSkeletonIsSilent`, `aPerformBodyReadEvalIsSilent`,
  `anUnresolvableReadEvalIn{DependsOn,Components}IsAHardError`,
  `anUnreadableReadEvalIsSilentInMetadataAndNamedWhereItDecides`,
  `parsesTheClPostgresAsdHeaderShape` — all capturing `System.err`, since "says nothing" is
  half the contract. Also `aTopLevelPrognIsFlattened`,
  `aNestedPrognFlattensRecursivelyAndAnEmptyOneIsANoOp`,
  `anUnsupportedFormInsideAPrognErrorsByItsOwnNameNotPrognsName`,
  `aModuleComponentNameAcceptsASymbolLikeCoerceName`, `aFileComponentNameAcceptsASymbol`,
  `asdDefparameterWithAnImpureValueParsesFineWhenNothingReadsIt`,
  `anImpureDefparameterErrorsNamingItselfOnlyWhenALaterFormReadsIt`,
  `aSystemPathnameIsAPrefixForEveryComponent`,
  `aSystemPathnameComposesWithModulesAndComponentPathnames`,
  `anEmptySystemPathnameAddsNoDirectoryLevel`, `aComputedSystemPathnameIsAHardError`,
  `parseAsdSourceSkipsRegisterSystemPackages`, `parsesTheVerbatimLackAsd`.

### Feature announcements read out of a real `.asd`

`AsdfSystems.collectFeaturePushes` reads a top-level
`(eval-when (SITUATION...) (pushnew :F *features*))` or a bare `pushnew`/`push` and hands it
to `parseDefsystem` as if the system had declared `:rontolisp-features (:F)` — both
spellings land in the same `LispSystem.features()` (`mergedFeatures`, pushes first).

- Scope: pushes accumulate in FILE order and reach only systems defined AFTER them; a
  dependency parsed from its own `.asd` still declares its own.
- Only the announcement shape is accepted inside the `eval-when`; anything else is a hard
  error naming the form. A `(:compile-toplevel)`-ONLY situation list is inert (ASDF `load`s
  a `.asd`, never compiles one). The `*features*` argument must be the symbol itself, so
  `(pushnew :x '(:a :b))` is not an announcement and stays an error.
- A `#+` in the SAME `.asd` sees the push without any of this (`reader.FeaturePushes`).
- **A DEPENDENCY announces features to the system that names it**:
  `BuiltinSystems.declaredFeatures` is read at PARSE time (ahead of the option loop, one
  channel with `:rontolisp-features` and `collectFeaturePushes`), so announced names hold for
  this system's own `:if-feature`/`(:feature ...)` clauses and for reading its components.
  **Only a BUILT-IN system announces** — a real third-party one announces by RUNNING, and a
  `.asd` is never evaluated here. Both `:depends-on` and `:defsystem-depends-on` announce.
  Divergence: here a `:depends-on` announcement ALSO reaches this definition's own
  `:if-feature` clauses (a one-pass parse has no "load between the clauses").
- Pinned by `AsdfSystemsTest.parsesTheFastIoAsdFeatureAnnouncementHeader`,
  `anAnnouncedFeatureReachesOnlyTheSystemsDefinedAfterIt`,
  `aDeclaredRontolispFeatureDoesNotLeakToAnotherSystem`,
  `aDeclaredRontolispFeatureWidensTheBackendSetForThatSystemOnly`, end-to-end by
  `FastIoCircularStreamsE2eTest`.

### The component-class surface

Only two things about a component class can reach a data-only front end: whether an instance
contributes a SOURCE file at all, and which file EXTENSION its name gets. Everything else
upstream subclasses exist for is `compile-op` warning policy, and there is no compile-file
and no fatal warning here, so ignoring it is EXACT. Model:
`AsdfSystems.ComponentClass(source, fileType)` + a per-`.asd` `ComponentClasses` scope.

- **ASDF's own classes are component types and superclasses without a defclass**
  (`BUILTIN_COMPONENT_CLASSES`): `cl-source-file` (`.lisp`, and what a bare `(:file ...)`
  means — `DEFAULT_COMPONENT_CLASS`), `cl-source-file.cl` (`.cl`), `cl-source-file.lsp`
  (`.lsp`), `static-file`, `doc-file`, `html-file` (last three ordering-only).
- **A top-level `defclass` declares a component class** (`collectComponentClass`): every
  superclass must resolve in that scope (ASDF's own or one declared EARLIER in the same
  file), `source` and `fileType` are inherited from the first source superclass, and a
  `(type :initform "cl")` slot overrides the extension. A defclass that is NOT a component
  class (an operation, a condition) stays a hard error.
- **`(defmethod source-file-type ((c CLASS) (s module)) "ext")` sets that class's
  extension**, collected in a PRE-PASS over the file's forms (`collectSourceFileTypes`,
  descending into `progn`) — real ASDF calls that generic long after the file is read, so
  the method's position must not matter. **The CLASS still has to precede its use**; only the
  METHOD is position-free.
- **Every other top-level `defmethod` is tolerated and ignored.** The one deliberately
  opened part of the closed world; safe because the failure it can hide is not silent (a
  method that really moved a file surfaces as the missing file, NAMED). Re-evaluation
  trigger: if a system fails with a confusing missing-file error traceable to an ignored
  method, give that method name its own channel rather than restoring the blanket error.
- **`:default-component-class`** is what a bare `(:file "name")` takes (ASDF's `class-for-type`
  fallback at exactly that spot), readable on the system AND on a module (a module walks up
  to the enclosing default). NOT in `IGNORED_OPTIONS`: its `#.` markers resolve and an
  unknown class is a hard error.
- Pinned by `AsdfSystemsTest.toleratesAClSourceFileSubclassAndLoadsItsComponents`,
  `aSourceFileTypeMethodSetsTheClassExtensionWhereverItSits`,
  `aTypeInitformSlotSetsTheClassExtension`, `theBuiltInSourceFileClassesAreComponentTypes`,
  `aModuleMayRePointTheDefaultComponentClass`, `anUnknownDefaultComponentClassIsAHardError`,
  `aDefclassThatIsNotAComponentClassIsAHardError`,
  `aTopLevelDefmethodOnAnotherNameIsToleratedAndIgnored`, and end-to-end by
  `LispEvaluatorAsdfTest.loadSystemReadsTheExtensionAComponentClassGivesItsFiles` (the
  extension must travel all the way to the READ, not just into `LispSystem.files()`).
- Driving library: **portableaserve** (`aserve.asd`, `htmlgen.asd`, `webactions.asd` all
  parse completely). Where it stops: see "portableaserve" below.

## `:class :package-inferred-system`

The style that replaced hand-listed `:components` upstream (ningle, rove, array-operations,
jose). Every other `:class` value picks a component TYPE that changes how sources load,
which a defsystem-as-data front end cannot honor, so it stays a hard error naming the
clause. Such a system has NO `:components` (one that lists them anyway is an error);
`LispSystem.packageInferredDir` (non-null iff declared; the system's own `:pathname`, `""`
when none) is the whole marker. Rules, all in `AsdfSystems`:

- **A sub-system name is a FILE PATH.** `x/a/b` -> `a/b.lisp` under the PRIMARY system's
  directory + its `packageInferredDir`. The primary is the part before the FIRST slash
  whatever the depth, so `rove/tests/main` resolves under `rove` even though `rove/tests` is
  its own explicit `defsystem` in the same `.asd`.
- **Dependencies are read out of the file's own `defpackage`** (`packageDependencies`):
  every package in `:use`, `:mix`, `:reexport`, `:use-reexport`, `:mix-reexport`, plus the
  FIRST argument of each `:import-from` / `:shadowing-import-from`.
  `:nicknames`/`:shadow`/`:export`/`:intern`/`:documentation` contribute nothing. This is
  the WHOLE dependency graph (ningle.asd's `:depends-on` lists only `"ningle/main"`).
  A file with NO `defpackage`/`uiop:define-package` is a hard error naming the file; forms
  BEFORE the package declaration are skipped (matching
  `asdf/package-inferred-system::file-defpackage-form` — the `(in-package #:cl-user)`
  header is a common style). Only the FIRST package definition form counts.
- **A package name becomes a SYSTEM name** (`packageSystemName`): what
  `register-system-packages` recorded, else the downcased package name.
  `cl`/`common-lisp`/`cl-user`/`common-lisp-user`/`keyword`/`asdf` drop out.
- **Only forms up to the package declaration are read**
  (`LispReader.readFirstFormMatching` with the `defpackage`/`define-package` predicate;
  `readFirstForm` is that with an always-true predicate, tolerant of `#.`). Every source in
  the system is opened here, which is also why the tolerant lexer may not WARN. No provenance
  is recorded either — the real load must claim the positions.
- **Derivation is on demand, whole-closure per call.** Both consumers call
  `inferPackageInferredSystems` only when the requested name is still missing after the
  `.asd` was parsed; it then derives everything reachable in ONE pass. An edge back to an
  already-registered sibling is not followed, so a `defpackage` cycle terminates and is
  reported by the caller's existing `:depends-on` cycle guard.
- Consumers: `LispEvaluator.loadSystem` (which owns the merged `asdfSystemPackages` map) and
  `LoadInliner.spliceSystem` (whose `Ctx` carries it). Coverage: `AsdfSystemsTest` (verbatim
  `ningle.asd`, the nested rove shape, the array-operations `:pathname`, the sibling cycle,
  both skip cases) + `PackageInferredSystemE2eTest` (four backends over
  `src/test/resources/package-inferred-demo`: a nested `x/a/b` sub-system, a
  `uiop:define-package` `:use-reexport` edge, a `register-system-packages` hop).

## Interpreter / compile path / search order

- **Interpreter**: `asdf:defsystem` is an `evalCons` case on `LispNames.ASDF_DEFSYSTEM`
  (special form — options are data); `asdf:load-system` is a global function beside
  `load`/`require`, accepts computed names, drives `loadFile` with the system's `baseDir`
  pushed onto `loadDirStack`. Per-evaluator state:
  `asdfSystems`/`loadedSystems`/`loadingSystems` + `systemPath`.
- **Compile path**: handled *inside* `LoadInliner`'s recursion (a loaded file can call
  `load-system`, a spliced component can `load`/`require`); the `Ctx` record threads the
  system registry, loaded set, cycle stack and search path. Top-level
  `(asdf:defsystem ...)` registers + is consumed; top-level literal
  `(asdf:load-system NAME)` splices deps then component files (dedup like `require`).
  Non-literal name = hard error at inline time; a *nested* asdf form is rejected by both
  compilers in the same `case` as `REQUIRE`/`PROVIDE`.
- **`load-system` / `quickload` keyword options are accepted and IGNORED on every path**
  (`AsdfSystems.checkIgnoredLoadOptions`, from `loadSystemName`, `LoadInliner.quickloadNames`
  and the two interpreter functions): there is no `operate` machinery for
  `:force`/`:verbose`/`:silent` to drive, and rejecting them would make a library that loads
  a system at RUN time (lack's `find-package-or-load` passes `:verbose nil`) unloadable over
  a no-op clause. The SHAPE is still checked, so a stray second system name is an error.
- **Search order**: dir of the loading file, then `--system-path` (CLI), then
  `RONTOLISP_SOURCE_REGISTRY` — plus, for a `ql:quickload`, the cache directories the
  download wrote ([dists.md](dists.md)). The latter two are `File.pathSeparator`-joined lists
  parsed by `RontoLispCli.systemPath()` and threaded to both paths and the REPL. The `asdf`
  package is seeded in `PackageRegistry` (does not use `cl`; both symbols external).
- **No ci-spec case is possible**: the compile path needs the `.asd` on disk at compile time,
  which the concatenated ci-spec driver cannot provide. Coverage is `AsdfSystemsTest` +
  `LispEvaluatorAsdfTest` + the asdf cases in `LoadInlinerTest` + the per-library E2Es below.

### Compile-time pathname folding (`cli.CompileTimePathnameFolder`)

After `LoadInliner.inline` returns the flattened program, a walker folds the ASDF/UIOP
pathname primitives real libraries call at load time to build a bundled-data-file path:

- `(asdf:system-source-directory X)` -> the recorded `LispSystem.baseDir` + trailing `/`.
- `(asdf:system-relative-pathname X REL)` -> that base with `REL` merged on (one-call form).
- `asdf:component-pathname` -> the same lookup (a system is the only component object
  materialized).
- `(make-pathname ...)` -> the composed namestring (`eval/PathnameOps.makePathname`).
- `(uiop:merge-pathnames* A [B])` -> the merged namestring (`PathnameOps.mergePathnames`).
- A top-level `(defparameter *X* <folded string>)` is recorded so a later primitive whose
  argument references `*X*` reduces too.
- It no longer folds `find-system` ITSELF (the runtime answer is an object), but unwraps a
  nested literal `(asdf:find-system 'x [ep])` in a fold's system-designator position
  (`systemDesignator`).
- Quoted data is opaque; a `let`/`lambda` rebinding never triggers substitution — the
  reduction fires only inside a foldable primitive's argument position, so the walk stays
  sound without full lexical-scope tracking.
- Same pass rewrites `(with-open-file (var <literal utf-8 path> [:external-format :UTF-8])
  BODY...)` into `(with-input-from-string (var <inlined contents>) BODY...)` when the path
  names a UTF-8 file on disk, **chunked at 20k Java-char boundaries** and reassembled with
  `(concatenate 'string CHUNK1 ...)` so the **JVM 65535 UTF-8 byte per-string ceiling** is
  never crossed. `:element-type` present SUPPRESSES the inlining.
- **The bundling rewrite skips a path the program itself opens for OUTPUT**
  (`collectWrittenPaths`, a pre-pass collecting literal namestrings behind
  `:output`/`:append`): baking compile-time contents of a file the program then WRITES makes
  it read stale data. This is the only non-conservative shape and it fails SILENTLY, which
  is why the guard is a pre-pass rather than a local check.
- Interpreter unaffected (`LoadInliner` runs only on the compile-to-file entry). Coverage:
  `LoadInlinerTest` (per-pattern folds, bundling, chunking, missing-file passthrough,
  `doesNotBundleAFileTheProgramItselfWrites`) + ci-spec
  `open-if-exists-append-keeps-the-existing-content`.

## ASDF component metaobjects at run time

One Lisp source, `eval/asdf.lisp` (canonical shape, `eval.AsdfRuntimeLibrary`), defines the
class family + `asdf:find-system` + the readers +
`system-source-directory`/`system-relative-pathname`/`component-pathname`
(designator-accepting: the object or a name; they answer NAMESTRINGS, `.kb/pathnames.md`) +
`registered-systems` + `asdf:*user-cache*`. The one per-backend seam is the record source
`%asdf-system-record`/`%asdf-system-names`; a record is
`(CLASS DIR FILES DEPS LOADED-P VERSION)` with FILES `(RELATIVE . RESOLVED)` pairs.

- **Interpreter**: both are Java built-ins over the live per-evaluator
  `asdfSystems`/`loadedSystems` (insertion-ordered, so `registered-systems` is
  deterministic); a built-in shim system answers a plain record even before it loads (the
  lack `find-package-or-load` probe route). `asdf.lisp` loads lazily
  (`ensureAsdfRuntimeLoaded`) on: resolution of any name it defines (function OR variable),
  `defsystem`/`load-system`/`quickload`/`test-system`, and any
  `defclass`/`defmethod`/`typep`/`typecase`/`etypecase`/`make-instance` form MENTIONING a
  component class name (`mentionsComponentClass`). `asdf:load-system`/`asdf:test-system`
  stay Java (they drive `loadFile`); both accept the metaobject as a designator
  (`asdfDesignator` reads slot 0).
- **Compile paths**: `LoadInliner.inline` ends with fold-then-splice —
  `CompileTimePathnameFolder.fold` FIRST (so a program whose only asdf use folds away
  splices NOTHING), then `AsdfRuntimeLibrary.process` prepends `asdf.lisp` + the baked
  `%asdf-registry%` table (from `Ctx.systems`/`loadedSystems`, `LinkedHashMap` for order) +
  the compile seam (the record defuns, runtime `asdf:load-system`/`ql:quickload` =
  "already spliced -> nil, else the call-time error", the `asdf:test-system` dispatch)
  whenever the folded program still references any runtime asdf name. The
  `Jvm/WasmExprCompiler` `ASDF_LOAD_SYSTEM`/`QL_QUICKLOAD`/`ASDF_FIND_SYSTEM` cases compile
  an ordinary call when the defun exists (`ctx.functions.containsKey`), keeping the
  historical stubs ONLY as the no-pipeline fallback (a direct compile with no `LoadInliner`
  in front — a test seam). Nested `require`/`provide`/`asdf:defsystem` remain compile errors.
- `asdf.lisp` needs its `resource-config.json` entry (`AsdfRuntimeLibrary` as
  `typeReachable`), like every classpath-loaded Lisp resource.

### test-op

- `parseDefsystem` records `:perform (test-op (o c) BODY...)` into `LispSystem.testOp`
  (params verbatim; body pre-qualified by `normalizeAsdUserForm` — bare uiop members to their
  home spelling, bare asdf FUNCTION members to `asdf:`, **and a uiop member the `.asd`
  qualified ITSELF (`uiop:symbol-call`, `uiop::`, or a home sub-package) to that same home
  spelling**, since only the home name has a definition behind it) and
  `:in-order-to ((test-op (test-op ...)))` into `testOpEdges`. A body containing an
  unresolved `#.` marker, or a qualified `test-op :after` method, stays tolerated-and-ignored
  (recording it would fail the eager compile of the emitted defun). Both options stay in
  `IGNORED_OPTIONS`, so their `#.` markers are never resolved or warned about.
- `LispSystem.packageInferredClass` marks which systems instantiate
  `asdf:package-inferred-system` (a declaring primary AND every derived sub-system — the
  branch rove's `run-system` typecase takes).
- Interpreter `asdf:test-system` = `loadSystem` + follow edges (visited-set cycle guard) +
  eval `((lambda (o c) BODY) nil <metaobject>)`.
- Compile path: `spliceSystem` emits `(defun %asdf-test-op-<name> (o c) BODY)` at the
  system's splice point; a top-level literal `(asdf:test-system NAME)` splices the system AND
  its test-op closure (`spliceTestOpClosure` — a plain load never pulls tests in) and KEEPS
  the call; the generated `%asdf-run-test-op` cond dispatches per name (edges via
  `%asdf-test-edge` = load-check + recurse, then the perform defun).
- Pinned by `AsdfMetaobjectsE2eTest` (all four backends: eq-memoized find-system, the readers
  over a `:components`+`:module` system, defmethod specializers, registered-systems, nested
  load-system no-op, `test-system` through the `:in-order-to` chain; fixture
  `src/test/resources/asdf-metaobjects-demo`), the metaobject legs of
  `PackageInferredSystemE2eTest`,
  `LispEvaluatorAsdfTest.findSystemAnswersAMemoizedComponentMetaobject` /
  `testSystemFollowsTheInOrderToChainIntoThePerformBody`, `AsdfSystemsTest`'s test-op
  parse/normalization group, and
  `LoadInlinerTest.nestedLoadSystemOfASplicedSystemAnswersNilOnJvm`.

## The substitution ladder (five tiers, widest first)

1. **Whole shim system** (`eval/ShimLibraries` + `BuiltinSystems`).
2. **Replacement `.asd`** (`eval/AsdOverrides`) — system metadata only, real sources.
3. **Leaf-module shim** (`ShimLibraries.leafModuleForms`) — one component file, real
   everything else.
4. **Derived forms** (`ShimLibraries.rewriteComponentSource`) — individual FORMS of a real
   component, the rest read normally.
5. **Generated component** (cl-unicode) — a component that does not exist in the release.

Beyond the end: **refused systems** (`ShimLibraries.refusalReason`) map a system name to a
SENTENCE saying why it cannot load, checked at the top of both loaders beside the conflict
check. Registered: `cffi-grovel` (grovelling compiles and runs a C program to read platform
headers) and `cffi-libffi` (structures by value already work through the foreign function
API's own struct layout). A clear message beats letting `ql:quickload` fetch something that
dies unparsed.

### Tier 1: built-in shim systems

`asdf:load-system`/`ql:quickload` and a `:depends-on` resolve these to bundled shims
(`src/main/resources/am/ik/rontolisp/eval/*.lisp`) instead of downloading — they are
per-implementation portability layers that cannot support rontolisp from their side.
Replacement-by-real-library plan is a standing item.

`BuiltinSystems.DEPENDENCIES` records edges BETWEEN shim systems; both loaders load/splice
them first. One edge exists: `flexi-streams -> trivial-gray-streams` (the flexi shim's
in-memory octet streams are real Gray streams and the protocol must be defined before their
`defclass` runs). `ShimLibraries.forms` takes the TARGET backend's `Features`, so a `#+`/`#-`
in a shim source says what it means on the backend being built for.

- `usocket`, `trivial-gray-streams` (adapts onto rontolisp's own Gray protocol,
  `.kb/gray-streams.md`), `closer-mop` (`class-slots` returns `(name declared-type)` pairs
  from `%class-slot-defs` over the ClosRegistry; plus `compute-slots` and a signalling
  `generic-function-lambda-list`), `flexi-streams` (a REAL `flexi-stream` wrapper class — a
  Gray stream lending UTF-8 characters to the octet stream it wraps — beside the real
  in-memory octet pair), `float-features` (wraps `%ieee754-*`; interpreter + JVM, no WASM),
  `bordeaux-threads` (nickname `bt`; the locking subset over `rontolisp:*-mutex`, with
  `with-lock-held` a built-in expansion rather than a shim defun, `.kb/mutexes.md`),
  `trivial-garbage` (nickname `tg`; `finalize` registers nothing and returns the object,
  `cancel-finalization` is a nil no-op — CL guarantees finalizers nothing, and no backend has
  GC hooks; consequence: a leaked prepared statement lives until the connection closes),
  `trivial-cltl2` (nickname `cltl2`; `define-declaration` a registering no-op,
  `declaration-information` always nil, which routes trivia's match2*+ onto `:trivial`),
  `cl+ssl` (client-side TLS over `rontolisp:tls-upgrade`, `.kb/tcp-sockets.md`),
  `mgl-pax-bootstrap` (package `mgl-pax`, nickname `pax`; `defsection` expands to
  `(defvar NAME nil)`, nil no-ops for the PAX-World pair; its real `.asd` declares
  `:around-compile`, a compile hook outside the subset — that is why the shim stays),
  `swank` (the degenerate rung: `create-server` SIGNALS, `stop-server` is a nil no-op; it
  exists because clack's `.asd` hard-depends on it and SLIME's own `.asd` is a program the
  front end cannot read).
- **`trivial-features`** — whose whole content IS the announcement
  (`BuiltinSystems.DECLARED_FEATURES` + generated `(pushnew :F *features*)` forms from the
  same list, so the read-time and run-time halves cannot drift). Declares **`:unix`** (every
  backend's file/path/environment surface is POSIX-shaped and none is Windows),
  **`:little-endian`** (WASM linear memory and a reactor's `:bytes` boundary are
  little-endian by the spec; the JVM backend exposes no such view) and **`:64-bit`** (every
  backend's fixnums are 64-bit and every pointer-shaped value is 8 bytes; cffi's `types.lisp`
  reads exactly this to pick `:size`'s base type). **The HOST half is a probe, not a table**:
  `:darwin` + `:bsd` or `:linux`, and `:arm64` or `:x86-64` (`BuiltinSystems.hostFeatures`,
  from `os.name`/`os.arch`), announced on the JVM-family targets ONLY, because a wasm module
  runs on WASI and not on the compiling machine. Hence the announcement is TARGET-AWARE
  (`declaredFeatures(names, target)`). Needed because cffi's whole library-resolution layer
  runs on `featurep`: without `:darwin` cl-sqlite picked the LINUX names on a Mac and
  `examples/jvm/cffi-sqlite.lisp` died with "Unable to load any of the alternatives", and
  `:arm64` is what puts Homebrew's `/opt/homebrew/lib` in the DYLD fallback search path.
  An unrecognized OS or CPU announces NOTHING for that half rather than guessing; `:32-bit`
  stays absent; `:windows` is not announced (`:unix` would have to come off first). **The
  base `Features` sets stay machine-independent**, which keeps the
  `reader-features-variable` ci-spec case's `(length *features*)` the same on every machine.
- **`babel`** (packages `babel` + `babel-encodings`) — upstream's own TWO layers, and the
  lower one is the point: the MAPPING protocol (`lookup-mapping` over
  `babel:*string-vector-mappings*` -> `code-point-counter` / `octet-counter` / `decoder` /
  `encoder`) IS the codec, and `string-to-octets` / `octets-to-string` /
  `string-size-in-octets` are drivers over it owning no coding logic. A library that decodes
  INCREMENTALLY has no whole octet vector to hand a driver (dexador's
  `src/decoding-stream.lisp` counts with `max-chars` 1 and decodes exactly those octets into
  a one-character `babel:unicode-char` buffer). In the one-character model **an encoding IS
  its name and a mapping IS its encoding**, so `get-character-encoding` and `lookup-mapping`
  normalize and answer the keyword, and `*string-vector-mappings*` is the SET of names that
  have a mapping — the same list `list-character-encodings` answers. **The counter and the
  decoder MUST agree on where a character ends** (drivers size a string from the counter then
  fill it with the decoder, so a disagreement is a wrong-length string, not a crash), which
  is why the 5- and 6-octet UTF-8 forms that never existed are consumed WHOLE by both. Errors
  are upstream's: the `character-coding-error` hierarchy per malformed shape,
  `*suppress-character-coding-errors*` swapping the signal for a substitution character
  (U+FFFD for UTF-8, U+001A for the single-octet encodings), every `:errorp` defaulting from
  that special and re-binding it. **Both packages are Java-seeded, so a `.lisp`-only widening
  publishes nothing** — a new name needs its `PackageRegistry` entry, and a run-time `export`
  is not a workaround; every `babel-encodings` external is re-exported under the `babel:`
  spelling by an import redirect built from that set (upstream's `babel` `:use`s
  `babel-encodings` and consumers spell both). **Where it stops**: three encodings
  (`:utf-8`, `:latin-1`/`:us-ascii` as the code-point identity they are), any other
  `:encoding` SIGNALS; and `string-size-in-octets` answers ONE value where upstream answers
  the counter's two (`.kb/multiple-values.md`). Pinned on all four backends by
  `BabelMappingE2eTest`.
- **`uiop`** — a package stub whose definitions arrive through `eval.UiopLibrary`
  (`.kb/uiop.md`, which registers all 15 sub-packages and gives every unimplemented member a
  `not-implemented-error` stub). Real members: `add-package-local-nickname` (consumed at
  resolve time by `PackageResolver` for literal top-level calls, so it works on every
  backend), `file-exists-p` (lowers straight onto `probe-file` — the truename on success,
  nil otherwise; interpreter global + a case in `LispMacroExpander.expandUiopStubCall`
  BEFORE the generic stub lowering), `merge-pathnames*`, the LISTING family
  (`directory-exists-p` / `directory-files` / `subdirectories` / `collect-sub*directories`,
  Lisp source in `LispPreludeLibrary` over the one `%list-directory` primitive),
  `getenv` (rontolisp's ONLY spelling of "read an environment variable"; ANSI CL has none),
  `symbol-call`, `split-string`, `emptyp`/`first-char`/`last-char`, `if-let`/`when-let`/
  `when-let*`/`with-deprecation` (built-in macro expansions; `with-deprecation` lowers to
  `(progn definitions...)` and joined `flattenTopLevel`'s splice forms beside `eval-when`,
  since it wraps top-level `defun`s), the temporary-file quartet
  (`ensure-directory-pathname`, `default-temporary-directory`, `delete-file-if-exists`,
  `with-temporary-file`), `native-namestring` (= `namestring`),
  `uiop::get-pathname-defaults` (internal, like real uiop; answers `""` on all four backends
  — every backend resolves a relative path against the host cwd, and `""` designates exactly
  that), and `uiop/image:print-condition-backtrace` (prints the CONDITION and no backtrace;
  real UIOP falls back to the same shape without a backtrace API).
  - **`uiop:symbol-call` is REAL on every backend**: the interpreter's is a runtime
    name-to-function lookup (`packageResolver.memberSpelling` + `resolveFunction` + `apply`);
    the compile paths lower it in `expandUiopStubCall` to
    `(funcall (intern (string name) (find-package pkg)) args...)` through the `_lookup`
    registry, AND carry uiop's own Lisp definition beside that fold, because the fold covers
    CALL position only while the idiom is usually written as a VALUE
    (`(apply #'uiop:symbol-call '#:pkg '#:name uri args)`, dexador's backend dispatch) —
    `.kb/symbol-runtime-api.md`. Reading it at all was the hard requirement: lack's
    `src/util.lisp` spells it with a SINGLE colon, so an internal-only symbol failed the
    whole FILE at read time.
  - `print-condition-backtrace` is defined in `uiop/image` (named directly by
    `lack-middleware-backtrace`'s `:import-from`) and the `uiop` package IMPORTS the name, so
    both spellings are ONE symbol and one prelude splice — pinned by
    `LispPreludeLibraryTest.bothUiopSpellingsOfPrintConditionBacktraceSelectTheOneEntry`.
  - `uiop:run-program` signals `uiop:not-implemented-error`: spawning an external process is
    outside every backend's sandbox, so an error is the honest answer.
  - **`with-temporary-file` is a MACRO** and therefore cannot reach `expandUiopStubCall`
    (which sees function-call shapes only). It is a real built-in expansion (the
    `usocket:with-*` pattern) in `LispEvaluator.evalCons` and both expression compilers,
    expanding into `%temp-file-name` + `open` + a cleanup. Two consequences: a LITERALLY true
    `:keep` drops the delete from the expansion entirely (keeping smart-buffer's spill path
    clear of the WASM unlink-shaped call-time error), and `%temp-file-name` /
    `uiop:delete-file-if-exists` are reached only from that expansion, which runs INSIDE the
    expression compilers — so both are selected by
    `LispPreludeLibrary.referencedBySurfaceForm` and rooted in `LibraryDefunPruner` like
    `%make-broadcast-stream`, or the compile paths emit "%TEMP-FILE-NAME is undefined".
  - **`EnvironmentLibrary.process` runs AFTER the whole library-splice chain** in
    `RontoLispCli`: `uiop:default-temporary-directory` reads `TMPDIR` through `uiop:getenv`,
    and upstream of the splice a smart-buffer program failed the `--component` compile with
    "compiled without EnvironmentLibrary.process".
- **`clack-handler-rontolisp`** — the Clack handler backend, and the one shim inverting two
  conventions, both forced by clack's late-bound discovery: its package is NOT seeded in
  `PackageRegistry` (the shim carries its own `defpackage`, because lack's
  `find-package-or-load` loads the system only when `find-package` MISSES) and it is
  registered under TWO system names (`clack-handler-rontolisp` + the dotted
  `clack.handler.rontolisp` lack derives from the package name). Three loader-side rules:
  the interpreter's runtime `asdf:find-system` answers a BUILT-IN system's name even before
  it loads; the interpreter's builtin-system branch of `loadSystem` evaluates shim forms
  through the RESOLVING `eval(form)` so a shim-carried defpackage registers; and
  `LoadInliner.spliceSystem` splices the handler shim EAGERLY after system `"clack"` (a
  compiled program cannot load at run time; the baked package table + `_lookup` registry
  serve the runtime probes). Full mechanics: `.kb/clack.md`.

### Tier 2: replacement `.asd` files (`eval/AsdOverrides`)

Some libraries' `.asd` is an executable PROGRAM, not data. `AsdOverrides` maps the `.asd`
FILE NAME to a bundled replacement (`src/main/resources/am/ik/rontolisp/eval/*.asd`, written
in the supported subset) and `AsdfSystems.locate` substitutes it **after locating the real
file — keeping the located PATH**, so component files still resolve against the real library
tree and the loaded sources are the library's own. One entry serves every backend.

| `.asd` | replacement | reason |
| --- | --- | --- |
| `ironclad.asd` | `ironclad-slice.asd` | defines component classes, generates defsystems with a `defmacro`, attaches `perform :around` |
| `cl-postgres.asd` | `cl-postgres-deps.asd` | declares the deps upstream under-declares (alexandria, cl-ppcre, usocket) + the `#.*string-file*` component |
| `postmodern.asd` | `postmodern-deps.asd` | opens with a top-level `eval-when` pushing features per implementation |
| `trivia.asd` | `trivia-trivial.asd` | routes `trivia` onto upstream's own `trivia.trivial` base system |
| `dbi.asd` | `dbi-deps.asd` | its cache selection rides a thread-capability feature expression that can never match |
| `tiny-routes.asd` | `tiny-routes-lite.asd` | not unparseable — replaced only to ADD the opt-in `tiny-routes/lite` secondary system |
| `cffi.asd` | `cffi-rontolisp.asd` | opens with `(error "Sorry, this Lisp is not yet supported")` and ends in a `defmethod version-satisfies`; the replacement names rontolisp's `cffi-sys` backend (`.kb/cffi.md`) |
| `cl-unicode.asd` | `cl-unicode-built.asd` | drops the build system, its `component-depends-on` method and the fiveam test system |

**Every bundled replacement `.asd` and every leaf-module shim `.lisp` also needs an entry in
`src/main/resources/META-INF/native-image/.../resource-config.json`** (`AsdOverrides` resp.
`ShimLibraries` as the `typeReachable` condition). The native binary loads them off the
classpath, and a missing entry fails ONLY there, as `<name> is missing from the classpath` at
`asdf:load-system` time — `./mvnw test` cannot catch it (the JVM run reads straight from
`target/classes`).

RETIRED: `chipz.asd` -> `chipz-crc32-slice.asd`. The real `chipz.asd` loads verbatim on all
four backends (`ChipzE2eTest`) now that `fill` exists.

### Tier 3: leaf-module shims (`ShimLibraries.leafModuleForms`)

Substitutes individual COMPONENT FILES inside a real system when the component's contract
with the rest of that system is a few package-qualified functions. Both loaders consult it
before reading a component file (`LispEvaluator.loadSystem` evaluates the forms through the
package resolver; `LoadInliner.spliceSystem` splices them). Each shim `.lisp` carries the
replaced file's own `defpackage` followed by canonical-shape fully-qualified defuns; a shim
MAY instead select a package with `in-package`, and both loaders then BRACKET it like a real
component (`packageResolver.pushPackage()`/`popPackage()`; the `%push-package`/`%pop-package`
markers on the compile path, emitted only when `selectsAPackage` is true).

**Key shape**: the map is keyed by SYSTEM name, and the component key is the path RELATIVE
TO THE SYSTEM'S BASE DIR (`src/prng/prng.lisp`, not `prng.lisp`), because ironclad's
components sit under `:module` prefixes; jzon's `.asd` lives in `src/` so its keys are bare.
A substituted component **does not have to exist on disk at all** — the shim short-circuits
before the file is read (which is why the vendored ironclad tree stays pruned).

Four motives, and a shim lives exactly as long as its motive:

- **portability**: jzon's `eisel-lemire.lisp` -> `jzon-eisel-lemire.lisp` (make-double =
  coerce + chunked exact-power-of-ten scaling, IEEE multiply/divide only so it is
  backend-identical; exponent clamped to +/-700; `|exp10| <= 22` rounds once, extremes a few
  ulps off), `ratio-to-double.lisp` -> `jzon-ratio-to-double.lisp` (a `coerce` one-liner),
  `schubfach.lisp` -> `jzon-schubfach.lisp` (write-float/write-double = `write-string` of
  `princ-to-string`, itself a Schubfach shortest round trip on every backend). This kills
  both the originals' `#.` power-of-ten table crash and their u64/u128 arithmetic. **Making
  macro-time globals LAZY did not retire the first reason**: the special is a `defvar` with
  NO value form, filled by a later top-level form the macro-time evaluator never runs, so
  forcing on the `#.` read still finds nothing. Also cffi's `src/strings.lisp` ->
  `cffi-strings.lisp` (the real file drives a babel code generator the shim lacks).
- **the backends already provide it**: ironclad's `src/prng/prng.lisp` ->
  `ironclad-prng.lisp` — a perfectly portable CSPRNG, so the shim draws
  `rontolisp:random-bytes`.
- **size of dependency, opt-in**: `tiny-routes/lite` -> `src/middleware/path-template.lisp`
  -> `tiny-routes-lite-path-template.lisp`, keyed by a system name existing solely to carry
  the substitution.
- **the implementation seam**: cffi's `src/cffi-rontolisp.lisp` does not exist upstream AT
  ALL — it is the backend every CFFI port writes for itself, declared by the replacement
  `.asd` and supplied only as a resource, so upstream's tree on disk is never edited.
- **size of file** is the fourth, and it EXPIRES: `src/public-key/public-key.lisp` ->
  `ironclad-public-key.lisp` was retired once the real file loads — a shim reproducing two of
  its functions verbatim is a redefinition race, not a saving. **General rule: a
  size-motivated leaf shim lives exactly as long as the real file has no route in.**

### Tier 4: derived forms (`ShimLibraries.rewriteComponentSource`)

Substitutes INDIVIDUAL FORMS of a real component and hands the rest to the caller's normal
read (so package resolution is the one a real component file gets). Each span is located by
a marker that must occur **EXACTLY ONCE** — a moved marker THROWS, naming the marker and the
file, because a silent fallback to the real source would restore the cost with nothing
pointing at why.

**`eval/Uax15Tables`** (pinned by `Uax15TablesTest`). uax-15 builds its tables at LOAD time
by parsing 2.7 MB of Unicode text through cl-ppcre. Two rewrites, both needed:

1. **Derived spans.** In `src/precomputed-tables.lisp`: the `(defvar *unicode-data* ...)`
   that reads and splits all 34,924 `UnicodeData.txt` rows becomes `nil`; the `let` folding
   them into the combining-class + two decomposition maps becomes those tables as data plus
   one BUILDER `defun` each; the `(defparameter *canonical-comp-map* ...)` + its `maphash`
   becomes a builder whose body is that maphash RELOCATED verbatim; the
   `(defparameter *unicode-letters* ...)` + the NINE hardcoded CJK/Hangul/Tangut range loops
   (nine, not seven — six sit behind an always-live `#-utf-16`) becomes
   `(defvar *unicode-letters* nil)` plus the UNION of those loops and the data-derived
   letters as sorted inclusive codepoint RANGES, searched by `%lite-unicode-letter-p`. In
   `src/uax-15.lisp`: the `let` folding `DerivedNormalizationProps.txt` into the four illegal
   lists becomes the source RANGE rows expanded on demand inside `get-illegal-char-list` and
   cached, and `unicode-letter-p`'s body becomes a call to that predicate. Everything that
   COMPUTES a normalization is verbatim upstream.
2. **Forced reads.** Each of the NINE bare reads of a derived table — `src/normalize-backend.lisp`
   (4) and `src/uax-15.lisp` (5) — becomes `(or *T* (%lite-build-T))`. Read-site forcing, not
   entry-point forcing: it keeps the granularity and cannot be bypassed by calling
   `uax-15::nfc` directly. The counts are an explicit inventory in `Uax15Tables.FORCED_READS`
   and a mismatch THROWS. The inventory is keyed by component PATH, so a component NOT in it
   is additionally scanned for the five names and throws if it has one.

**Three things make the `(or ...)` protocol correct and are the whole trap surface**:
(a) every table global must start `nil`, so the two upstream `defparameter`s of a fresh
(non-nil, hence TRUE) `make-hash-table` are demoted to `(defvar ... nil)` — left alone, `or`
short-circuits onto an empty table forever; (b) reads inside `precomputed-tables.lisp` are
deliberately NEVER rewritten (that file is full of `(setf (gethash K *T*) V)` write places),
so the composition-map builder forces its dependency explicitly as its first form (without
it all four backends die); (c) the nine range loops are READ now rather than run, so this
rewrite interprets a reader conditional the reader used to handle —
`hardcodedLetterRanges` THROWS on a loop spelled any other way, or behind any other
conditional, rather than silently dropping codepoints.

- The letter table is **GONE rather than lazy**: ~127,000 entries answering one membership
  predicate, replaced by 1,332 integers of merged ranges. That trades BUILD time for LOOKUP
  time; the table only pays off past ~11,000 calls interpreted and ~34,000-39,000 compiled,
  and the only caller in the loadable corpus is postmodern's `valid-sql-identifier-p`. **If a
  program ever crosses that, the answer is a memo keyed by the characters it actually asks
  about, not the 127,000-entry table back.** The search shape is tuned for the INTERPRETER: a
  3-argument recursion reading the vector out of its global, `low`/`high` EVEN indices into
  the flat pair run (the two shapes it beat are recorded on
  `Uax15Tables.LETTER_PREDICATE`). What makes the merge sound is that the ranges are the
  replaced table's key set MEMBER FOR MEMBER, holes included: `UnicodeData.txt` gives a CJK
  block only First and Last rows and the loops stop short of the Last one
  (`below #x4DB5` against a `4DBF` row), so `#x4DB4` and `#x4DBF` are letters and `#x4DB5` is
  not — the three codepoints `Uax15E2eTest` ends on.
- **Bulk numbers are emitted as decimal runs inside STRING literals scanned by a generated
  helper, never as numeric literals**: an integer literal costs TWO JVM constant pool entries
  and ~25,000 of them push a cl-postgres-scale class past the **65534-entry class-format
  ceiling**, where every emitted u2 index silently truncates (`.kb/jvm-method-size-limits.md`).
  The runs are a QUOTED LIST of **1,000-character chunks cut between integers**, never one
  long literal, because **`(char s i)` costs O(i) on every COMPILE backend** (wasm's
  `_str_char_at` UTF-8 walk, the JVM's `offsetByCodePoints`), making a single-literal scan
  quadratic. **The interpreter is 1.0x on purpose** — it was fixed the other way
  (`Environment.charRef` rebuilt the whole Java `String` from the `int[]` on every access;
  it now indexes the slot), so **do not A/B the chunking rationale interpreted**. The
  compile-path O(i) remains.
- When the bundled data files cannot be read, the real source loads and builds everything
  eagerly, and `rewriteTables` appends four IDENTITY builders generated from the same name
  list the forced reads use (the only thing keeping the two sides in step, since no E2E walks
  that path) plus a `%lite-unicode-letter-p` that is NOT an identity: with no derived ranges,
  the eagerly built letter table is the answer. Routing `unicode-letter-p` through a NAME
  rather than inlining the search is what buys that.
- Two behavior changes: `(uax-15:unicode-letter-p #\A)` answers T where the real load answers
  NIL (upstream keys every data-derived letter entry on `nil` — its `char-from-hexstring`
  reads `#+utf-32`, which trivial-utf-16 announces from a COMPUTED push the reader
  deliberately does not honor, `.kb/reader-features.md`); and uax-15's four internal table
  globals read `nil` from user code until the API is called, `*unicode-letters*` forever.
- **The module does NOT shrink**: an ASDF-spliced third-party tree is never pruned
  (`.kb/library-defun-pruning.md`) and the fold bakes each `with-open-file` per site, so
  laziness moves work in time, never out of the artifact. Retiring the letter table IS an
  exception (it deleted emitted code): -19,926 bytes of wasm, -15,671 of JVM class.
- Pinned by `Uax15E2eTest` (all four backends; its exercise LEADS with the two lines pinning
  the deferral — all five globals still `nil` after the load, then `uax-15::compose` to force
  the composition map through the one route that does not go through the decomposition map
  first) and `Uax15TablesTest` (derivation, builder shape, demoted defparameters, dependency
  force, folded range loops and their union, the chunk cut, every loud guard — moved form,
  wrong read count, an outside-inventory read, a re-spelled range loop, a surviving
  letter-table mention — and the fallback cross-checked against the builder names the forced
  reads call).

**`eval/QuriEtldTables`** (pinned by `QuriEtldTablesTest`). Upstream writes its effective-TLD
tables as `(defvar *etlds* '#.(load-etld-data))`, a READ-time evaluation whose value is a list
of two HASH TABLES — the interpreter can hold that, a compile backend has to emit the datum
as a literal and there is no literal syntax for a hash table (`Cannot quote: #<HASH-TABLE>`).
Three spans move: the `defvar` becomes `nil` + a `%lite-build-etlds` builder; the three
`*etlds*` reads in `parse-domain` become `(or *etlds* (%lite-build-etlds))`; and the
`with-open-file` header's path becomes the LITERAL namestring **with `:element-type
'character` dropped** — not cosmetic, since a literal path with only `:external-format` left
is exactly the shape `CompileTimePathnameFolder` inlines, and `:element-type` is precisely
the option that suppresses it. Narrowing: `(load-etld-data OTHER-FILE)` reads the bundled
list.

Retired from this tier: `eval.AlexandriaSymbols` (alexandria's `maybe-intern`), deleted once
`*package*` became a genuine dynamic variable and the form it rewrote around started reading
the caller's package by itself (`.kb/packages.md`).

### Tier 5: a GENERATED component (`eval/ClUnicodeTables`)

Three of the eight components cl-unicode's primary system names — `lists.lisp`,
`hash-tables.lisp`, `methods.lisp` — **do not exist in the release at all**. Real ASDF
materializes them by loading the separate `cl-unicode/build` system (a UCD parser over
`build/data/*.txt`) and running its `:perform (load-op ...)`, wired with `:output-files` plus
a `component-depends-on` method on `prepare-op`. All three are outside the subset, which the
original `ASDF:DEFSYSTEM cl-unicode/build: unsupported option :OUTPUT-FILES` error reported
correctly. `eval/ClUnicodeTables` parses the same bundled data files and emits the same
definitions in Java at load time (the fourteen range trees `methods.lisp` looks up, the ten
hash tables `hash-tables.lisp` fills, the six property-symbol lists `lists.lisp` sets),
reached through `ShimLibraries.leafModuleForms` — which is why that method takes the system's
base directory and the loader.

- **The emitted shapes match `build/dump.lisp` exactly, quirks included**, so upstream's own
  test suite stays meaningful: `build-range-list` returns at `+code-point-limit+ - 1` (the
  last range ends at `#x10FFFE` and `#x10FFFF` is in no range); `split-range-list` picks its
  middle with `(round (1- length) 2)`, i.e. round-half-to-EVEN; the `pushnew` lists come out
  in reverse order of first appearance.
- **The dumps are not quoted literals**: written out they are ~5 MB holding ~140,000 numbers
  and ~68,000 character names (~208,000 constant pool entries against the 65534 a class may
  name), so each table travels as its own PRINTED TEXT inside ~230 string literals read back
  with `read-from-string` (570 entries), and each of the fourteen range trees became a flat
  range table `%lookup` binary-searches, BUILT ON FIRST LOOKUP. Retiring the balanced tree is
  the one FAITHFULNESS deviation and it is free (`split-range-list`'s middle only decided the
  tree's shape, never an answer). Chunk ceilings: `.kb/jvm-method-size-limits.md`; a quoted
  list is walked recursively when resolved and read, so 200,000 elements in one literal
  overflows the stack in `PackageResolver.resolveQuotedDatum`, and the reader recursing per
  element caps a text chunk at **1,000 elements**.
- Pinned by `ClUnicodeTablesTest` (a synthetic UCD slice covering every reader in
  `build/read.lisp`, asserted as emitted TEXT) and
  `AsdfSystemsTest.parsesTheBundledClUnicodeReplacementAsd`.

## Libraries that load, and what is pinned

Every entry loads its VERBATIM upstream sources unless a substitution is named.
`AsdfLibraryE2eSupport` hosts the four-backend E2Es and needs a VENDORED tree per system
(under `src/test/resources/`); a library whose tree lives only in the quicklisp cache is
verified MANUALLY on all four. Language gaps a library forced are owned by their own topic
files, named per entry.

- **cl-who 1.1.5** — `ClWhoE2eTest` (interpreter + JVM, vendored). `with-html-output(-to-string)`
  expands at MACRO-EXPANSION time, so on the compile path the whole render resolves in the
  `UserMacroExpander` macro-time evaluator and backends see baked string constants. The
  macro-time config replay it needs is a **static purity judgment, not a data file**:
  `UserMacroExpander.isPureConfigSetf`/`isPure` accept a top-level `(setf (PLACE args...) V)`
  only when the `(defun (setf PLACE) ...)` writer is a pure config setter (body assigns
  special/global variables through a side-effect-free allow-list of control forms +
  arithmetic/comparison/logic/predicate builtins; `ecase`-over-specials is covered) and V +
  place args are pure. **Deny by default**; no per-library registration; no
  `eval-when (:compile-toplevel)` escape hatch. Top-level `defvar`/`defparameter`/`defconstant`
  register LAZILY (`Environment.defineLazy`) — see `.kb/defmacro-backquote.md`.
  Lite: `:indent` (needs dynamic special rebinding), `(let ((*html-mode* ...)) ...)` ignored
  (use `(setf (html-mode) :html5)`), hyperdoc's `loop ... being the external-symbols` empty.
  **NO render ci-spec case is possible**: it needs the `.asd` on disk, but
  `JvmClassShakerCorpusTest`/`WasmTreeShakerCorpusTest` re-compile the concatenated ci-spec
  sources WITHOUT `--system-path`, so a `cl-who:` reference would fail package resolution there.
- **cl-base64 3.4** — `ClBase64E2eTest` (all four, vendored); ci-spec
  `cl-base64-residue-features`. Limits: the WASM backends degrade an integer beyond `i31` to a
  float, so `integer-to-base64-string` diverges for large integers (the E2E uses 1234567);
  `base64-stream-to-*` compiles but the CL stream objects it expects are untested. Owned
  elsewhere: `.kb/symbol-runtime-api.md` (the macro-time `intern` name synthesis),
  `.kb/string-write-runtime.md` (`(setf (schar s i) c)`), `.kb/error-handling.md`
  (`error`/`signal`/`warn` as FUNCTIONS, `cerror`).
- **assoc-utils** — `AssocUtilsE2eTest` + `AssocUtilsUpcaseE2eTest` (all four, vendored, Public
  Domain); ci-spec `assoc-utils-features`. Introduced `eval/LispPreludeLibrary`: recursive
  rontolisp-source defuns (canonical bare-cl source, the json.lisp pattern) spliced by
  `LispPreludeLibrary.process` (after `UrlLibrary.process` in
  `RontoLispCli`/`RontoPlayground`/both corpus tests/`AsdfLibraryE2eSupport.compileProgram`)
  when referenced, and lazy-loaded by the interpreter in `resolveFunction` via
  `loadedPreludeNames`. Also brought the 5-value `define-setf-expander` protocol
  (`LispEvaluator.setfExpanders`; `UserMacroExpander` rewrites `setf`/`incf`/`decf` on a user
  place BEFORE the compilers, so the expr-compiler `DEFINE_SETF_EXPANDER` case is a nil no-op)
  and `LoopExpander.parseForBeingHash` (snapshots the table into an alist once via a `maphash`
  accumulator, so iteration order follows the backend's hash order). Lite: `equalp` on
  arrays/hash-tables/structures falls back to `eql`; `define-modify-macro` place subforms may
  double-evaluate.
- **ironclad 0.61 slice** — `IroncladE2eTest` (all four). `ironclad-slice.asd` declares
  `ironclad/core` (package, conditions, generic, macro-utils, util, common, digests/digest,
  macs/mac, prng/prng), `ironclad/digest/sha256`, `ironclad/digest/sha512` (SHA-384 AND
  SHA-512, one file), `ironclad/mac/hmac`, `ironclad/kdf/pkcs5`, `ironclad/kdf/hmac`,
  `ironclad/kdf/password-hash`, `ironclad/kdfs` (kdf/kdf.lisp) and `ironclad/public-key/rsa`
  (src/math.lisp + public-key/{public-key,pkcs1,rsa}.lisp), aggregate `ironclad` = that slice.
  - **One deliberate deviation from real ASDF's layout, forced by eager compilation:
    kdf/kdf.lisp loads LAST.** It `make-instance`s every KDF class and the compilers expand a
    `make-instance` where it stands, so every class it names must already be registered —
    which is why kdf/hmac.lisp is declared a DEPENDENCY of `ironclad/kdfs`, not a sibling.
  - `bordeaux-threads` (a real `:depends-on`) is dropped: zero call sites in the slice.
  - Covered: FIPS 180-2 SHA-256/224, SHA-384/512, RFC 4231 HMAC (both `make-mac` and the
    deprecated `make-hmac`), RFC 5869 HKDF case 1 via `make-kdf :hmac-kdf`, PBKDF2 at 1 and
    4096 iterations, RFC 7677 §3 SCRAM-SHA-256 end to end, and the RSA stack over a FIXED
    2048-bit key pair (so the raw signature is a pinned constant) plus PSS/OAEP round trips and
    a live `generate-key-pair :rsa :num-bits 1024`.
  - Out of scope: ciphers, aead, octet-stream, the rest of prng/, the non-RSA public-key
    algorithms, and the KDFs whose classes live in those subsystems (scrypt, argon2, bcrypt).
  - **Not a rontolisp gap**: PS512 with a 1024-bit key trips ironclad's own
    `(>= num-bytes (+ (* 2 digest-len) 2))` assertion — PSS/SHA-512 needs >= 1040 modulus bits.
  - **`random-data` is a leaf shim** over `rontolisp:random-bytes` implementing `*prng*`,
    `list-all-prngs`, `make-prng`, `random-data`, `random-bits`, `strong-random`.
    `strong-random` keeps upstream's REJECTION SAMPLING (draw `integer-length` bits, retry
    while >= limit) — a modulo fold would bias the low characters of a SCRAM nonce.
    `:fortuna`/`:fortuna-generator` are not implemented (`make-prng` accepts any name and
    returns the OS generator); the seed-file operations are absent.
  - **Why the SCRAM names had to exist before cl-postgres could compile at all**: an undefined
    function is a **compile-time** error on the JVM/WASM backends while the interpreter defers
    it to the call, so even a trust-auth connection that never runs SCRAM would not build.
  - `gen-client-proof` XORs two 32-byte digests as 256-bit INTEGERS, so the wasm legs were
    blocked until exact integers there became arbitrary-precision (`.kb/wasm-bignum.md`).
    **Pinned edge case**: `integer-to-octets` returns the MINIMAL vector, so a proof whose high
    bytes cancel comes back shorter than 32 and must be padded (cl-postgres'
    `pad-octet-vector`). An off-by-one is a silently wrong proof surfacing only as an
    authentication failure, so `IroncladE2eTest` pins a synthetic pair whose XOR has two
    leading zero bytes beside the RFC vector.
  - **Native PBKDF2 on the INTERPRETER only** (`eval/IroncladNative` + `eval/Sha2Kernels`):
    `LispEvaluator.loadSystem` rebinds `IRONCLAD::PBKDF2-DERIVE-KEY` to a Java kernel as soon
    as the system DEFINING it finishes loading — the `LinalgSimd` interception shape (capture
    the defun, fall back to it on any DECLINED input), keyed on the DEFINITION rather than a
    system name so it fires for the aggregate and for a bare `ironclad/kdf/pkcs5` alike.
    Always on and **not a `.kb` divergence**: PBKDF2-HMAC-SHA-224/256 is a spec-defined
    function of its arguments, so the kernel and ironclad's code are interchangeable by
    construction (contrast `--simd`, opt-in because it trades float precision). Costs:
    interpreted Lisp 17,091 ms, kernel 9 ms, JVM `.class` ~1 s, WASM component ~3 s — the
    compiled backends need no sibling. Boundary set by two measurements: replacing only
    `update-sha256-block` + `sha256-expand-block` buys ~4x, not enough (the compression
    function is 77% of the cost, the interpreted mdx buffering the rest), and the 4096-round
    loop re-derives the HMAC key schedule per iteration, which the kernel absorbs once.
    `Sha2Kernels` is hand-written rather than `java.security.MessageDigest` because it is
    `LispEvaluator`-reachable and therefore compiled into the native binary AND the browser Web
    Image, where a JCA provider is a registration burden or absent
    ([[web-playground-native-image-gotcha]]). Pinned from both sides by `IroncladNativeTest`
    (RFC 7914 vectors, the JDK's own `PBKDF2WithHmacSHA*`/`MessageDigest` as an independent
    oracle across every padding boundary, and the declined inputs still producing ironclad's
    own `check-type` / unsupported-digest text) and `IroncladE2eTest`, whose three COMPILED legs
    keep running ironclad's Lisp inner loop. **Re-evaluation trigger**: if the interpreter's own
    arithmetic/macro costs close the gap, or a compiled backend regresses into needing the same
    treatment, revisit the boundary — widening it to `digest-sequence`/the HMAC trio was
    considered and declined.
- **cl-postgres** — `ClPostgresE2eTest`, which runs by DEFAULT (Docker is its only gate; the
  `RONTOLISP_POSTGRES_E2E` siblings `MitoE2eTest`/`PostmodernE2eTest` stay opt-in).
  `open-database` + `exec-query` complete a real round trip against PostgreSQL 17 under
  `trust`, `password`, `md5` AND SCRAM-SHA-256 on the interpreter, the JVM and the WASM
  `--component` backend; **Preview 1 has no TCP by design** (`.kb/tcp-sockets.md`) and its
  socket calls compile to CALL-TIME error stubs. The verbatim `cl-postgres.asd` is exactly what
  the `.asd` front end above supports (pinned by
  `AsdfSystemsTest.parsesTheClPostgresAsdHeaderShape`); the `cl-postgres-deps.asd` override
  declares the under-declared deps (alexandria, cl-ppcre, usocket) so
  `(ql:quickload "cl-postgres")` resolves as ONE form on the compile paths.
  **Its `#.*string-file*` component must stay `strings-utf-8`**, the branch upstream's own
  feature test picks (`.kb/reader-features.md`, `:unicode`): `strings-ascii` announces
  `client_encoding SQL_ASCII` and encodes one octet per code point, so a non-ASCII query
  parameter reaches the server as an invalid byte sequence and desyncs the connection (the
  `unicode` rung pins round trips both ways).
  Harness: a Testcontainers `postgres:17-alpine` with a per-role `pg_hba.conf` ladder —
  `trustuser`/`passuser`/`md5user`/`scramuser`, ONE ROLE PER METHOD so a broken rung cannot
  fall back to another — and one probe program per backend asserting byte-identical output on
  three backends plus the Preview 1 compile error. **The component leg connects to the
  container's IP ADDRESS, not its network alias**, because `tcp-connect` takes only IPv4
  literals on WASM. `-c authentication_timeout=600` is kept as insurance for the ~3 s component
  leg on a loaded CI machine. Recorded costs, so a regression is a change to this file rather
  than a flaky test — scram-sha-256 connect, first/second: interpreter 155/68 ms, JVM 214/100,
  component 140/137; PBKDF2 x4096 alone 9 ms interpreted (native kernel), 44 JVM, ~87 component.
- **alexandria 1.0.1** — `AlexandriaE2eTest` (all four); vendored UNMODIFIED (the `.asd` +
  `alexandria-1/` + `alexandria-2/`; the two `tests.lisp` are `:static-file` entries,
  ordering-only and never read). **The exercise IS `examples/asdf/alexandria-demo.lisp`
  verbatim — keep the two in sync.** ci-spec `last-with-a-count` /
  `every-some-over-many-sequences` / `coerce-to-a-computed-result-type` /
  `read-sequence-into-a-character-buffer`.
  - **Two cross-backend CORRECTNESS bugs fell out, both found by a wrapper needing them.**
    (1) **`#'mapcar` as a first-class VALUE silently dropped every list but the first on both
    compile backends** (the wrapper was `binary(MAPCAR)`), so `alexandria:mappend`
    (`(apply #'mapcar function lists)`) answered `(1 2)` against the interpreter's `(1 3 2 4)`.
    Fixing it exposed the whole family diverging the same way; every member now takes N lists
    everywhere. **Read `.kb/map-family.md` before touching any of them.**
    (2) **An injected wrapper whose body calls `apply` was reachable while WASM's `apply`
    runtime was not emitted**: `usesEval` (which gates `_apply`) scans the SOURCE program and
    wrappers are injected after it, so `(funcall #'mapcar #'list '(1 2) '(3 4))` in a program
    using `apply` nowhere else answered `(NIL NIL)` on WASM (`_apply` degrades to a
    nil-answering stub rather than trapping). `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` +
    `referencesApplyingWrapper` name that set (the `map*` six, `every`/`some`, `funcall`) and
    the gate consults it. Pinned in isolation by
    `WasmLispCompilerIntegrationTest.applyUsingWrapperReachedByFuncallCompilesAndRuns` —
    **each assertion must be the ONLY form in its program**, so the concatenating ci-spec driver
    cannot cover it.
  - Still NOT supported (user-visible in `doc/*/guides/asdf-systems.md`): `type=` (needs
    `subtypep`'s secondary value); `format-symbol`/`ensure-symbol` and `ensure-function` on a
    symbol work interpreted and are loud compile-backend ERRORS;
    `shuffle`/`random-elt`/`gaussian-random` draw each backend's own entropy, so no
    cross-backend pin can exist for them.
- **jzon 1.1.4** — `JzonE2eTest` (all four, a combined basic + README exercise; vendored, plus
  an interpreter-only test for the WASM-divergent residue). Its numeric leaves are the
  jzon leaf-module shims above. Accepted WASM divergences kept OUT of the four-backend
  exercise: large-float print shape, hash-table iteration order over multiple keys, and
  non-ASCII `\u` escapes (`code-char` beyond ASCII emits one raw byte on WASM). Remaining
  `uiop:` stubs error at call time.
- **uax-15 0.1.3** — `Uax15E2eTest` (all four; transitively pulls the vendored split-sequence +
  cl-ppcre). Its table substitution is tier 4 above. Forced the pathname primitives +
  `with-open-file` file-inlining, a `LOOP` per-clause iteration-head rewrite in
  `LispMacroExpander`, and the **WASM UTF-8 string byte model** (`.kb/wasm-gc-strings.md`):
  `_charvec_to_str` encodes each character as its 1-4 byte UTF-8 sequence and the byte-indexed
  accessors (`char`/`aref`/`length`/`subseq`) walk UTF-8 through
  `_str_char_count`/`_str_char_at`/`_str_char_byte_offset`. The component test also drove
  `WasmComponentBuilder.memModuleFor`: the shared canonical-memory module's initial page count
  follows the core module's memory-import minimum, so a program whose static data exceeds 384KB
  no longer traps at instantiation.
- **cl-unicode 0.1.6 + cl-ppcre-unicode + cl-str**: `(ql:quickload "str")` resolves and loads
  cl-ppcre, cl-unicode/base, cl-unicode, cl-ppcre-unicode, cl-change-case, str, and
  `(str:title-case "HELLO LISP!")` answers `"Hello Lisp"` on **all four backends**. Three
  prerequisites, in order: (1) the **`equalp` key fold on every backend**, without which the
  load dies at `Unknown property name "Cs".` on any compiled backend — cl-unicode's
  `*property-map*`, its two name tables and `*property-aliases*` are all `equalp` and rely on
  case-insensitive lookup (`derived.lisp` asks for `"Cs"`, the table holds `"CS"`, because the
  bidi class `CS` registers after the general category `Cs` in `UnicodeData.txt` order and
  `*canonical-names*` is last-wins per symbol — upstream lands there too); fixed by folding the
  key before it is placed (`LispEquality.equalpKey`, then all four, `.kb/hash-tables.md`).
  (2) The generated components stopped being LITERALS — tier 5 above. (3) The WASM module's
  linear memory had to stop giving the bump heap a fixed three growth pages
  (`.kb/wasm-gc-heap-pregrow.md`). One infrastructure gap is worth knowing: cl-ppcre's
  `nsubseq` used to signal on every backend, taking the whole shared-substring surface with it
  (`:sharedp t` on `scan-to-strings` / `register-groups-bind` / `do-scans`, a FUNCTION
  replacement to `regex-replace`/`-all`) — **RETIRED**, since `make-array :displaced-to` over a
  string answers a real string VIEW on all four (`.kb/adjustable-arrays.md`), so the verbatim
  `nsubseq` loads and `ClPpcreE2eTest` covers the entry points it feeds. Also needed:
  `*compile-verbose*`/`*compile-print*` (nil here like `*load-verbose*`, but portable sources
  READ them and an unbound variable is a hard error) and two character names
  (`.kb/characters-code-points.md`).
- **s-sql** — ci-spec `s-sql-enablement-language-group`; the quickload run is MANUAL (the
  postmodern tree lives in the quicklisp cache). `(sql (:select '* :from 'foo :where (:= 'id
  1)))` renders identically on ALL FOUR — s-sql opens no sockets, so unlike cl-postgres it runs
  on Preview 1 (the socket calls its cl-postgres dependency drags in compile to CALL-TIME
  errors there: the pruner cannot drop cl-postgres' defmethod-anchored socket chain, since
  `LibraryDefunPruner.definitionName` prunes defun/defvar-family only, so `WasmExprCompiler`'s
  Preview-1 tcp family lowers through `LispMacroExpander.callTimeUnsupportedStub`, the same
  policy as an undefined function).
  **One replay rule landed for its operator table**: a top-level
  `(let (...) <definitions only>)` is evaluated WHOLE into the macro-time evaluator
  (`UserMacroExpander.registerMacroTimeDefinitions`), because `register-sql-operators` closes
  each of the ~230 `(eql :keyword)` `expand-sql-op` defmethods over a `make-expander` closure —
  without it the `sql` macro fell to the default op renderer on the compile paths while the
  interpreter worked. The ~230-method eql dispatcher stays under the JVM/WASM method-size
  guards with no chunking. Other pieces owned elsewhere: `FmtCut` (`~^`, `~:*` inside `~[`),
  [standard-output-redirect.md](standard-output-redirect.md),
  `LoopExpander.retargetSequentialEqualsSteps`.
- **postmodern** — `PostmodernE2eTest` (opt-in `RONTOLISP_POSTGRES_E2E=1`, same Testcontainers
  harness as `ClPostgresE2eTest`); ci-spec `postmodern-non-mop-milestone` for the socket-free
  mechanics. The whole graph resolves and the non-MOP build LOADS AND RUNS: `with-connection` /
  `create-table` / `insert-into` / `query` / `with-transaction` / `update` / `query :single`
  byte-identically on interpreter, JVM and `--component`, with `:reconnect` and
  `retry-transaction` restart paths driven for real. Preview 1 stays a compile error by design.
  Upstream's `.asd` is unreadable as data (a top-level `eval-when` pushing features per
  implementation), so `postmodern-deps.asd` **takes both feature decisions statically**:
  - **`:postmodern-use-mop` ON** — `table.lisp` joins ahead of `deftable.lisp`, `closer-mop`
    arrives through upstream's own `(:feature :postmodern-use-mop "closer-mop")` shape, and
    postmodern's `defpackage` takes its `#+postmodern-use-mop` branch
    (`(:use :closer-common-lisp ...)`, `.kb/packages.md`). The DAO layer runs on the static
    definition-time MOP subset (`.kb/clos.md`). The `:if-feature`/`(:feature ...)` clauses stay
    VERBATIM so the whole decision remains a feature flip — pinned by
    `AsdfSystemsTest.thePostmodernMopBuildIsAFeatureFlip` (re-parses the resource with the
    declaration reduced to `:postmodern-thread-safe` alone and asserts `table.lisp` and
    `closer-mop` leave the build) and `parsesTheBundledPostmodernReplacementAsd`.
  - **`:postmodern-thread-safe` ON** via `:rontolisp-features` — three lock sites in this build
    (connection pool, statement-id counter, class-finalize lock), five more in `table.lisp`.
    Honest because `bt:with-lock-held` really serializes (`.kb/mutexes.md`). OFF was a genuine
    narrowing, not a scope choice: rontolisp DOES run concurrent handlers (one virtual thread
    per request under `serve`), so the `(progn ...)` those sites compiled to was racy.
  - **`:depends-on` differs from upstream three ways, all deliberate**: `global-vars` DROPPED
    (declared upstream, ZERO call sites), `bordeaux-threads` follows the feature decision, and
    `cl-ppcre` + `uax-15` ADDED (called by `roles.lisp`/`execute-file.lisp`/`util.lisp`, never
    declared upstream; leaving them out would make the eagerly-resolving compile paths depend on
    the order of somebody else's `.asd`). `postmodern/tests` is not reproduced (fiveam,
    simple-date, local-time). `s-sql.asd` and `simple-date.asd` sit in the same release
    directory and need no override; `simple-date` is not a dependency at all (json-encoder
    probes for it with runtime `find-package`). `:nicknames (:pomo)` registers through
    `PackageResolver` like any user package.
  - One deviation remains visible in output: a condition prints as a slot dump rather than
    through its `:report`.
- **quri 0.7.0** — ci-spec `quri-enablement-language-group` + `QuriEtldTablesTest`; the
  quickload run is MANUAL on all four (its tree lives in the quicklisp cache; alexandria IS
  vendored, so vendoring quri's tree beside it is all an automated `QuriE2eTest` would take).
  Dependencies: alexandria, split-sequence, cl-utilities, idna (all real) plus the babel shim.
  Its one substitution is `QuriEtldTables` (tier 4). Two things worth carrying:
  - **`apply` through a COMPUTED designator has no arity ceiling.** Per-arity dispatchers take
    one physical parameter per Lisp argument and stop at `MAX_CALLABLE_ARITY`, and `_apply`
    walked the argument list, counted it, and fell off the end — nil on the JVM, an
    `unreachable` trap on WASM. Both backends gained a SPREAD dispatcher (`_invoke_v` /
    `FUNC_DISPATCH_SPREAD`) over EVERY callable, taking the list whole; each case reads its
    target's required parameters out of it and hands a variadic target the remaining TAIL,
    which IS the callee's physical rest parameter. Cheaper than raising the ceiling: one case
    per function, not one per (function, arity) pair.
  - **`format`'s destination is decided at RUN time** when it is not the literal `nil`/`t`
    (`formatDestinationDispatch`): `nil` is the build-and-return-the-string destination, so a
    VARIABLE destination cannot be lowered to a write. The **Gray-streams `format` rewrite used
    to override it**: `GrayStreamsLibrary.process` turns `(format STREAM ...)` into "render to a
    string, hand it to the write-string dispatch, answer nil" and fires over the WHOLE program
    as soon as ANY part uses the Gray protocol; it now performs the same test
    ([gray-streams.md](gray-streams.md)). **Only the concatenated ci-spec program catches
    this** — in isolation the quri case passes on all four.
  - Other pieces owned elsewhere: `#P"..."` and the pathname VALUE (`.kb/pathnames.md`),
    `print-object` joining `CL_FUNCTIONS`, `princ`/`~A` writing a symbol's NAME with no package
    qualifier (`LispSymbol.display` = `memberName`, CLHS 22.1.3.3; the JVM's
    `_lispToDisplayString` and the WASM `_princ_val` symbol branch became "everything after the
    last colon"), the defstruct export ORACLE (`PackageResolver.spellsAsExternal`, threaded
    through `expandTopLevelDefinitions`), `(:include parent (slot new-default) ...)` re-defaulting
    in the child's layout only while the slot keeps its inherited index, `nil`/`t` as string
    designators, `*print-escape*`/`*print-readably*` BOUND around the `print-object` call
    (`printObjectCall`), `(setf (getf place indicator) value)` lowered INLINE in
    `LispMacroExpander.expandSetf` (inline because the expansion happens during expression
    compilation, long after the prelude-splice pass; lite — `place` is evaluated twice), and
    `make-list :initial-element` (the element form bound OUTSIDE the loop so every cell shares
    one value, as CL specifies).
  - A plain BUG found here: `LispMacroExpander.rewriteLocalCalls` tested for a non-symbol head
    BEFORE testing for an improper list, and that branch rebuilds from `cons.toList()`, which
    DROPS an improper tail — a nested loop destructuring pattern `((field . value) . rest)` came
    back as `((field . value))`. The improper-list test now runs first.
  - **ONE limitation remains, pre-existing**: quri's `:lenient` percent-decoding crashes on the
    three compile backends when the input really is malformed — it skips a bad escape with a
    `go` out of a `handler-bind` handler into an enclosing `tagbody`, and a `go` that crosses a
    lambda has no lowering there (`.kb/do-return-block.md`). Well-formed input never reaches
    the handler, and `uri-query-params` defaults to `:lenient t`.
- **local-time 1.0.6** — ci-spec `local-time-enablement-language-group`; the quickload run is
  MANUAL on all four. The whole timestamp API is byte-identical on ALL FOUR. **Real TZif
  timezone files load** wherever the host has a filesystem
  (`(local-time:define-timezone tokyo #p"/usr/share/zoneinfo/Asia/Tokyo" :load t)` parses the
  binary zone file through `read-byte`/`read-sequence`); where they cannot be read — WASM with
  no preopened directory, a host without the file — local-time's own `handler-case` falls back
  to `+utc-zone+`, which is why making a failed `open` SIGNAL rather than trap on WASM was a
  precondition (`.kb/read-load-streams.md`).
  - **The four load-context pathname variables** `*load-pathname*` / `*load-truename*` /
    `*compile-file-pathname*` / `*compile-file-truename*` (`LispNames`,
    `PackageRegistry.CL_VARIABLES`). The first pair holds the file being loaded while its
    top-level forms run, on EVERY backend: the interpreter REBINDS them dynamically around each
    loaded file (`LispEvaluator.loadFile`), the compile paths ASSIGN them per spliced file from
    the `%begin-file` brackets `LoadInliner` emits. **An ASDF COMPONENT is loaded by its
    RESOLVED path** (what real ASDF hands `load`; a plain `load` keeps the spelling it was
    called with), which makes its `*load-pathname*` equal `asdf:component-pathname` — that
    equality is the point and `LoadContextE2eTest` pins it. Outside a load both are nil,
    including inside a function the load defined. Both are also established at READ time, so a
    `#.` datum reading them answers what the same file's run-time value will be; mechanics and
    the byte-identity gate are `.kb/load-inliner.md`.
    **The compile-file pair is permanently nil, at read time too, deliberately**, for three
    reasons — any one expiring is the re-evaluation trigger: (a) the portable spelling
    `(or *compile-file-pathname* *load-truename*)` now ANSWERS through its fallback arm;
    (b) local-time spells `#.(or *compile-file-truename* '*load-truename*)`, whose whole point
    is that the first arm is nil so the value is the SYMBOL, deferred to load time — a non-nil
    first arm silently freezes the COMPILING machine's path into the artifact; (c) a library
    branching on `*compile-file-pathname*` is asking "am I being compiled to a fasl", and the
    honest answer here is no (the compile path splices SOURCE and evaluates the datum in a
    macro-time image, CL's `load` situation, not its `compile-file` one).
    **If a real `compile-file` ever exists, bind both pairs together and revisit local-time's
    spelling in the same pass — not one of them alone.**
    **`*readtable*` rides the same list**: nil everywhere (the reader is the frontend's, with no
    runtime readtable object to name), but a loader binds it in the SAME `let` as the pathname
    pair (clack's `%load-file` binds all four around its read/eval loop), so
    `injectMvSpillGlobal` declares it too, or the rebinding fails with
    `Cannot compile symbol reference: *READTABLE*`. `injectMvSpillGlobal` declares whichever of
    the four the program mentions.
  - `merge-pathnames` and `truename` are `LispPreludeLibrary` entries — ONE Lisp definition over
    primitives every backend has, unlike `make-pathname` / `uiop:merge-pathnames*`, which stay
    Java + compile-time folding because their keyword shapes resolve at compile time.
    `merge-pathnames` is pinned against `PathnameOps.mergePathnames`
    (`LispPreludeLibraryTest#thePreludeMergePathnamesAgreesWithPathnameOps`); `truename` is
    `(or (probe-file p) (error ...))`, whose load-bearing half is the SIGNAL —
    `(ignore-errors (truename x))` is how a library probes for an optional directory. The
    DIRECTORY-LISTING family joined the same way (`.kb/directory-listing.md`).
    **One residue**: local-time's DEFAULT repository path is computed with a runtime
    `make-pathname`, which only the interpreter has, so the compile paths need an explicit
    `:timezone-repository`.
  - Also landed here: the `find` family taking the whole position keyword set through
    `buildPositionScan` with the ELEMENT as the answer (`positionScanValues` grew an
    `elementResult` flag, so first-class `#'find` takes them too),
    `make-array :initial-contents` from a **packed vector** (`LispIntVector` and
    `LispFloatArray` are sequences now — what the TZif reader seeds from), and
    `:element-type 'unsigned-byte` opening a BINARY stream. Pins:
    `LispEvaluatorTest#evalFindFamilyTakesThePositionKeywordSet`.
  - **Two pre-existing backend defects had to be fixed for it to work at all**, both unrelated
    to local-time: argument evaluation order was right-to-left for `list` (and backquote) on
    all three compile backends, which made the TZif header decode its six fields in reverse
    (`.kb/argument-evaluation-order.md`), and the WASM 7-parameter callable ceiling rejected
    `encode-timestamp-into-values`, so `MAX_CALLABLE_ARITY` was raised to 10.
- **lack / clack `.asd` front end**: the system-level `:pathname` prefix, the
  `register-system-packages` skip and the ignored `load-system`/`quickload` keyword options
  (all described above) let the unpatched `lack.asd` parse whole — the `:pathname "src"`
  primary, the eighteen one-line alias systems, and `lack/tests` with its own `:pathname`,
  nested `:module`s, `#+todo`-suppressed component and `:perform`. Pinned by `AsdfSystemsTest`
  (`parsesTheVerbatimLackAsd`, `loadSystemNameIgnoresKeywordOptions`,
  `loadSystemNameRejectsANonKeywordSecondArgument`, the `:pathname` group). The rest of the
  milestone is `.kb/clack.md`.
- **fast-io 1.0 + circular-streams** — `FastIoCircularStreamsE2eTest` (all four; vendored).
  Nothing library-specific landed; three general mechanisms did, each of which had blocked the
  load: the `.asd` feature announcement above, `with-slots` binding a write-only unbound slot
  ([clos.md](clos.md)), and a `slot-value` naming a slot NO class declares lowering to a
  RUN-time error instead of failing the compile ([clos.md](clos.md) — fast-io's `open-stream-p`
  reads `'openep`, a typo, in a method nothing calls; the interpreter never saw it because it
  expands a method body only when called).
  **Two upstream facts the exercise encodes, both verified against SBCL 2.2.9 rather than
  assumed**: circular-streams cannot see the end of a fast-io input stream (fast-io's
  `stream-read-byte` passes no `eof-error-p` to `fast-read-byte`, so it SIGNALS `end-of-file`
  where the Gray protocol wants `:eof` — the E2E reads past EOF through a Gray source of its
  own), and the `#.`-computed `:long-description` is dropped unresolved and unremarked.
  **One blocker is NOT fixed**: fast-io's `defmethod close` makes `close` a user generic that
  DROPS the built-in on the interpreter (`with-open-file` then dies with "No applicable method:
  CLOSE on INTEGER" anywhere in the image) while the compile paths ignore the method instead —
  a general "a user definition of a built-in name" gap.
- **trivia** — `TriviaE2eTest` (all four; trivia + lisp-namespace vendored); ci-spec
  `trivia-enablement-language-group`. Covers constant/variable/cons/list*/vector/struct/class
  patterns, `(type keyword)`, the mito cons/guard/eql nest.
  **DIVERGENCE RECORD**: `AsdOverrides` maps `trivia.asd` -> `trivia-trivial.asd`, declaring
  `trivia` as pure metadata over `trivia.trivial` — upstream's own sanctioned base system
  ("Systems that intend to enhance Trivia should depend on this package, not the TRIVIA
  system"). Upstream `trivia` depends on `trivia.balland2006`, the match-clause OPTIMIZER,
  which needs iterate (a whole loop DSL) + type-i and buys ZERO semantics; the `:trivial` route
  is semantically identical, just unoptimized. **Re-evaluation trigger**: if a real consumer
  needs iterate itself, or interpreter match performance becomes the bottleneck, do iterate +
  type-i + balland2006 as their own milestone and delete the override. Measured for that
  trigger: a 4-clause `match` in a defun costs ~0.31 s PER CALL interpreted, because the
  interpreter re-expands user macros every evaluation and one trivia expansion runs the whole
  level2 expander stack; the compiled backends expand once at compile time and are unaffected —
  so the trigger is really "someone hot-loops `match` under the interpreter".
  **Shims it needed**: `trivial-cltl2` (above) and two `closer-mop` additions.
  **The general mechanism that landed**: `UserMacroExpander` REPLAYS plain top-level forms of
  SPLICED SYSTEMS (inside `%begin-system`/`%end-system` provenance brackets) into the
  macro-time evaluator, and honors the compile-file situations of a macro-EXPANDED
  `(eval-when ...)` — [defmacro-backquote.md](defmacro-backquote.md).
- **sxql** — `SxqlE2eTest` (all four; sxql + cl-package-locks vendored; select/where incl.
  `:and`/`:or`/`:in`/`:like`, order-by `:desc`, limit/offset, left-join `:on`, insert-into
  `set=`, update, delete-from, create-table with column options, drop-table, alter-table);
  ci-spec `sxql-enablement-language-group`. `sxql:yield` produces SQL text + bind values as
  multiple values BYTE-IDENTICALLY on all four, verified against SBCL 2.2.9 on the same
  sources, so the pins are upstream's own answers.
  Two shapes worth carrying: **runtime slot names normalize to the base spelling** inside the
  shared `%slot-value-runtime`/`%slot-value-set-runtime`/`%slot-boundp-runtime` defuns —
  `(intern (symbol-name n))` **deliberately IN the public defun, not at call sites**, so the
  `intern` spelling is visible to the WASM `usesIntern` emission gate; and **`subtypep` walks
  struct `:include` ancestry** (with `structure-object` as every struct's supertype), resolves a
  user **deftype** on either side through its expansion, and takes an **`(or ...)`** compound on
  either side — sxql's `(subtypep (type-of clause) 'multiple-allowed-clause)` gates whether a
  SECOND where/join clause merges or signals, and `multiple-allowed-clause` is a deftype for
  `(or join-clause where-clause)`. The shared static `LispMacroExpander.subtypep` serves the
  interpreter and the literal fold, and `subtypepUniverse` includes struct names,
  `structure-object` and deftype names so the emitted `%subtypep-ancestor-table%` answers the
  runtime call identically. Other pieces owned elsewhere: `.kb/clos.md` (struct CLOS dispatch
  widened to `:include` parents and `structure-object`), `.kb/reader-features.md` (a `#.` whose
  value is a SYMBOL or cons in an evaluated position splices as the OBJECT,
  `resolveReadTimeEvalInCode`). Residue: lisp-namespace's `pprint-logical-block` call compiles
  to a call-time error stub.
- **The mito-closure tolerance batch**: four parse-level widenings, each a whitelist entry and
  **never a sink** — an unknown top-level form / component type still errors loudly (pinned).
  (1) `(:version NAME "1.2.3")` `:depends-on` entries. (2) A top-level `(defmethod perform ...)`
  tolerated and ignored, since WIDENED to every top-level method with `source-file-type` read.
  esrap's `perform :after` hook pushes six `:esrap.*` capability features + `(provide :esrap)`,
  and ignoring them is a **VERIFIED decision**: a grep of the whole cached dist for
  `#+esrap.`/`#-esrap.` and of esrap's sources for any `esrap.` feature read found zero hits.
  **Re-evaluation trigger**: if an esrap release or a downstream starts reading one, fold the
  pushed keywords into `LispSystem.features()` via the `collectFeaturePushes` channel.
  (3) A top-level doc-file component-class `defclass`, since generalized to any component class
  (ASDF's own `:doc-file`/`:html-file` are accepted without a defclass; chipz.asd is the driving
  shape). (4) `mgl-pax-bootstrap` as a built-in shim.
  Two resolver rules landed in `PackageResolver.resolve`: **a literal top-level qualified
  `define-package` (`uiop:` or `mgl-pax:`/`pax:`) is consumed exactly like `defpackage`** (the
  variant's extra clauses — `:use-reexport`, `:mix` — and redefinition tolerance still error
  loudly until a consumer needs them), and **`pax:defsection` AUTOEXPORTS its
  `(SYMBOL LOCATIVE)` entries from the current package** (`consumeDefsectionExports`) —
  mgl-pax's documented default and trivial-utf-8's ONLY export mechanism, without which uuid's
  `trivial-utf-8:string-to-utf-8-bytes` fails to resolve. `asdf:system` joined the asdf package
  externals (the class name, referenced as data by defsection bodies). Pinned by
  `AsdfSystemsTest` (the version-entry pair, the perform-defmethod pair, the doc-defclass trio)
  and `PackageResolverTest` (`definePackageIsConsumedLikeDefpackage`,
  `aBareDefinePackageIsNotTheVariant`, `paxDefsectionExportsItsEntries`).
- **cl-dbi + dbd-postgres** — manual three-backend run; Preview 1 out (no sockets), and a
  component needs `-S tcp=y -S inherit-network=y`. connect / do-sql / prepare / execute /
  fetch-all / with-transaction (commit AND rollback) / connect-cached / disconnect run
  identically on interpreter, JVM and component against `postgres:17-alpine`.
  **`AsdOverrides` maps `dbi.asd` -> `dbi-deps.asd`**: upstream's `.asd` PARSES fine, but its
  cache selection rides a thread-capability feature expression
  (`(:or :abcl (:and :sbcl :sb-thread) ...)`) that can never match rontolisp's feature set and
  **MUST NOT be satisfied by claiming one** (the additive-features rule), so the verbatim parse
  picks the single-threaded `cache/single.lisp` on backends that really run concurrent
  handlers. The replacement takes the decision per backend: `thread.lisp` + the real
  `bordeaux-threads` behind `:if-feature (:not :rontolisp-wasm)`, `single.lisp` (upstream's own
  threadless choice) on WASM. **DIVERGENCE RECORD + trigger**: re-evaluate if the WASM backends
  gain threads, or if upstream's feature expression changes shape. Driving consumer:
  `dbi:connect-cached` is per-thread connection pooling keyed by `bt2:current-thread`. Pinned by
  `parsesTheBundledDbiReplacementAsd` (thread on INTERPRETER/JVM features, single on WASM).
  Also: `rontolisp:current-thread` (`.kb/threads.md`), the bt shim's `make-lock` widened to
  `&rest` (the v2 `:name` spelling), `uiop:define-package` + `:use-reexport` +
  `:shadowing-import-from` (`.kb/packages.md`), the `trivial-garbage` shim, and
  `asdf:missing-component` / `asdf:retry` as resolve-only externals (dbi's
  `with-autoload-on-missing` handler-binds them around a runtime `asdf:load-system`; dead here
  — a missing system is a hard Java-side error, never a signaled condition). **The runtime
  driver load short-circuits as designed**: `dbi:connect` probes `find-driver` FIRST
  (`class-direct-subclasses` of `dbi-driver`), so on the compile paths — where the program must
  contain `(ql:quickload "dbd-postgres")` itself and a nested load-system is a call-time error
  stub — the stub is dead once the driver is loaded. CLOS pieces (a DIRECT `(make-instance
  driver)` with a computed class, the late-bound `#'generic` value): `.kb/clos.md`.
- **mito-core** — ci-spec `mito-core-enablement-language-group`; the quickload is manual. The
  DAO CRUD round trip — `mito.core:connect-toplevel`, `deftable` (serial auto-pk +
  `record-timestamps-mixin` injected by the `dao-table-class` metaclass), `ensure-table-exists`,
  `insert-dao`/`find-dao`/`select-dao`+`sxql:where`/`save-dao`/`delete-dao`, `object-id` — runs
  identically on interpreter, JVM and component. New dependencies: **dissect** (its
  stack/restart introspection is the empty-body no-op interface — no backend carries a Lisp call
  stack) and **uuid** (via ironclad + trivial-utf-8; v1/v4 draw the backend's own entropy
  through the `make-random-state`-answers-nil + `random`-ignores-the-state model). The
  enablement batch is general and owned per topic: `.kb/clos.md` ("mito-core integration
  batch"), `compiler/FreeVarAnalyzer` (case clause key lists as data — `(lambda flet labels)` as
  keys used to capture LABELS), [jvm-method-size-limits.md](jvm-method-size-limits.md) (the
  `%error-runtime` chain segmentation), `.kb/packages.md` (`:import-from` of an INHERITED name,
  `trueHome`), plus the `pax:defsection` export residue on the compile paths
  (`UserMacroExpander.paxDefsectionExportForm` — the defsection expands to a defvar at macro
  time, so the compilers' own resolver pass would never see the autoexports; a literal top-level
  `(export '(...))` is emitted in its place).
- **esrap** — `EsrapE2eTest` (all four; the parser is pure computation, so Preview 1 runs it;
  esrap + trivial-with-current-source-form vendored); ci-spec
  `esrap-enablement-language-group`. Exercise: esrap's own README smoke example plus mito's
  `migration/sql-parse.lisp` grammar VERBATIM (what `mito.migration:migrate` uses to split a
  migration file into statements) and the parse-error report; every expected line verified
  against SBCL 2.2.9. Nothing esrap-specific landed; the two gaps worth carrying because each
  gave a WRONG answer rather than an error:
  - **The SIZE of `(string N)` / `(simple-vector N)` / `(vector T N)` is checked.** Unchecked,
    `(typep sub '(or character (string 1)))` answered true for every string — so `(or "foo"
    "bar")` compiled to the single-CHARACTER dispatch and a successful parse advanced one
    position — and `(typep cell '(simple-vector 41))` answered nil for the packrat cache's own
    vector, which then reached `gethash`. **`(simple-vector N)` carries only a SIZE** (element
    type is always t) while `(vector ELEMENT-TYPE SIZE)` leads with the element type; reading
    one as the other produced both.
  - **`(cons CAR-TYPE CDR-TYPE)`** was not a compound type specifier at all — its arguments
    fell through to the ranged-NUMERIC default and compiled as bounds, so esrap's expression-kind
    table (`(cons (eql function) (cons symbol null))`) evaluated the symbol `function` as a
    variable. A non-numeric atomic type spelled compound now ignores its arguments.
  Others, each owned per topic: a constant DOTTED pair surviving a template that also carries a
  NESTED backquote ([defmacro-backquote.md](defmacro-backquote.md) — the CLtL2 path's
  `bq-attach-append` constant fold appended the tail as if proper), multiple
  `(:constructor ...)` on one `defstruct` ([defstruct.md](defstruct.md)), `:test-not` across the
  whole `:test`-taking family as ONE shared `TestSpec` (so the compiled call and the first-class
  value decide the same way) plus `count`'s `:start`/`:end`, **`reduce` over an EMPTY sequence
  calling its function with ZERO arguments** (CL's rule; `#'append`'s wrapper became variadic in
  the same pass, because on WASM a wrong-arity indirect call TRAPS where the JVM was lenient),
  the character predicates, the printer surface ([pretty-printer.md](pretty-printer.md) —
  `*print-level*`/`*print-length*` are not decoration, esrap's `print-object` BINDS both), the
  format logical block `~<...~:>` and `~/name/` ([format.md](format.md); a `~/name/` is a
  function REFERENCE and the only trace of one, so `LibraryDefunPruner` scans string literals
  for it), `*modules*` as the real set `provide` records and `require` consults,
  `(compile nil lambda-form)` EVALUATING the form through the eval runtime instead of signalling
  (`compile`'s own contract where there is no compiler), and **a macro-GENERATED `deftype`
  reaching the compilers' registry** (`UserMacroExpander.emitMacroGeneratedDeftypes` —
  alexandria defines its whole `positive-integer`/`array-index` family from one `macrolet`, so
  nothing in the emitted program named them and a `typecase` clause using one failed the COMPILE
  while the interpreter resolved it).
  Residue, both dead in esrap and warned about at compile time: `set` and `break` are undefined.
  `char-name` answers nil for a graphic character, which is CL — SBCL's Unicode NAME
  (`DIGIT_ZERO`) is an extension, and the only difference in the parse-error report.
- **tiny-routes** — `TinyRoutesE2eTest` (all four; routing is pure computation, so Preview 1
  runs it; vendored, `extraSystemPath` = the vendored cl-ppcre) plus the three tiny-routes legs
  of `ClackE2eTest`, which serve the same routes over real HTTP and are the only coverage of the
  LIVE `ql:quickload` and of `tiny-routes-middleware-cookie`. Only SERVING needs `clackup`
  (`.kb/clack.md`). Three general gaps, each a hard failure:
  1. **The LOOP anaphoric `it` was unbound in any package but `cl-user`**
     (`LispMacroExpander.LoopExpander`): the substitution matched the symbol by RAW name, and
     the expander runs AFTER `PackageResolver`, so only the unqualified `cl-user` spelling ever
     hit — and `tiny:routes`, the dispatch function every application goes through, is
     `(loop ... when (funcall handler request) return it)` inside `(in-package :tiny-routes)`.
     Both compile paths failed at COMPILE time. **Matching the MEMBER (`splitQualified`) in any
     package is the rule**: no `cl:it` exists to collide with. `(loop-finish)` carried the
     identical raw-name compare and was fixed with the same helper.
  2. `uiop:if-let` / `when-let` / `when-let*` / `with-deprecation` as built-in macro expansions.
  3. **A serve component whose program ends in a non-`cl-user` package**: the handler bridge
     `eval/HttpLibrary` synthesizes is appended AFTER the program (so the handler NAME resolves
     where the directive was written), which left `%serve-dispatch` / `%serve-request-body` /
     `%serve-handle` resolving in the application's own package while http.lisp — spliced at
     the head — calls the unqualified ones, so `(in-package :demo)` before `clack:clackup`
     failed the `--component` compile with "Cannot compile: %SERVE-DISPATCH". The three
     synthesized names now carry an explicit `cl-user::` qualifier, which normalizes to the bare
     name in every package; the handler reference is deliberately left unqualified.
  `tiny-routes/test` is out of scope: it depends on fiveam, whose own dependency
  `net.didierverna.asdf-flv` stops on the unsupported `:long-name` defsystem option.
- **tiny-routes/lite** — the OPT-IN ppcre-free variant, and the first substitution keyed by a
  system name existing solely to carry it. Three tiers composed: `AsdOverrides` maps
  `tiny-routes.asd` -> `tiny-routes-lite.asd`, which redeclares the primary system VERBATIM (so
  the plain load is untouched — a silent substitution was rejected) and adds secondary system
  `tiny-routes/lite` = same components, no `:cl-ppcre`; `ShimLibraries.LEAF_MODULES` substitutes
  `src/middleware/path-template.lisp` under THAT system name only
  (`tiny-routes-lite-path-template.lisp`: same package, same four exports, the dispatch cond and
  the non-ppcre defuns verbatim upstream); `AsdfSystems.locate` resolves the slash name by the
  secondary-name rule; and `DistClient.ensureAvailable` gained the general fallback that a slash
  name absent from systems.txt downloads its PRIMARY's release (ASDF's naming rule guarantees
  `NAME/SUB` lives in `NAME.asd`). **Both tiers are needed together** — the file swap alone is
  -0.9%, because the loaded-but-unreferenced engine stays anchored through its CLOS surface
  (`.kb/optimize-dead-code-elimination.md`, the routing section, which holds the measurements).
  **The matcher's contract is "matches identically or refuses loudly"**: tokens are
  `:([A-Za-z_][A-Za-z0-9_-]*)` anywhere in the template — the token-NAME scan is as greedy as
  upstream's, so `/a/:x-:y` parses as ADJACENT tokens `:X-` and `:Y` and `/a/b-c-d` binds
  `(:X- "b-c-" :Y "d")`; **do not "fix" that, it is upstream's own behavior, empirically
  pinned** — matched with greedy-with-backtracking over `([^/]+)` parts. A template containing
  any of `. \ [ ] ( ) { } | ^ $ * + ?` (live regex syntax upstream) or `:regex t` SIGNALS at
  route-build time naming the escape; a zero-token colon template (`/a/:1`) matches nothing on
  either system (upstream's keyword matcher answers a nil plist, so the wrapper never calls the
  handler). Exact templates (no colon) are `string=` on both, metacharacters included — the
  rejection applies only where upstream builds a regex.
  Pinned by ONE corpus in two classes: `TinyRoutesLiteE2eTest` (lite on all four backends,
  vendored tree WITHOUT cl-ppcre on the search path — passing at all proves the dependency is
  gone — plus the rejected shapes' verbatim messages) and `TinyRoutesLiteUpstreamParityTest`
  (the REAL engine over the same `TinyRoutesLiteCorpus` constants on the interpreter, plus the
  co-load refusal). **Co-loading the two systems is REFUSED by both loaders in both orders**
  (`ShimLibraries.conflictingSystem`, checked at the top of `LispEvaluator.loadSystem` /
  `LoadInliner.spliceSystem`): they define the same packages, so whichever loaded last would
  silently redefine the matcher — against the lite contract in one order, silently re-shipping
  cl-ppcre in the other. Consequence: `tiny-routes-middleware-cookie` (depends on full
  tiny-routes) cannot be combined with lite, correctly. Docs: the `tiny-routes/lite` subsection
  of `doc/{en,ja}/guides/asdf-systems.md`, `examples/cloudflare-workers/httpbin-tiny-routes`,
  the `examples/asdf/README.md` row.
  **A cl-ppcre/lite in the same pattern was built, parity-pinned and then REJECTED (user
  decision) — do not re-propose it** as the answer to the engine's module share; the direction
  is shrinking the REAL engine's compiled share.
- **rove 0.10.0** — `RoveE2eTest` (all four; rove + dissect vendored beside cl-ppcre). The demo
  project `src/test/resources/rove-demo` carries BOTH system shapes rove's `run-system`
  typecase dispatches on: a `:package-inferred-system` (`my-app/tests`) whose suite is found
  through the `component-sideway-dependencies` walk over the derived sub-system metaobjects, and
  a plain `defsystem` (`my-plain/tests`) whose suite is found through the `*load-pathname*`-keyed
  file->package map recorded per `deftest`. The whole assertion surface runs
  (`deftest`/`testing`/`ok`/`ng`/`signals` with a user condition and `'type-error`/`outputs`/
  `pass`/`fail`/`skip`/`failing`/`setup`/`teardown`/`defhook`/`diag`, a failing assertion, an
  assertion whose form signals) and every entry point (`rove:run` on both shapes, `run-test`,
  `run-suite *package*`), each answering the passed-p boolean the
  `(uiop:quit (if (rove:run :my-app/tests) 0 1))` exit recipe consumes.
  Harness catch-up: `AsdfLibraryE2eSupport.compileProgram` hands `LispPreludeLibrary.process`
  the TARGET feature set (`uiop:featurep` answers against the `*features*` the backend was
  SEEDED with, `.kb/uiop.md`) and runs `EnvironmentLibrary.process` per backend (rove's
  `with-local-envs` reads `uiop:getenv`, which under `--component` is the spliced
  environment.lisp). Expected lines verified against SBCL 2.2.9 modulo stripped ` (Nms)`
  durations, `#+sbcl`-only `at file:line` lines, the dissect stacks, and ONE text divergence:
  the printer spells an accessible symbol package-qualified (`MY-APP/MAIN:ADD` where SBCL prints
  `ADD`) — **flip `RoveE2eTest`'s expected lines to the SBCL spellings when that lands.**
  Documented non-goals that hold: `uiop:lispize-pathname` stays unreached (`resolve-file`'s fasl
  arm is dead while `asdf:*user-cache*` is nil), `deftest`'s `:compile-at :run-time` is
  interpreter-only, `:style :none` on a compiled program needs the program to load
  `rove/reporter/none` itself, and a raw wasm TRAP in a test body still ends the run
  (`.kb/error-handling.md`). Docs: `guides/testing.md` + the `guides/asdf-systems.md` row.
- **The examples are rove's first consumer**: `examples/console/roman.lisp` (the 1..3999
  round-trip), `examples/cloudflare-workers/httpbin/check.lisp` (six requests through the
  hand-written Worker adapter, asserted over the PARSED reply) and
  `examples/browser/minesweeper/minesweeper-core-test.lisp` (over the rendering-free core)
  check themselves. Shape, also taught in `guides/testing.md`: a plain single file with
  `(asdf:load-system :rove)` + `(use-package :rove)` in `cl-user` +
  `(uiop:quit (if (run-suite *package*) 0 1))`, spelling the VENDORED route (`--system-path` at
  `src/test/resources/{rove,dissect,cl-ppcre}`) so `-Drontolisp.examples=true` passes with no
  network, with `(ql:quickload "rove")` named in a header comment as the outside-this-repository
  spelling. Two harness defects had to go first: **`examples.yaml`'s `systemPath` is now a
  LIST**, each element absolutized separately and joined with `File.pathSeparator`
  (`ExamplesE2eTest.systemPathFlags`; passing `a:b` as ONE path absolutized only `a`, and the
  scalar spelling still parses via `ACCEPT_SINGLE_VALUE_AS_ARRAY`), and the INTERPRETER leg now
  passes `--system-path` at all. The manifest checks rove's SUMMARY lines only, because
  per-assertion lines print forms and rove appends a ` (Nms)` duration past 37 ms.
  `IndentRules` gained rove's body-taking operators (`testing`/`failing` -> `body(1,2)`,
  `setup`/`teardown` -> `body(0,2)`, `defhook` -> `body(2,2)`; `deftest` needs none, the `def*`
  convention reads it) — without them `rontolisp format` aligns every assertion under the
  description STRING's width. **What the conversion FOUND, both real bugs the milestone's own
  demo could not see**: an inner `handler-case` not shadowing an enclosing `handler-bind`
  (fixed — `.kb/error-handling.md`, "A `handler-case` joins the cluster stack") and the
  `#'coerce`/`#'elt` sweep (rove's `form-inspect` needs every operator in an `ok` to be
  first-class; `.kb/core-representation.md`, "Built-in function wrappers").
- **`rontolisp test` — the CLI runner.** `TestCommand` (package `cli`) is a SOURCE GENERATOR,
  not a mode: it builds ONE program (rove prologue + target + verdict epilogue) and hands it to
  the ordinary `RontoLispCli` pipeline, so with no `-o` it is interpreted and with `-o` the SAME
  program compiles, every emitted artifact carrying the identical exit contract for free
  (`uiop:quit` is real on all four). That is why `test` is parsed by `CliOptions` like an
  ordinary run — unlike `format`, which owns its whole argv — since every compiler flag must
  keep working. **Upstream `roswell/rove.ros` is the spec**: read it in the quicklisp cache
  (`~/.rontolisp/quicklisp/software/rove-*/roswell/rove.ros`); it is NOT in the vendored copy.
  Mirrored from it: rove loaded up front (`asdf:load-system` when rove is on the search path,
  `ql:quickload` otherwise), a `.lisp` target's system name read from its `defpackage`
  (`AsdfSystems.fileDefpackageName`, the same `file-defpackage-form` rule the package-inferred
  loader uses), `asdf:load-system` + `asdf:test-system` then `rove:run` as the fallback, and the
  verdict read from `rove/core/suite:*last-suite-report*` AFTER the run so nothing runs twice.
  - **The one hook that is not cosmetic**: rove sets `*last-suite-report*` in `call-with-suite`
    ONLY, which `rove:run`/`rove:run-tests` reach and **`rove:run-suite` — the README-FAQ shape a
    single test file ends with, and the shape this command exists for — does not**
    (`run-suite` -> `with-reporter` -> `run-suite-tests`, no `call-with-suite`). So the prologue
    defines `(defmethod rove:invoke-reporter :after (reporter function) (setf
    rove/core/suite:*last-suite-report* (rove/core/stats:stats-results reporter)))`:
    `invoke-reporter` is the one funnel EVERY entry point crosses, and its argument IS the stats
    object. Without it the runner would have to re-run the target's tests —
    `RoveTestCommandE2eTest`'s `containsOnlyOnce("Summary:")` pins that it does not. A second
    replacement in the same prologue:
    `(defmethod rove:run-suite (suite &key (style rove:*default-reporter*)) ...)` — same
    specializer, so CL redefinition semantics REPLACE rove's method — because rove's own
    `run-suite` hard-codes `:spec` while every other entry point defaults to
    `*default-reporter*`, and without the replacement `-r dot` would silently do nothing for
    exactly the FAQ shape.
  - **Deliberate divergences from `rove.ros`** (re-evaluation triggers): (1) failure exits **1**,
    not `-1` — the 8-bit mask turns `-1` into 255. (2) No `COVERAGE`/sb-cover arm; still a
    non-goal. (3) The target's own stdout is NOT swallowed into a broadcast stream the way
    `run-file-tests` does it — rove's `*report-stream*` is
    `(make-synonym-stream '*standard-output*)` and only `call-with-suite` rebinds
    `*standard-output*` back to `*rove-standard-output*`, so on the `run-suite` path swallowing
    stdout would swallow the REPORT. **If rove ever moves that rebinding into `run-suite-tests`,
    the swallow becomes safe and worth taking.** (4) A `.lisp` file whose `defpackage` names no
    locatable system is LOADED instead of erroring (upstream would hand the name to
    `asdf:load-system` and die), then, if nothing recorded a report, the suite of the package it
    declares — CL-USER when it declares none — is run; without that the FAQ demo is not a valid
    target. (5) Running NO test exits 1 with a message on stderr, where upstream's
    `(every #'passedp '())` is a vacuous pass — a suite that stopped registering its tests is the
    silence the command exists to end. (6) A system designator, not only a `.lisp`/`.asd` FILE,
    is a target. (7) `-r` accepts only `spec|dot|none`, an unknown one being a command-line error
    (exit **2**, as for any bad argv, keeping "the command was wrong" distinct from "a test
    failed") rather than upstream's run-time attempt to load `rove/reporter/<style>`; `none` IS
    loaded up front, because `make-reporter` would otherwise load it at RUN time, which only the
    interpreter can do. Colors follow the destination (terminal yes, pipe and every compiled
    artifact no) instead of rove's unconditional ON.
  - **Not done, deliberately**: a plain `rontolisp FILE` still exits 0 after a red suite (a
    top-level form is a statement, `.kb/toplevel-statement-values.md`). A stderr hint was
    weighed and REJECTED: the generic run path would have to know about rove and read
    `*last-suite-report*`, and doing it only where it is cheap (the interpreter) is precisely
    the lagging-backend divergence this tree forbids.
  - Coverage: `RoveTestCommandE2eTest` (8 tests — the red/green verdict on all four backends as
    SUBPROCESSES, since a compiled `uiop:quit` is a real `System.exit`/`proc_exit`; the reporter
    option; the defines-only fallback; a system target and a `.asd`; the no-test rule) plus two
    `RontoLispCliTest` cases for the help text and the exit-2 argv errors. **No ci-spec case is
    possible**: that driver concatenates every case into ONE program and cannot supply a file
    target. Docs: `doc/{en,ja}/guides/testing.md` leads with the subcommand.
- **cl-mustache 0.12.3** — `ClMustacheE2eTest` (the API surface: `render`/`render*` over string
  and pathname templates, `compile-template`, `define`, `make-context` with `:data`/`:partials`,
  alist / hash-table / chained contexts, sections, inverted sections, partials, dynamic partial
  names, lambda sections) and `ClMustacheSpecE2eTest`, both all four; vendored, MIT/Expat.
  Nothing about the `.asd` needed widening (its `#.` `:version` reads `version.lisp-expr`
  beside the source, which the compile-time pathname fold resolves). **It was the FORCING
  FUNCTION for seven general gaps, none cl-mustache-specific**: `ctypecase`; **`gethash`'s
  present-p surviving a FUNCTION return** (`context-get` is a `defmethod` whose body IS
  `(gethash ...)`, so without it every `{{name}}` rendered empty — `.kb/multiple-values.md`);
  `#.` read-time eval seeing `*load-truename*` on the COMPILE path; `signal` of an unhandled
  condition being fatal on the three compiled backends (a missing partial signals
  `partial-cant-be-found`); hash-table printing (`{{.}}` over a map — it TRAPPED both WASM
  backends); WASM float printing (the spec's Decimal Interpolation cases); and a
  runtime-computed absolute path resolving against the preopen NAMES rather than fd 3 (file
  templates and `*load-path*` partial lookup, `.kb/read-load-streams.md`).
  `ClMustacheSpecE2eTest` loads the vendored, machine-generated **194-case mustache spec suite
  verbatim** through a 20-line `plan`/`is`/`finalize` stand-in for `prove` (not in the tree)
  that prints ONE character per case, so the expected line pins the pass/fail SET and not just
  the count. **158/194 is parity with upstream, not a shortfall**: SBCL 2.x fails
  byte-identically the same 36 through the same shim — null interpolation, dotted names pushing
  a context frame, and the entire 26-case `~inheritance.json` module, which postdates the 1.1.2
  spec cl-mustache targets. Implementing those is a change to cl-mustache, so **the number is a
  REGRESSION pin: if it moves, a rontolisp change moved it.** Upstream's `t/test-api.lisp`
  scores 20/20 on all four but is NOT in the E2E: its
  `#.(or *load-truename* *compile-file-truename*)` bakes the HOST path of `t/test.mustache`,
  which the container the WASM legs run in does not have — `ClMustacheE2eTest` covers the same
  ground with a template the program writes to `target/` at run time.
- **jose** — `JoseE2eTest` (HS/RS/PS/none, integer claims with the claim-check keywords, every
  correctable condition, two malformed tokens; every line pinned against SBCL 2.2.9 on the same
  sources AND the HMAC tokens against Python's `hmac`/`hashlib`, the HS256 one being the token
  jose's README publishes) and `JoseTestSuiteE2eTest` (upstream's OWN `jose/tests/jwt` through
  rove, the `rontolisp test jose/tests/jwt` shape), both all four, shared dependency path in
  `JoseSystems`; vendored with trivial-utf-8. A `:class :package-inferred-system`, so its files
  load off their own `defpackage` headers with no `:components`. **The whole dependency graph
  loads unpatched too** — cl-json, cl-base64, ironclad, split-sequence, assoc-utils, alexandria,
  trivial-utf-8 (over the `mgl-pax-bootstrap` shim); no slice, override or leaf shim of its own.
  Two general bugs were found finishing it: `with-input-from-string` over a MUTABLE character
  vector cast to `String` on the JVM, and **a multi-pair `setq` whose later value form builds a
  closure** — `FreeVarAnalyzer` walked the first pair only, so the closure's captures were never
  recorded: a silently wrong answer on the JVM and `Cannot find variable for closure` on WASM.
  The second is cl-json's, not jose's: `set-custom-vars` expands to exactly that shape and
  `json-rpc.lisp`'s `invoke-rpc` is its only caller, so it sat behind the tree-shaker until a
  program loaded rove beside cl-json.
  `jose/tests/jws` is excluded and always will be: it `(:use #:pem)` and `pem` is in no
  Quicklisp dist, so it runs on no implementation. **Non-goal: JWE** — jose implements JWS/JWT
  only; re-scope if upstream adds it.
- **iterate 1.6.0** — `IterateE2eTest` (all four; a macro over pure computation, so Preview 1 is
  in; vendored, no `extraSystemPath`). Acceptance list: the numeric driver in every spelling
  (`from`/`to`/`downto`/`below`/`by`), the sequence drivers (`in`, `on`, `in-string`,
  `in-vector`, `in-hashtable`), every accumulator (`collect`/`collecting` with and without
  `into`, `sum`, `multiply`, `maximize`, `minimize`, `counting`, `always`, `thereis`,
  `appending`, `reducing`), the control clauses (`repeat`, `with`, `while`, `until`,
  `if-first-time`, `finally`) and a nested `iter` feeding a named outer one through
  `(in outer ...)`; byte-identical against SBCL 2.2.9. Nothing iterate-specific lives in the
  tree — the five prerequisites are owned per topic: the `,.` splice spelling and the native
  `#L`/`#nL` reader macro ([defmacro-backquote.md](defmacro-backquote.md),
  [reader-features.md](reader-features.md)), the `ldiff`/`sublis`/`gentemp` prelude defuns,
  `with-hash-table-iterator` ([hash-tables.md](hash-tables.md)), and **`macro-function`
  answering nil for rontolisp's own `while`** ([symbol-runtime-api.md](symbol-runtime-api.md)) —
  iterate's walker asks before it recognizes its own clauses, and a yes made
  `(iter ... (while test))` loop forever. Feature-level pin: ci-spec
  `sharp-l-comma-dot-and-hash-table-iterator`.
  Two shape facts, both matching SBCL: a clause head is an ordinary symbol read in the CURRENT
  package, so `(iterate:iter (for i from 1 to 5) ...)` leaves `FOR` in `CL-USER` and iterate does
  not recognize it — the exercise does `(use-package :iterate)`, as a consumer must on any
  implementation; and iterate's own `iterate-test.lisp` does NOT ride along, being written
  against `rt`, which is not vendored. **Trigger to revisit**: vendoring `rt` (or porting the
  suite to rove) would replace the acceptance list with upstream's ~700 tests. Note the trivia
  DIVERGENCE RECORD above — iterate WORKING does not by itself fire its trigger;
  `trivia.balland2006` still additionally needs `type-i`.
- **array-operations 1.2.1** — ci-spec `array-operations-enablement-language-group`; the
  quickload is manual (its tree and let-plus's live in the quicklisp cache). A
  `:class :package-inferred-system` over `src/`, loading over let-plus + anaphora; the API runs
  IDENTICALLY on all four (`(aops:generate #'identity 7 :position)` / `each` / `permute`). The
  `.asd` needed ONE parse widening, `:long-name` joining the tolerated metadata. Everything else
  is general and owned per topic: the nested `with-slots` instance temp ([clos.md](clos.md), for
  clunit2, the library's own test framework), the read-modify-write place once-only rule
  ([argument-evaluation-order.md](argument-evaluation-order.md), for alexandria's `shuffle`), a
  `defmacro` a macro expands to inside a `progn` or a closing `let`
  ([defmacro-backquote.md](defmacro-backquote.md) — for let-plus and anaphora; without it the
  JVM and both WASM builds died on "The function DEFMACRO is undefined"), the standard
  float-range constants read like `pi` (`LispReader.CL_FLOAT_CONSTANTS`,
  `doc/*/reference/data-types.md`), and an ITERATIVE `equalp` over an array and a list
  (`LispPreludeLibrary`; the old per-element `labels` recursion blew the interpreter stack
  comparing two 720-element rank-5 arrays).
  **Standing result**: upstream's own clunit2 suite scores **217/219 here against SBCL 2.2.9's
  219/219 on the same sources**. The remaining 2 are ONE rontolisp answer and it is CONFORMANT:
  `(array-element-type (make-array 4 :element-type 'fixnum))` answers `T` where SBCL answers
  `FIXNUM` — `array-element-type` returns the UPGRADED element type and there is no
  fixnum-specialized array here to upgrade to. Nothing about array-operations itself is left.
  The two gaps that closed were the RANK-0 array (`(make-array nil :initial-element x)`, now the
  empty case of the row-major model — `.kb/array-literals.md`, "The RANK-0 array") and the ARRAY
  TYPE LATTICE (`type-of` answering `T` for every array instead of `(simple-vector 4)` /
  `(simple-array single-float (4))` — `.kb/declarations-type-checks.md`, "The array type
  lattice").

## Why this is a shim and not real ASDF (spike)

Upstream ASDF 3.3.7 (one file, 14,130 lines, 700 KB, MIT) **loads end to end** in the
INTERPRETER with the patch set below, and `find-system` then really walks
`*central-registry*`, probes the filesystem, `load`s a real `.asd` and runs `defsystem` ->
`register-system-definition` -> `parse-defsystem` -> `parse-component-form`. Three things
decided against it:

1. **It is a PORT, not a drop-in.** Upstream refuses on an implementation it does not know
   (`#-(or abcl allegro ... sbcl scl xcl) (error "ASDF is not supported on your
   implementation")`), `detect-os` refuses without `:unix`/`:windows`/`:genera`/`:os-macosx`,
   and there are 34 `not-implemented-error` call sites behind 230 implementation reader
   conditionals — each a `#+rontolisp` branch carried as a fork or a replayed patch set forever.
   (The port itself is cheaper than the count suggests: filling exactly ONE hole, `getcwd`, made
   `probe-file*` / `ensure-pathname` / `sysdef-central-registry-search` work, because
   `probe-file` / `truename` / `directory "*.*"` are already real.)
2. **Startup.** Loading it costs 2.85 s wall / 16.7 s CPU on the interpreter against a 0.40 s
   floor for `(print 1)` on the same jar — ~7x the whole current startup. Only "do not load it
   unless the program needs it" makes that survivable, and the shim gives that for free.
3. **The real blocker was OURS**: every hash table was keyed by `key.print()` with an `EQUAL`
   test and there was no `eq` table, while ASDF's session cache keys are lists holding live
   components whose graph is cyclic — a `StackOverflowError` in `LispHashTable.put`. **Both
   halves have since landed** (a table places a key by a depth-capped structural hash and
   decides it with `equal`, `.kb/hash-tables.md`; `print-object` is dispatched for a nested
   object), so the cyclic-key blocker is gone. Reasons 1 and 2 still decide it. Still no `eq`
   table.

Also worth knowing when widening the shim: the compile paths resolve systems at COMPILE time
(`cli/LoadInliner` splices component sources, `.kb/load-inliner.md`) and the browser playground
has no filesystem, so a runtime facility like real ASDF could only ever run inside the
compiler's own interpreter as a plan resolver — never in the artifact.

**Re-evaluate when** any of the three change: the hash-table/identity model gains `eq` tables, a
per-library shim fix stops being the cheaper move (watch the open uiop items), or upstream gains
a portable no-`compile-file` mode that removes the port surface.

### Reproducing the spike

1. Fetch `asdf.lisp` from `https://common-lisp.net/project/asdf/archives/asdf.lisp`.
2. `sed 's/UIOP/XIOP/g; s/uiop/xiop/g; s/ASDF/XSDF/g; s/asdf/xsdf/g'` — the built-in packages
   are pre-seeded. (`defpackage` over an EXISTING package now MODIFIES it as CL requires
   (`.kb/packages.md`), so a re-run needs the `asdf`/`uiop` SYSTEM renaming only.)
3. Rewrite each of the 39 `uiop/package:define-package` forms to `defpackage`, expanding
   `:use-reexport` transitively into an `:export` list and adding `:common-lisp` to every
   `:use`; drop `:recycle` / `:unintern` / `:intern`. `PackageResolver` accepts only
   `:use-reexport` of the extra clauses and rejects `:intern` outright.
4. Delete the port guard, `(pushnew :unix *features*)`, and make `not-implemented-error` a
   no-op, so the run measures what lies BEYOND the missing port.
5. Inject the missing `cl` names as per-package defuns (an unregistered name is a
   package-INTERNAL symbol here, so one definition per `in-package`).

## The `.asd`-as-data boundary is not what stops portableaserve

**Measured against `portableaserve-20190813-git`: widening the boundary into an evaluator buys
nothing.** The refusal we hit first is `acl-compat/acl-compat.asd`'s top-level
`(defun lisp-system-shortname () ...)`, called from a
`(defmethod component-pathname ((c unportable-cl-source-file)) ...)` routing each unportable
component into a per-implementation subdirectory. That method is not decoration — four source
files come from it — so honoring it means evaluating the `.asd`, and a `defmethod` on
`component-pathname` also means real `operate` machinery. But behind that door, in the order
they bite:

1. **Dependencies that do not exist here**: `:depends-on (:puri :cl-ppcre :ironclad :cl-fad
   #+sbcl :sb-bsd-sockets #+sbcl :sb-posix)`. `puri` and `cl-fad` are absent even from this
   machine's quicklisp cache (`quri` is not `puri`); `sb-bsd-sockets`/`sb-posix` are SBCL
   *contrib modules* and cannot exist here by construction.
2. **Component #1 needs SBCL's packages to be real.** `acl-compat/packages.lisp` is a plain
   `(:file "packages")` — nothing the parser has any say over — and does
   `#+sbcl (:use #:sb-bsd-sockets)`, `#+sbcl (:use #:sb-ext #:sb-gray)`,
   `#+sbcl (:import-from :sb-ext #:without-package-locks #:string-to-octets)`. Without `#+sbcl`
   announced, the `.asd` itself signals its own
   `#-(or lispworks cmu sbcl mcl openmcl clisp allegro) (error ...)`. So the two ways through
   are "announce `#+sbcl` and need SBCL's internals" or "announce nothing and be refused by
   name".
3. **The files the method selects are a port of SBCL's internals**: `acl-mp.lisp` carries 62
   `sb-thread:` references, `acl-excl.lisp` uses `sb-posix:stat`/`lstat`/`s-isdir`,
   `acl-sys.lisp` uses `sb-ext:*posix-argv*`, `acl-socket.lisp` uses
   `sb-sys:wait-until-fd-usable`.

**SBCL on this machine cannot load it either** (`Component :PURI not found, required by
#<SYSTEM "acl-compat">`), so there is not even an oracle to diff against. The conclusion is
about WHAT `acl-compat` is: a per-implementation compatibility layer whose whole job is to name
one host's internals. An `.asd` evaluator would move the failure four files later. The reachable
path to the AllegroServe chapters is the substitution ladder — a shim `net.aserve` /
`acl-compat` surface (`publish`, `request-query`, `with-http-response`, `with-http-body`) over
rontolisp's own HTTP server (`.kb/http-server.md`, `.kb/clack.md`).

**Re-evaluate when** something OTHER than a per-implementation compatibility layer is refused by
that gate — a `.asd` whose top-level code is portable CL computing component lists or pathnames.

### The _Practical Common Lisp_ book corpus, as a standing result

`practicals-1.0.3` (twelve ASDF systems over eleven chapters, loaded through this shim with
`--system-path`) diffed byte for byte against SBCL 2.2.9.debian on the interpreter. **The corpus
needed no shim, no replacement `.asd` and no leaf-module substitution.**

- Byte-identical: `spam` (ch.23, over the quicklisp cl-ppcre), `binary-data` (24), `id3v2` (25),
  `mp3-database` (27), `html` (30/31), `macro-utilities` (8) and `profiler` (32)'s
  `show-timing-data`.
- Differ ONLY by the missing right margin: `simple-database` (3), `test-framework` (9),
  `pathnames` (15).
- Chapters 15, 25 and 27 need `--feature sbcl`; 26/28/29 are the `net.aserve` rows above.
- `macro-utilities` used to diverge because `gensym`'s default prefix was lowercase `g` — a name
  like `"g3"` needs `|...|`-escaping under `:case :downcase` where SBCL's uppercase `"G3"` does
  not. Fixed by matching CL's uppercase `G` default (a name-model conformance miss). The counter
  value itself never matches across implementations regardless.
- `fifth`..`tenth` follow the same `(nth k x)` macro expansion as `first`..`fourth`, on all four
  backends and the compiled `eval` runtime interpreter.
