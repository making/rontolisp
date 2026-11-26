# asdf (limited system-definition subset)

An API-compatible mini-ASDF, **not a port**. Shared core `eval/AsdfSystems.java` LOADS a `.asd`
file as Lisp, form at a time, and parses each `defsystem` form **as plain data** (the recording
operator) -- see "What a `.asd` may contain". Component METAOBJECTS and the OPERATION PROTOCOL are
real at run time ("The operation protocol"); no fasls, no output-translations. Docs:
`doc/*/guides/asdf-systems.md`, `reference/functions/asdf-{defsystem,load-system,perform,operate,...}.md`.

## The surface is the inventory (h95, 2026-11-04)
`src/main/resources/am/ik/rontolisp/asdf-exports.txt` (`AsdfExports`): every export of every
`asdf/*` package in SBCL 2.2.9's ASDF 3.3.1 (230 externals of `asdf`, 448 sub-package rows). Why:
an absent name stops the whole file at READ, even on a line never reached (prove, cl-project, caveman2).
- **One spelling per symbol** (`PackageRegistry`): `asdf` owns every member homed in an `asdf/*`
  package (external only when `asdf/interface` exports it); a member homed in `uiop/*`
  (`asdf:hostname`, 24 externals) IS uiop's symbol, so `asdf-user` uses both without a shadow. No
  `asdf`-homed name collides with a uiop export. Trap: `asdf.lisp` is not resolved on the interpreter,
  so it must spell the canonical form (`asdf:version`, not `asdf::version`). `*asdf-version*` is
  internal, as in real ASDF.
- **Real definitions** are `asdf.lisp`; a public name it defines must be an inventory name
  (`buildDefinitions` fails otherwise), plus `::` internals a library imports (`asdf/bundle::link-op`).
  **Everything else gets a stub** from its kind (`AsdfRuntimeLibrary.stubForms`), signalling
  `uiop:not-implemented-error`; a generic's default method signals so a library's method works. A
  flattened superclass list keeps only the most specific (no linearization otherwise). Bundle ops other
  than the image pair are refusals ("Building an executable").
- **Who carries what**: the interpreter evaluates `asdf.lisp` and every stub on the first ASDF name. A
  compile path splices `asdf.lisp`, the stubs the program names (read off a RESOLVED copy,
  `mentionedMembers`) and the registry. `LibraryDefunPruner` may prune `asdf.lisp`'s defuns
  (`mayDefine`) except `LOWERING_TARGETS`; `defclass`/`defgeneric`/`defmethod` are roots.
- **Metadata**: `LispSystem.metadata` keeps options as written; a `#.` in them is never evaluated, so the
  reader answers nil where real ASDF has the value (usually a README read). Baked only for a program
  naming a reader (`METADATA_READERS`).
- **`*central-registry*`** is searched first, as real ASDF does. Interpreter: each entry evaluated per
  search (`centralRegistryDirs`); compile path: a folding top-level `push`/`pushnew`
  (`centralRegistryPush`) goes on `Ctx.centralRegistry`.
- **`find-system` searches on the interpreter** (`locateSystem`): runs the `.asd`, downloads nothing,
  loads no system. A compile path answers its baked table.
- **Conditions**: `missing-component` / `missing-dependency` (`requiringSystem`); interpreter wraps a
  `.asd` error as `load-system-definition-error` (`%asdf-call-loading-definition`); compile path:
  `path: message`. A handler naming an ASDF class loads the runtime first (`handlesAsdfCondition`).
- **Other load spellings at top level** (`loadSystemsNames`): `oos`/`operate` with quoted `load-op` /
  `load-source-op`, `load-systems`, `load-systems*`, `require-system` splice and are consumed; `test-op`
  and `make` splice and stay (`make` decides at run time from `:build-operation`); a folding `load-asd`
  runs in the build image.
- **`clear-system`** on a compile path forgets until the next `find-system` (a new instance of the baked
  record; loading loads nothing). Interpreter: the next load reruns the file.
- Verified against SBCL 2.2.9's ASDF 3.3.1 on all four with `AsdfMetaobjectsE2eTest`'s `registry/`
  systems (metadata `#.` and a symbol's printed package aside).
- Pinned by `AsdfMetaobjectsE2eTest` (every external named),
  `PackageResolverTest#shimPackagesExportEveryExternalOfTheRealLibrary`, `LispEvaluatorAsdfTest`
  (search, conditions, `clear-system`, `load-asd`),
  `LoadInlinerTest#aProgramCarriesOnlyTheStubsAndMetadataItNames`.

## What a `.asd` may contain: it RUNS (h35, 2026-10-30)
`AsdfSystems.loadAsd` loads the file into an `AsdImage` FORM AT A TIME (tolerant `#.` mode), each form
read with the features widened by what the file pushed so far, as CL's `load` (cl-pdf's zlib guard).
Interpreter: the image is the program's evaluator. Compile path: `LispEvaluator.buildImage` (created on
the first `.asd` only), reading with the TARGET's features and SHARING `ctx.systems()` /
`ctx.systemPackages()`; nothing reaches the program but the recorded plan. A file opens
(`LispEvaluator.AsdLoadScope`) in `asdf-user`, `*load-pathname*` set, `*features*` BOUND.
- **Recognized by SHAPE, before anything runs**: `defsystem` in any package spelling (`parseDefsystem`
  on the RAW form, so a test-op body keeps unresolved symbols); `register-system-packages`; a
  component-class `defclass`; `defmethod` (ignored except the `source-file-type` pre-pass); `progn` and
  load-firing `eval-when` flattened (`firesOnLoad`), `(:compile-toplevel)`-only dropped; a top-level
  `#.` marker dropped (the ASDF version guard: evaluated, it refused bordeaux-threads, fiveam, etc.).
- **`asdf::*asdf-version*` is `"3.3.7"`** (uiop's contract version). Below 3.1.5 lisp-binary loads
  its bundled 14k-line `asdf.lisp`, which refuses the implementation. Pin: `AsdfMetaobjectsE2eTest`
  (the gate in `meta-demo.asd`, all four).
- **The `.asd` read's feature set is the loader's STATIC one** (evaluator `features` plus `--feature`),
  never run-time `*features*`: a push ahead of `ql:quickload` does not reach the `.asd`'s `#-`; a
  `--feature` does. Reason: determinism (below) -- a plan must not depend on which systems loaded
  first. (Source reads, unlike `.asd` reads, do see earlier pushes:
  [reader-features.md](reader-features.md), "A source's own feature push".)
- **Everything else is EVALUATED** (`AsdLoad.eval`; an unreadable `#.` fails naming it): package
  headers, `warn`, `find-system` (downloads nothing: cl-containers' `asdf-system-connections` probe
  answers nil), defuns/defvars, `error` (the library's own message), feature pushes however computed.
- **A push is the FILE's declaration**: undone when the file closes; `AsdLoad.pushedFeatures` is merged
  into every system defined AFTER it. Deviation from real CL (SBCL keeps the push): the determinism
  decision. A `#+` ABOVE a push does not see it.
- **A system the file's own forms load** (`AsdLoadScope.isOwnForm`, `takeLoadedSystems`) leads the
  `:defsystem-depends-on` of every later system in the file (`withEarlierLoads`), so compile paths
  splice it ahead, matching the interpreter, which loads it OUTSIDE the file's bindings
  (`loadOutsideTheFile`). SBCL reads a component without `in-package` in the `.asd`'s package; compile
  paths cannot (the package lives in the build image), so every backend uses the caller's. Real ASDF
  refuses a system the reading `.asd` itself defines (circular), so a fixture's tool system lives in
  its own `.asd`.
- **An `asdf:defsystem` that EVALUATION reaches** (inside `when`, from a macro) is recorded via
  `evalDefsystem`, from the RESOLVED form.

### Options
- `:components` in ASDF's PLAN order (`LispSystem.files`; `placeComponent`): definition order, each
  after its `:depends-on` siblings, depth first. Trap: a topological sort placing dependency-free
  components first broke arnesi (`(:file "debug" :depends-on ())` ahead of `packages.lisp`). Matches
  SBCL 2.2.9's `asdf::make-plan` on arnesi and alexandria.
- Component `:pathname` decouples NAME from path. A string gets the class's extension even past a dot
  (`"d.cl"` -> `d.cl.lisp`); a `#P` literal is verbatim, as `parse-unix-namestring`. Empty adds no
  level. **A computed `:pathname` is a hard error.**
- `locate()` *attempts to read* `NAME.asd` through `SourceLoader` (no existence check: the browser
  playground's in-memory loader). `lib/tests` maps to the primary `.asd`.
- `:if-feature` keeps the component in the graph but drops its files; `(:feature EXPR DEP)`
  (`dependencyName`); a surviving `(:require MODULE)` is a hard error; elements past the dependency
  are IGNORED, as `resolve-dependency-combination`.
- `(:version NAME "1.2.3")`: constraint NOT checked.
- **`:rontolisp-features (...)`** — rontolisp-added (`AsdfSystems.declaredFeatures`), **additive
  only**; a DEPENDENCY keeps the outer set. Needed by replacement `.asd`s deciding statically
  (postmodern).
- **`:defsystem-depends-on`** (`LispSystem.defsystemDependsOn`) resolved BEFORE `:depends-on`,
  deliberately NOT merged into `dependsOn`: `component-sideway-dependencies` must not list it (all four).
- **`:weakly-depends-on`**: the parse has no search path, so a loader resolves it just before loading
  (`resolveWeakDependencies`). Never downloads. A missing `lib/sub` of a found `lib.asd` is a hard
  error. Component-level stays refused.
- **`:encoding`**: `:utf-8`, `:default`, nil; anything else refused, as source is only read as UTF-8.
- **`IGNORED_OPTIONS`** is the CLOSED list, so an option added later is load-bearing by default.
  `:version` (`declaredVersion`, ASDF's `normalize-version`): a string, `:read-file-form` /
  `:read-file-line` through uiop's `safe-read-*`; nil for anything not dotted integers. Consumer:
  coalton's eclector version check. Pin: `AsdfMetaobjectsE2eTest` (`meta-demo/tests`), all four.

### Options around the compile: `:around-compile` (h94, 2026-11-04)
ASDF calls the hook around `compile-file` (or the source `load` for `load-source-op`); the slot
inherits (`around-compile-hook`: nearest bound, so nil on a child means no hook).
- **Parse** (`aroundCompileHook`, `LispSystem.aroundCompile`, one hook per file): a `.asd`'s image homes
  a lambda/symbol where the file reads it; a string is read at the call in `cl`; an inline `defsystem`
  keeps the form.
- **The call** (`LispEvaluator.callAroundCompile`) is always in an INTERPRETER: the program's own, or a
  compile path's build image (`LoadInliner.spliceSystem`). A hook that never calls the thunk fails the
  load. A hook naming a function first loads the `:defsystem-depends-on` in the image (a lambda does
  not, so coalton's image never loads its compiler).
- **What reaches a compile path's program**: only the hook's added features, twice -- the read
  (`spliceComponent`) and macro time (`LispMacroExpander.loadContextFeatures`,
  `UserMacroExpander.Session` `pushFeaturesBinding` until `%end-file`). No `*package*`/`*readtable*`,
  no REMOVED feature (`Features` is additive on purpose).
- **Why macro time too**: coalton never tests `#+coalton-lisp-toplevel`; its parser asks
  `(alexandria:featurep :coalton-lisp-toplevel)` while `coalton-toplevel` EXPANDS. A read-only hook
  would pass a read test and fail coalton; the fixture pins both.
- **Divergences**: the interpreter runs the component's top-level forms inside the hook
  (`load-source-op`); a compile path runs them after it (`load-op` of a fasl). The interpreter expands
  a macro inside a `defun` at first run ([interpreter-expansion-memo.md](interpreter-expansion-memo.md)),
  past the hook. Hook output goes to stderr on a compile path. A leaf-shim component gets no
  macro-time binding.
- Consumers: `autoload` (`mgl-pax.asdf:compile-pax`, interpreter and JVM), clingon
  (`eval/WithUserAbortForms`), coalton.
- Pinned by `AsdfMetaobjectsE2eTest` (all four: lambda, symbol, string, nil), `AsdfSystemsTest`,
  `LispEvaluatorAsdfTest`.

### `#.` in a `defsystem` — the CONSUMER decides
- The tolerant lexer (`ReadEvalMode.SKIP_UNREADABLE`) re-lexes the datum into a `(%read-eval datum)`
  (`LispNames.READ_EVAL`) or `(%read-eval-unreadable "RAW")` (`LispNames.READ_EVAL_UNREADABLE`)
  marker: **one datum either way**, so a plist's pairing never shifts.
- **A marker inside a `defsystem` is not resolved where it is read**: `parseDefsystem` resolves per
  option (`resolveReadEval` -> `AsdLoad.readEval`, `AsdContext`; inline gets `AsdContext.NONE`). So
  cl-postgres' `(:file #.*string-file*)` reads the `defparameter` evaluated above it.
- **Outside `IGNORED_OPTIONS` a failure is a HARD ERROR** naming the `.asd` and clause: a silent nil
  surfaces much later as an undefined symbol far from the cause. Inside, a marker is never evaluated.

### Feature announcements from a dependency
- `BuiltinSystems.declaredFeatures` is read at PARSE time, so a dependency's announced names hold for
  this system's `:if-feature`/`(:feature ...)` and component reads. **Only a BUILT-IN system
  announces** (a real one announces by RUNNING, after the parse). Divergence: it also reaches this
  definition's own `:if-feature` (one-pass parse).
- What any system's files PUSH reaches every later source read
  ([reader-features.md](reader-features.md), "A source's own feature push"), including
  `BuiltinSystems.trivialFeaturesForms` -- but not `:if-feature` clauses, decided at parse. Real ASDF
  decides `:if-feature` when it plans, after that load: still a divergence.

### The component-class surface
Only two things reach the plan from a component class: whether it contributes a SOURCE file, and the
EXTENSION (`AsdfSystems.ComponentClass`, per-`.asd` `ComponentClasses`, read from SHAPE). Other names:
"Classes loaded code defines".
- `BUILTIN_COMPONENT_CLASSES`: `cl-source-file` (`DEFAULT_COMPONENT_CLASS`), `.cl`, `.lsp`,
  `static-file`, `doc-file`, `html-file`.
- A top-level `defclass` whose superclasses all resolve in the scope declares one (`componentClass`); a
  `(type :initform "cl")` slot sets the extension. Any other `defclass` RUNS.
- `(defmethod source-file-type ((c CLASS) (s module)) "ext")` is collected in a PRE-PASS
  (`collectSourceFileTypes`), so position does not matter -- **the CLASS still must precede its use**.
- **Every other top-level `defmethod` is ignored, not run**: (1) its effect would live only in the
  image, and the compile paths' build image never runs the program, so the backends would disagree
  (cl-store, esrap, cl-smtp, rt); (2) the common `(eql (find-system :x))` specializer FORM is refused by
  the interpreter's `defmethod` (22 of 339 cached `.asd` files). A method in a library's SOURCE runs.
  **Re-evaluate when** a loadable library's `.asd` method carries behavior its program observes.
- A component type is any symbol, as `class-for-type` (`ComponentClasses.named`).
- **The `type` slot is the class's, not the extension** (`ComponentClass.slotType`), as SBCL 2.2.9.
- A `.asd` loaded as a component source registers its system, loading nothing (all four).
- **`:default-component-class`**, on system AND module, is NOT in `IGNORED_OPTIONS`.
- Driving library: **portableaserve**.

### Classes loaded code defines (h93, 2026-11-04)
**The split**: the IMAGE answers what the plan needs, the PROGRAM holds the instances. `.asd` methods
stay ignored (they would act only in the build image); a class from a dependency's SOURCE has its
methods in the program too.
- **Lookup** (`ClassResolver`, `%asdf-class-facts`, `AsdfRuntimeLibrary.IMAGE_SOURCE`): ASDF 3.3.1's
  `class-for-type` over `coerce-class`, as SBCL 2.2.9. Runs only on a miss, loading
  `:defsystem-depends-on` first; a compile path's image then tries the program's earlier systems
  (`setProgramSystems`: lass's "load it separately"). A compile needing no lookup loads nothing more (keeps
  coalton's compiler out of the image).
- **The answers**, off a prototype: the class, its kind, `source-file-type`,
  `module-default-component-class`, the `around-compile` slot, and whether a `load-op` primary leaves
  the file unread. `STUB_FILE_TYPES` gives stub classes their upstream `type`.
- **The plan**: `LispSystem.loadedClasses`; class/kind names are STRINGS because the table stands ahead
  of the packages. Unread options are initargs on a loaded class only (`lass-file`'s `:output`).
- A non-`cl-source-file` class's file (`lass-file`) is opened by no loader; the component bracket stays,
  so its actions are performed.
- **Instances**: `%asdf-make-system` / `%asdf-make-component`. Compile path: defuns generated BEHIND the
  program (`classDispatch`), one literal `make-instance` per class a SPLICED system names, so
  `%mop-make-instance`'s computed-class gate stays off. A system is registered then
  `reinitialize-instance`d (`%asdf-reinitialize-system`), as `parse-defsystem` (hu.dwim.asdf's `:after`
  methods). `%asdf-set-metadata` does not overwrite initarg-set slots when the definition is silent.
- **A class the `.asd` itself defines**: the instance is of its nearest superclass outside the file
  (`programClass`, `noteEvaluated`).
- **A deferred file** (`LoadedClass.deferred`, `:DEFERRED`): real ASDF never loads a test file while it
  loads the system, so the macro-time pass registers definitions but does not replay plain top-level
  forms (`Session.openComponents`); otherwise a test suite runs at compile time.
- **Thunks stay callable**: `%asdf-load-spliced` records `(SYSTEM INDEX THUNK)` in
  `%asdf-spliced-thunks%`; `%asdf-load-component` runs it for a later `load-source-op`. One spliced
  before the first `perform` method has no thunk and signals.
- **Compile-op methods run in the program**, at its start: one plan order on every backend; a build-image
  run would lose what a `load-op` method records.
- **`:perform (test-op :after (o c) ...)`** is recorded like the plain spelling (17 cached `.asd`
  files); an unreadable `#.` in the body drops the clause silently.
- **Measured** (`(asdf:test-system NAME)` vs SBCL 2.2.9): prove-test, proc-parse-test, xsubseq-test,
  myway-test, ningle-test, cl-slug-test, smart-buffer-test, http-body-test, quri-test all pass.
  `fast-http-test` fails 3 checks on all four AND on SBCL: upstream's own (CONNECT `upgrade-p`; `Head
  response with Content-Length` without `:head-request t`). SBCL runs them over the cache with
  `(asdf:initialize-source-registry '(:source-registry (:tree "~/.rontolisp/quicklisp/software/")
  :ignore-inherited-configuration))`.
- **Refusal**: `unsupported component type` / `unsupported :class`; an inline `defsystem` on a compile
  path cannot see a class the program's own `defclass` defines.
- Pinned by `ProveE2eTest` (`prove-test.expected` from SBCL), `AsdfMetaobjectsE2eTest`
  (`classes-tool.lisp`, all four), `UserMacroExpanderTest` (deferred replay),
  `AsdfSystemsTest#aPerformBodyReadEvalReadsInTheImage`.

## The search (i25, 2026-11-05)
`asdf-search.lisp`, ported from ASDF 3.3.1 (`~/.rontolisp/quicklisp/software/lisp-binary-*/asdf.lisp`);
the configuration DSL is `uiop-configuration.lisp` (`.kb/uiop.md`). Order:
`*system-definition-search-functions*` (real ASDF's three), then the download cache.
- **Interpreter**: `locateAsd`/`locateSystem` -> `searchSystemDefinition` -> `%asdf-search-file`. A
  function answers a pathname (a string is a `type-error`, as there) or a system, adopted empty
  (`%asdf-adopt-system`). Searched per lookup, so a `let` of either variable counts.
- **What it inherits is the search path**, not `~/common-lisp`, `~/.config` or `CL_SOURCE_REGISTRY`:
  only `--system-path` and `RONTOLISP_SOURCE_REGISTRY` (`%asdf-inherited-search-path`;
  `LispEvaluator.searchPathDirs`: the asking file's directory, then `systemPath`). **Probed at each
  search, never scanned** (a `.asd` written later counts; the playground's loader cannot list), so it
  is a mark in `%asdf-flattened-entries%` between `*source-registry*` and `%asdf-registry-behind%`.
  `:ignore-inherited-configuration` drops it but keeps the download cache (`distDirs`), as Quicklisp's
  own search function survives in real ASDF.
- **Compile path**: `AsdfSystems.sourceRegistryCall` recognizes a top-level literal
  `initialize-source-registry` / `clear-source-registry`; `LoadInliner.readSourceRegistry` runs it in
  the build image (`%asdf-read-source-registry`, the one DSL implementation) into `Ctx.searchDirs`.
  **A pushed function is not called while splicing**: the program has not run.
- **At run time on a compile path** (`searchAtRunTimeDefun`): carried only by a program naming a member
  (`searchMembers`), since `process-source-registry`'s methods would be pruner roots. An unspliced
  `.asd` found then reaches `%asdf-load-asd`'s refusal (signals, where `nil` was answered before). A
  literal `(setq *s-d-s-f* nil)` is not read.
- **Divergences**: a `.asd` configuring the registry does it in the build image only (compile path).
  `find-system-if-being-defined` and the immutable search are not in the walk. A central-registry
  entry may lack its trailing slash; real ASDF signals.
- **`(require :asdf)`, `(require :uiop)`** load nothing (`AsdfSystems.isImplementationModule`), all
  four; every measured script opens with one. Nested: [load-inliner.md](load-inliner.md),
  `NestedRequires`.
- Measured: fiveam's `build.lisp` / `test.lisp` (57/57) and cl-json's `test-cl-json.lisp` run;
  `compile-cl-json.lisp` stops at `compile-op` ("The operation protocol").
- Pinned by `AsdfMetaobjectsE2eTest` (`source-registry/exercise.lisp`, `source-registry.expected`, also
  on SBCL), `LispEvaluatorAsdfTest`,
  `LoadInlinerTest#aLiteralSourceRegistryCallDecidesTheSearchWhileSplicing`,
  `#aProgramCarriesOnlyTheStubsAndMetadataItNames`.

## `:class :package-inferred-system`
Consumers: ningle, rove, array-operations, jose. `LispSystem.packageInferredDir` (non-null iff declared)
is the whole marker; rules in `AsdfSystems`:
- **A sub-system name is a FILE PATH** under the PRIMARY's directory; the primary is the part before the
  FIRST slash.
- **Dependencies come from the file's first `defpackage`/`uiop:define-package` only**
  (`packageDependencies`: `:use`, `:mix`, the `:reexport` family, first argument of
  `:import-from`/`:shadowing-import-from`). None is a hard error.
- **Package name -> system name** (`packageSystemName`): `register-system-packages`, else downcased;
  `cl`/`cl-user`/`keyword`/`asdf` drop out.
- Only forms up to the package declaration are read (`LispReader.readFirstFormMatching`); every source
  is opened, which is why the tolerant lexer may not WARN.
- Derivation on demand (`inferPackageInferredSystems`); a `defpackage` cycle terminates.
- Coverage: `AsdfSystemsTest` + `PackageInferredSystemE2eTest` (`src/test/resources/package-inferred-demo`).

## Interpreter / compile path / search order
- **Interpreter**: `asdf:defsystem` is an `evalConsRareOperator` case (`LispNames.ASDF_DEFSYSTEM`,
  options are data); `asdf:load-system` accepts computed names and performs each component through
  `asdf:perform` (-> `loadComponent` -> `loadFile`). State: `asdfSystems`/`loadedSystems`/
  `loadingSystems` + `systemPath`.
- **Compile path**: inside `LoadInliner`'s recursion (`Ctx`). A non-literal `load-system` name is a hard
  error at inline time; a *nested* asdf form is rejected like `REQUIRE`/`PROVIDE` (a `.asd`'s own
  `load-system` runs in the build image).
- **`load-system`/`quickload` keyword options are accepted and IGNORED** (`AsdfSystems.checkIgnoredLoadOptions`):
  nothing to drive (no fasl cache), and rejecting them made lack's `find-package-or-load` unloadable.
- `RontoLispCli.systemPath()` parses `File.pathSeparator` lists.
- **No ci-spec case is possible**: the compile path needs the `.asd` on disk at compile time. Coverage:
  `AsdfSystemsTest`, `LispEvaluatorAsdfTest`, `LoadInlinerTest`, the per-library E2Es.

### Compile-time pathname folding (`cli.CompileTimePathnameFolder`)
After `LoadInliner.inline`, folds load-time pathname calls (`system-source-directory`,
`system-relative-pathname`, `component-pathname`, `make-pathname` via `eval/PathnameOps.makePathname`,
`uiop:merge-pathnames*`) and records a top-level `(defparameter *X* <folded string>)`. A nested literal
`(asdf:find-system 'x)` in a designator position is unwrapped (`systemDesignator`).
- **A recorded value is a constant only for a variable NOTHING assigns** (`collectMutableNames`,
  anywhere incl. defun bodies: the pass cannot order a write against a use). Pin:
  `LoadInlinerTest#doesNotSubstituteAVariableThatTopLevelSetqRebinds`. Only top-level recording; a
  `defvar` never overwrites a recorded value.
- `(with-open-file (var <literal utf-8 path>) BODY)` -> `with-input-from-string` of the contents,
  **chunked at 20k Java chars** under the **JVM 65535-byte string ceiling**. `:element-type`
  SUPPRESSES it.
- **A path the program opens for OUTPUT is never bundled** (`collectWrittenPaths` pre-pass): stale data
  fails SILENTLY. Pins: `LoadInlinerTest#doesNotBundleAFileTheProgramItselfWrites`, ci-spec
  `open-if-exists-append-keeps-the-existing-content`.

## ASDF component metaobjects at run time
`eval/asdf.lisp` (`eval.AsdfRuntimeLibrary`) defines the classes, `find-system`, the readers and the
pathname functions (designator-accepting, answering NAMESTRINGS, `.kb/pathnames.md`). The one
per-backend seam is `%asdf-system-record`/`%asdf-system-names`; record `(CLASS DIR COMPONENTS DEPS
LOADED-P VERSION META ROOT)`.
- **Interpreter**: Java built-ins over `asdfSystems`/`loadedSystems`; a built-in shim answers before it
  loads. `asdf.lisp` loads lazily (`ensureAsdfRuntimeLoaded`, `mentionsComponentClass`).
- **Compile paths**: `CompileTimePathnameFolder.fold` FIRST (a program whose only asdf use folds away
  splices NOTHING), then `AsdfRuntimeLibrary.process` prepends `asdf.lisp` + `%asdf-registry%`. The
  `ASDF_LOAD_SYSTEM`/`QL_QUICKLOAD`/`ASDF_FIND_SYSTEM` compiler cases are only the no-pipeline fallback.
- `asdf.lisp` and `asdf-exports.txt` need `resource-config.json` entries (native image).

### The component tree (i23, 2026-11-05)
Children are real instances in definition order (djula, cl-dejavu, mcclim, swank-asdf, serapeum walk
them); the plan is a separate table.
- `AsdfSystems.ComponentTree` (`LispSystem.tree`): `children` and `plan` share `ComponentNode` objects,
  so plan index is by identity (`AsdfRuntimeLibrary.nodeRecords`).
- Record `(KIND NAME RELATIVE RESOLVED PLAN-INDEX . OPTIONS)`. KIND: keyword for ASDF's classes (one
  literal `make-instance` each, for `%mop-make-instance`'s gate), string for a loaded class.
- **Divergence**: real ASDF's plan also has actions on a module and a static file, so a `perform` method
  on either runs there and not here. A disabled `:if-feature` component has no plan index: load-op on it
  signals.
- `component-pathname` of a system is its `:pathname` directory; `system-source-directory` the `.asd`'s.
  A designator is accepted where real ASDF signals.
- `find-component`, `component-pathname` are generics with real ASDF's methods (swank-asdf's
  `:around`); `"m/two"` finds nothing, as there.
- A package-inferred sub-system's child is named `"lisp"`, as real ASDF's.
- Measured vs SBCL 2.2.9: `registry/tree-exercise.lisp` prints `meta-demo-tree.expected` (re-derive on
  SBCL).
- Pinned by `AsdfMetaobjectsE2eTest` (all four), `PackageInferredSystemE2eTest` (`"lisp"`).

### test-op
- `:perform (test-op (o c) BODY...)` -> `LispSystem.testOp`, body pre-qualified by
  `normalizeAsdUserForm`; `:in-order-to` -> `testOpEdges`.
- `LispSystem.packageInferredClass`: rove's `run-system` typecase branch.
- `asdf:test-system` is `asdf.lisp`'s on every backend; `o` is the `test-op` INSTANCE. Seams
  `%asdf-test-op-edges` / `%asdf-run-recorded-test-op` (compile paths: `testOpEdgesDefun` /
  `recordedTestOpDefun`). A top-level literal `(asdf:test-system NAME)` splices the system AND its
  test-op closure (`spliceTestOpClosure`) and KEEPS the call.
- Pinned by `AsdfMetaobjectsE2eTest` (`src/test/resources/asdf-metaobjects-demo`),
  `PackageInferredSystemE2eTest`, `AsdfSystemsTest`'s test-op group,
  `LoadInlinerTest.nestedLoadSystemOfASplicedSystemAnswersNilOnJvm`.

## The operation protocol
`asdf.lisp` defines real ASDF's operation classes and hierarchy, `make-operation` (one instance per
class), the generics `perform`, `input-files`, `output-files`, `source-file-type`, `operation-done-p`
(default `t`), and `operate`: load-op/load-source-op -> `load-system`, test-op -> `test-system`,
build-op -> `:build-operation`, an image op -> "Building an executable", anything else SIGNALS (nothing
for compile-op alone to produce). `compile-bundle-op` is a stub class `operate` refuses.
`asdf/bundle::link-op` and `bundle-pathname-type` are real (cffi-toolchain). Default methods are ASDF
3.3.1's; a designator op dispatches through a `symbol` method.
- **The plan is fixed**: `%asdf-perform-system NAME :before`, `%asdf-perform-component NAME I` per file,
  then `:after` -- real ASDF's action order with a fresh cache. Compile-op runs each time (a source file
  compiles BY loading); `operation-done-p` is never consulted for the fixed operations.
- **Build actions** (cffi-grovel's `process-op`): what `component-depends-on` names on the component
  ITSELF beyond the fixed five, skipped when real ASDF's stamps say done (outputs present, not older
  than inputs, `operation-done-p`). A load-op then reads `(first (input-files compile-op c))` when it
  differs (the groveller's generated Lisp). Image-only Lisp (`AsdfRuntimeLibrary.IMAGE_SOURCE`:
  `%asdf-build-actions`, `%asdf-perform-build-actions`, `%asdf-component-source`). A compile path
  loads the SYSTEM into its build image (`LispEvaluator.buildComponentSource`) and splices the
  generated file. Divergence: a compiled program performs no build action at run time, as a delivered
  image does not. Pinned by `AsdfMetaobjectsE2eTest` (`classes-tool.lisp`'s `generated-file`, SBCL's
  answers, all four) and `CffiGrovelE2eTest`.
- **Interpreter**: load-op reaches `%asdf-load-component` -> `loadComponent` (self-contained: performing
  load-op again reloads). **A loaded file's forms are top-level forms** in the global scope, no
  `lexicalPackage` (`.kb/packages.md`), so a macro expands in the file's package (otherwise
  lisp-namespace interned trivia's `symbol-optimizer` wrongly; `TriviaE2eTest`).
- **Compile paths -- the component as a thunk.** `LoadInliner.spliceSystem` brackets every component with
  `(%asdf-component "SYSTEM" I)` / `(%asdf-component-end)` and `(%asdf-system-op "SYSTEM" :before|:after)`
  (`compiler/SplicedComponentGroups`). `AsdfRuntimeLibrary.process` STRIPS them unless a form defines an
  `asdf:perform` method (`definesPerformMethod`, any depth, so a macro template counts): every other
  program is byte-identical. Lowered after `lowerLoadContextMarkers` (`SplicedComponentGroups.lower`;
  JVM, wasm-GC, the CLI dump; NoGc REFUSES): after the first perform definition a component becomes
  `(cl-user::%asdf-load-spliced "S" I (cl:lambda () (cl-user::%run-top-group N)))` + `(%top-group N)`
  FORMS `(%top-group-end N)`. Forms stay TOP-LEVEL for every static pass; only the chunk emitters (JVM
  Pass 2b, `WasmToplevelEmit`) cut chunks and record `topGroupChunks`, called by `%run-top-group` (Pass
  2c). Groups NEST.
- **Divergence: compiled method dispatch is static** (`.kb/clos.md`): a perform method applies from
  program start. A top-level `await` inside a group is refused on wasm (`WasmAsyncEmit`), as is a
  top-level local read across a boundary (`WasmToplevelEmit`): a thunk cannot suspend the top level.
- **Packages**: `asdf/interface` and `asdf-utilities` are nicknames of `asdf` (as in ASDF 3); other
  `asdf/*` packages are import redirects; `asdf/defsystem` nicknames `asdf/parse-defsystem`,
  `asdf-action` `asdf/action`. No `asdf/user`.
- Consumers: fiveam through the REAL asdf-flv (`*suite*` file-local exactly as on SBCL 2.2.9); lass.
- Pinned by `AsdfMetaobjectsE2eTest` (all four: `hooks.lisp` with asdf-flv's `:around`; `a`/`b`/`c`
  pin order, file-local binding, nesting), `LispEvaluatorTest#loadEvaluatesFileAndReusesDefinitions`,
  `UserMacroExpanderTest#macroExpandingToDefmacroIsConsumedToo` (`.kb/defmacro-backquote.md`).

## Building an executable: `program-op`, `asdf:make`, `rontolisp build` (i83, 2026-11-06)
**Decision: the interpreter is the image a build runs in, and `uiop:dump-image` COMPILES that
image.** No backend has a heap to save, so ECL's model, not SBCL's. `asdf:make`, `rontolisp build
SYSTEM` and a build script's `(asdf:make :app)` are one path. Rejected: compiling the system with its
entry point beside ASDF -- deploy's build-time hooks would have nowhere to run, and every Makefile's
`--eval '(asdf:make :app)'` would need rewriting.
- **ASDF 3.3.1's protocol**: `make` -> `build-op` -> `:build-operation` (`%asdf-build-operation`) ->
  `%asdf-perform-image`. Its methods are upstream's in `AsdfRuntimeLibrary.IMAGE_SOURCE`, kept out of
  `asdf.lisp` because `ensure-function` reaches `eval`, which every compiled program loading a system
  would then carry.
- **Where it refuses**: a compile path's `%asdf-check-image-build` seam and `%dump-image`; an evaluator
  without `ImageBuilder`. Each names `rontolisp build SYSTEM`. `image-op` alone (a core) refuses.
- **The dumped program** (`eval/ImageDump`): `(asdf:load-system NAME)` per system asked for at top
  (`imageSystems`), then `(setf uiop:*image-dumped-p* :executable)` and `(uiop:restore-image ...)`. The
  target reads it with its OWN features (deploy's cffi drops on `.wasm`). Refused by name: a closure,
  an instance, a system no `.asd` defines. NOT carried: what the image evaluated outside a system,
  build-time state, restore hooks registered after the load.
- **The start** is upstream's `restore-image` (all four), exiting with `shell-boolean-exit` of the
  entry point's value because `*lisp-interaction*` is nil here ([uiop.md](uiop.md)). SBCL's
  program-op image exits 0 regardless: the one divergence.
- **Inside a running program**: `dump-image` without `:executable t` signals `uiop:not-implemented-error`
  on all four BEFORE the protocol (no side effect; upstream runs hooks then refuses), as does every dump
  in a compiled program. `restore-image` is NOT a refusal: under `*lisp-interaction*` nil it ends the
  process, so a ci-spec case calls it with `:lisp-interaction t`. Trap: a case that ends the program
  truncates the concatenated corpus; `JvmDeadMethodEliminationCorpusTest` counts its JVM lines
  ([test-execution.md](test-execution.md), "A case that ends the corpus").
- **The artifact's kind follows its name** (`RontoLispCli.imageBuilder`): no type `--native`,
  `.jar`/`.class` JVM, `.wasm` a module, `.war` refused. `-o` replaces the build pathname.
- **A jar's argv0 is its path** (`JvmArgvRuntimeBuilder`): deploy finds its data there.

### deploy: build-time hooks in the image, boot hooks in the executable
deploy 3.0.0 builds with its own methods on the interpreter (`:load`, `:deploy`, `:build` hooks, as on
SBCL); the executable boots as deploy boots (`:boot`, entry point, `:quit`). `deploy:data-directory` is
the executable's directory.
- **Derived forms** (tier 4, `eval/DeployForms`, pinned by `AsdfProgramOpE2eTest`): rontolisp arms of
  `command-line-arguments` and `envvar`; `runtime-directory` from argv0 only when it names a file; the
  dump's entry-point closure becomes `(call-dumped-entry ENTRY *build-time* *source-checksum*
  *data-location*)`.
- **Replacement `.asd`** (tier 2, `deploy-rontolisp.asd`): no cffi on a `.wasm` (refuses `ffi:`).
- `register-immutable-system`/`register-preloaded-system` are recorded only; a restart's `&optional`
  default is pinned by ci-spec `restart-system`.
- **Divergences**: `:pre-load` hooks (a `make-plan :before` method) never run (fixed plan);
  `*foreign-libraries-to-reload*` is not carried. SBCL's deploy image answers nil for `uiop:argv0`
  (its `*image-dumped-p*` is `t`).

## The exported surface, measured 2026-11-05
`asdf` 230 of 230 SBCL 2.2.9 externals, `uiop` 418 of 427 (a member may be a stub). In 232 Quicklisp
releases, 34 define a `perform` method in an `.asd`.

## The substitution ladder (five tiers, widest first)
1. **Whole shim system** (`eval/ShimLibraries` + `BuiltinSystems`).
2. **Replacement `.asd`** (`eval/AsdOverrides`) — system metadata only, real sources.
3. **Leaf-module shim** (`ShimLibraries.leafModuleForms`) — one component file.
4. **Derived forms** (`ShimLibraries.rewriteComponentSource`) — individual FORMS of a component.
5. **Generated component** (cl-unicode) — a component absent from the release.

Beyond the end: **refused systems** (`ShimLibraries.refusalReason`): a name -> a SENTENCE, checked in
both loaders. `cffi-libffi` (structs by value already work); `cffi-grovel` only without a C compiler or
for a WASM target (`.kb/cffi.md`).

### Tier 1: built-in shim systems
Bundled shims (`src/main/resources/am/ik/rontolisp/eval/*.lisp`) resolved instead of downloading.
`BuiltinSystems.DEPENDENCIES`: `flexi-streams -> trivial-gray-streams`. `ShimLibraries.forms` takes the
TARGET backend's `Features`.
- `usocket`; `trivial-gray-streams` (`.kb/gray-streams.md`); `closer-mop` (`.kb/clos.md`);
  `flexi-streams`; `float-features` (interpreter + JVM, no WASM); `bordeaux-threads` (`bt`,
  `.kb/mutexes.md`); `trivial-garbage` (`finalize` registers nothing: a leaked prepared statement lives
  until the connection closes); `trivial-cltl2` (`declaration-information` always nil, routing trivia's
  match2*+ onto `:trivial`; `function-information` global kind only, for typo); `cl+ssl`
  (`.kb/tcp-sockets.md`); `mgl-pax-bootstrap`; `swank` (`create-server` on the interpreter,
  `.kb/swank.md`; SIGNALS in a compiled program -- clack's `.asd` hard-depends on it).
- **`trivial-features`** — its whole content IS the announcement (`BuiltinSystems.DECLARED_FEATURES`
  + generated `pushnew`s from the same list, so read-time and run-time cannot drift): **`:unix`**,
  **`:little-endian`**, **`:64-bit`** (cffi's `types.lisp`). **The HOST half is a probe, not a table**
  (`BuiltinSystems.hostFeatures`: `:darwin`+`:bsd` or `:linux`, `:arm64` or `:x86-64`), JVM-family
  targets ONLY (`declaredFeatures(names, target)`): cffi's library resolution runs on `featurep`
  (`:arm64` puts `/opt/homebrew/lib` in the DYLD fallback). Unknown OS/CPU announces NOTHING;
  `:32-bit`/`:windows` never. **Base `Features` sets stay machine-independent** (ci-spec
  `reader-features-variable` counts `*features*`).
- **`babel`** (`babel` + `babel-encodings`) — upstream's two layers; the MAPPING protocol IS the codec
  and `string-to-octets`/`octets-to-string` drive it, because an INCREMENTAL decoder (dexador's
  `decoding-stream.lisp`) has no whole vector. **The counter and the decoder MUST agree on where a
  character ends** (else a wrong-length string, not a crash): 5-/6-octet UTF-8 forms consumed WHOLE by
  both. **Both packages are Java-seeded, so a `.lisp`-only widening publishes nothing** — a new name
  needs its `PackageRegistry` entry. Three encodings (`:utf-8`, `:iso-8859-1`, `:ascii`, upstream's
  canonical names). Every external exported
  (`PackageResolverTest#shimPackagesExportEveryExternalOfTheRealLibrary`; output = SBCL with real babel,
  `BabelMappingE2eTest`); `enable-sharp-backslash-syntax` and `instantiate-concrete-mappings` signal by
  name. Trap: cxml's `*catalog-dtd*` reads `catalog.dtd` at start-up from the compile-time path, so a
  `.wasm` without that directory preopened stops on `OPEN: cannot open file`.
- **`uiop`** — definitions through `eval.UiopLibrary` (`.kb/uiop.md`).
  - **`uiop:symbol-call` is REAL on every backend**: the compile paths lower it in `expandUiopStubCall`
    to `(funcall (intern (string name) (find-package pkg)) args...)` AND carry uiop's definition, since
    the fold covers CALL position only while the idiom is usually a VALUE
    (`.kb/symbol-runtime-api.md`). lack's `src/util.lisp` spells it with a SINGLE colon.
  - `print-condition-backtrace` is homed in `uiop/image` and IMPORTED into `uiop`: one symbol.
  - `uiop:run-program`: JVM family only; signals on both WASM backends (`.kb/uiop.md`,
    "uiop/launch-program and uiop/run-program").
  - **`with-temporary-file` is a MACRO** (built-in expansion in `LispEvaluator.evalCons` and both
    expression compilers). A LITERALLY true `:keep` drops the delete (smart-buffer on WASM);
    `%temp-file-name`/`uiop:delete-file-if-exists` must be selected by
    `LispPreludeLibrary.referencedBySurfaceForm` and rooted in `LibraryDefunPruner`, or
    "%TEMP-FILE-NAME is undefined".
  - **`EnvironmentLibrary.process` runs AFTER the whole library-splice chain** (`TMPDIR` via
    `uiop:getenv`).
- **`clack-handler-rontolisp`** — inverts two conventions for clack's late-bound discovery: its package
  is NOT seeded in `PackageRegistry` (lack's `find-package-or-load` loads only when `find-package`
  MISSES), and it is registered under TWO system names (plus dotted `clack.handler.rontolisp`). The
  interpreter's `find-system` answers a BUILT-IN name before it loads; `loadSystem` evaluates shim forms
  through the RESOLVING `eval(form)`; `LoadInliner.spliceSystem` splices it EAGERLY after `"clack"`.
  `.kb/clack.md`.

### Tier 2: replacement `.asd` files (`eval/AsdOverrides`)
Maps the `.asd` FILE NAME to a bundled replacement (`eval/*.asd`); `AsdfSystems.locate` substitutes it
**after locating the real file — keeping the located PATH**, so components resolve against the real
tree. A few-form difference in a copyleft `.asd` (clsql's LLGPL, petalisp's AGPL-3.0) is no bundled
near-copy: `AsdOverrides.REWRITES` edits the located text by exactly-once markers
(`.kb/java-frontend-provenance.md`).

| `.asd` | replacement | reason |
| --- | --- | --- |
| `ironclad.asd` | `ironclad-slice.asd` | component classes, `defmacro`-generated defsystems, `perform :around` |
| `cl-postgres.asd` | `cl-postgres-deps.asd` | under-declared deps (alexandria, cl-ppcre, usocket) + `#.*string-file*` |
| `postmodern.asd` | `postmodern-deps.asd` | top-level `eval-when` pushing features per implementation |
| `trivia.asd` | `trivia-trivial.asd` | routes `trivia` onto upstream's `trivia.trivial` base |
| `dbi.asd` | `dbi-deps.asd` | cache selection rides a thread-capability feature expression that can never match |
| `tiny-routes.asd` | `tiny-routes-lite.asd` | only to ADD the opt-in `tiny-routes/lite` |
| `cffi.asd` | `cffi-rontolisp.asd` | opens with `(error "Sorry, this Lisp is not yet supported")`; names rontolisp's `cffi-sys` (`.kb/cffi.md`) |
| `cl-unicode.asd` | `cl-unicode-built.asd` | drops the build system, `component-depends-on`, fiveam |
| `uffi.asd` | `uffi-cffi.asd` | guards its whole `defsystem` per implementation; replacement is cffi's `uffi-compat/uffi.asd` over `cffi-uffi-compat`. Interpreter and JVM; `.wasm` refuses naming `ffi:` |
| `clsql.asd` | its own text, edited (`AsdOverrides.REWRITES`) | loads uffi only for a `perform :after` that does not run here; `#-rontolisp` before both forms, declares `closer-mop`, so the core reaches wasm |
| `deploy.asd` | `deploy-rontolisp.asd` | a `.wasm` has no foreign libraries and refuses `ffi:`: reads deploy the mezzano way ("deploy: build-time hooks") |
| `petalisp.native-backend.asd` | its own text, edited (`AsdOverrides.REWRITES`) | `static-vectors` serves address-reading arms and refuses rontolisp; the rontolisp arm (`eval/PetalispForms`) reads none |

**Every bundled replacement `.asd` and every leaf-module shim `.lisp` also needs a
`resource-config.json` entry** (`AsdOverrides` / `ShimLibraries` `typeReachable`). A missing one fails
ONLY in the native binary (`<name> is missing from the classpath`) — `./mvnw test` cannot catch it.

RETIRED: `chipz.asd` -> `chipz-crc32-slice.asd`; the real one loads verbatim on all four (`ChipzE2eTest`).

### Tier 3: leaf-module shims (`ShimLibraries.leafModuleForms`)
Substitutes COMPONENT FILES inside a real system, consulted by both loaders before the read. A shim
carries the replaced file's `defpackage`, or `in-package`, bracketed like a real component
(`%push-package`/`%pop-package`, only when `selectsAPackage`). Keyed by SYSTEM name + path RELATIVE TO
THE SYSTEM'S BASE DIR (`src/prng/prng.lisp`); the file **need not exist on disk**.

A shim lives exactly as long as its motive:
- **portability**: jzon's `eisel-lemire.lisp`, `ratio-to-double.lisp`, `schubfach.lisp` (the `#.`
  power-of-ten table crash and u64/u128 arithmetic); cffi's `src/strings.lisp` (a babel code
  generator). Lazy macro-time globals did not retire the first: the special is a `defvar` with NO value
  form.
- **the backends already provide it**: ironclad's `src/prng/prng.lisp` -> `ironclad-prng.lisp`.
- **size of dependency, opt-in**: `tiny-routes/lite`'s `path-template.lisp`.
- **the implementation seam**: cffi's `src/cffi-rontolisp.lisp` (not upstream).
- **size of file** EXPIRES: **a size-motivated leaf shim lives exactly as long as the real file has no
  route in.**

### Tier 4: derived forms (`ShimLibraries.rewriteComponentSource`)
Substitutes INDIVIDUAL FORMS and hands the rest to the normal read. Each span's marker must occur
**EXACTLY ONCE** — a moved marker THROWS, naming marker and file. Tier 4 rather than a shim whenever the
rest of the file is portable CL.

**`eval/Uax15Tables`** (pinned by `Uax15TablesTest`). uax-15 builds its tables at LOAD time by parsing
2.7 MB of Unicode text through cl-ppcre. Two rewrites: (1) **derived spans**: tables become data plus one
BUILDER `defun` each; `*unicode-letters*` + the NINE hardcoded CJK/Hangul/Tangut loops become sorted
codepoint RANGES (`%lite-unicode-letter-p`); everything that COMPUTES a normalization is verbatim. (2)
**forced reads**: each of the NINE bare reads becomes `(or *T* (%lite-build-T))` -- at the read site, so
calling `uax-15::nfc` directly cannot bypass it. `Uax15Tables.FORCED_READS` is an explicit inventory; a
mismatch THROWS, as does an uninventoried component naming one of the five.

**The trap surface of the `(or ...)` protocol**: (a) every table global must start `nil` (two
`defparameter`s demoted to `(defvar ... nil)`); (b) reads inside `precomputed-tables.lisp` are NEVER
rewritten (they are `setf` places), so the composition-map builder forces its dependency first; (c)
`hardcodedLetterRanges` THROWS on any other loop spelling.
- The letter table is **GONE rather than lazy** (~127,000 entries -> 1,332 range integers): the table
  pays off only past ~11,000 calls interpreted, ~34,000-39,000 compiled, and the only corpus caller is
  postmodern's `valid-sql-identifier-p`. **If a program ever crosses that, the answer is a memo keyed by
  the characters it asks about, not the table back.** Search tuned for the INTERPRETER
  (`Uax15Tables.LETTER_PREDICATE`); ranges = the old key set MEMBER FOR MEMBER.
- **Bulk numbers are decimal runs inside STRING literals, never numeric literals**
  (`.kb/jvm-method-size-limits.md`), as a QUOTED LIST of **1,000-character chunks cut between
  integers**, because **`(char s i)` costs O(i) on every COMPILE backend**. **The interpreter is 1.0x on
  purpose** (`Environment.charRef` indexes) — **do not A/B the chunking rationale interpreted**.
- When the bundled data cannot be read, the real source loads eagerly and `rewriteTables` appends
  IDENTITY builders (plus a non-identity `%lite-unicode-letter-p`).
- Behavior changes: `(uax-15:unicode-letter-p #\A)` answers T where the real load answers NIL
  (`.kb/reader-features.md`); the internal table globals read `nil` until the API is called.
- **The module does NOT shrink**: an ASDF-spliced tree is never pruned
  (`.kb/library-defun-pruning.md`); laziness moves work in time, not out of the artifact (the letter
  table is the exception).
- Pinned by `Uax15E2eTest` (all four) and `Uax15TablesTest`.

**`eval/QuriEtldTables`** (pinned by `QuriEtldTablesTest`). `(defvar *etlds* '#.(load-etld-data))` is
two HASH TABLES, no literal syntax (`Cannot quote: #<HASH-TABLE>`). The `defvar` -> `nil` +
`%lite-build-etlds`, the three reads forced, and the `with-open-file` path made LITERAL **with
`:element-type 'character` dropped** (that option suppresses `CompileTimePathnameFolder`'s inlining).

**`eval/ClFadForms`** (pinned by `ClFadE2eTest`, all four, SBCL 2.2.9's answers). cl-fad 0.7.6's
`#-(or ...)` "not supported" arms: `getenv` -> `uiop:getenv` (runs at LOAD, beside the
`"TEMPORARY-FILES"` translation, `.kb/pathnames.md`); `list-directory` -> SBCL's `directory`;
`file-exists-p` -> `probe-file`; `delete-directory-and-files` -> `delete-file` on a directory
(`.kb/uiop.md`).

**`eval/LocalTimeForms`** (no vendored copy). local-time's `%get-current-time` fallback ->
`(rontolisp:unix-time)`, so `now` has the clock's fraction.

**`eval/WithUserAbortForms`** (pinned by `WithUserAbortE2eTest`, all four). The last `#+impl` arm of the
condition name -> `rontolisp:interactive-interrupt` ([error-handling.md](error-handling.md), "SIGINT").
clingon over it prints as SBCL; a real SIGINT exits 130 (interpreter, JVM).

**`eval/TrivialMimesForms`** (pinned by `TrivialMimesE2eTest`, all four). `mime-types.lisp`'s top-level
`(build-mime-db)` runs at a compiled program's start-up and failed wherever neither `/etc/mime.types`
nor the source-adjacent file was readable at RUN time (component without `--dir /etc`, `--no-wasi`,
macOS, away from the build tree; every dexador program). The call becomes "`/etc/mime.types` if
`probe-file` finds it, else the bundled file through a LITERAL path" that `CompileTimePathnameFolder`
inlines (66 KB). Chosen over documenting `--dir /etc`: that covered neither macOS nor a reactor.

**`eval/AtomicsForms`** (pinned by `AtomicsE2eTest`, all four). atomics ends `cas`/`atomic-incf`/
`atomic-decf` in `(no-support 'CAS)`, a macro signalling at expansion (sento). The three become
expansions, `:atomics-cas-*` features pushed; `cas` macroexpands the place first. A `car`/`cdr` is
`rontolisp::%cons-cas-car`/`-cdr` on the interpreter and JVM (`LispCons`' `VarHandle`,
`runtime/RontoConsCas`); the primitive compares by reference, so `cas-car` reads the cell, compares by
`eq` and swaps with the object it read (a re-boxed fixnum would otherwise never match). Every other
place, and all of wasm, uses `get-setf-expansion` under one lock (`.kb/concurrent-served-requests.md`):
atomic against `cas`, not against a plain `setf`. A slot CAS per representation was not made: nothing
measured wants it.

**`eval/FsetForms`** (cache only; "Libraries that load"). `Code/port.lisp`'s per-impl locks/barriers:
one insertion, a mutex per lock, a barrier as a lock grab (no-op on wasm). `with-lock`'s `:wait? nil`
waits: no try-lock primitive exists (`.kb/mutexes.md`), and `Tuple-Lookup` only reorders keys with it.

**`eval/CoaltonForms`** (cache only). `Hash`/`lisp-combine-hashes`: three markers widen the ccl arms to
`(or ccl rontolisp)` (64-bit mix masked to a fixnum). Float literal typing: coalton's
`(etypecase value (single-float F32) (double-float F64))` answered `F32` for every literal here (every
float is both). The SPELLING decides, as SBCL's two types do: the reader keeps a `d`/`l` mark
(`LispDouble.doubleSpelled`, `LispLexer`, an `interpret-token :around` on coalton's eclector client); an
arm asks `rontolisp::%double-spelled-p` first ([declarations-type-checks.md](declarations-type-checks.md)).
coalton re-reads printed forms, so its `print-form` uses `rontolisp::%prin1-keeping-float-spelling`
(`FloatText.keepingDoubleSpelling`). Nothing else reads the mark; the compile paths' prelude answers nil.
Rejected: typing a literal by its expected type (wrong for `(+ 1.0d0 x)` with the literal first) and
unifying F32/F64 (an instance on one would be found for the other).

**`eval/TrivialArgumentsForms`** (pinned by `TrivialArgumentsE2eTest`, all four). Its `defpackage`
imports MOP readers per `#+impl` arm with no generic arm (`No such package: METHOD-GENERIC-FUNCTION`).
Three spans: a `#+rontolisp #:closer-mop` arm (readers built in, `macro/generic-functions.lisp`); an
`arglist` arm over `rontolisp::%function-lambda-list` ([swank.md](swank.md), "Argument lists"); `:rontolisp`
in both `#-(or ...)` fallbacks. Compile paths answer `:unknown` (a compiled function keeps no lambda
list), but a macro asking at expansion is answered by the compile-time evaluator (in-nomine's
`define-namespace`). Consumers: spinneret, serapeum, typo, petalisp.

**`eval/TrivialMacroexpandAllForms`** (cache only; the walk is pinned by ci-spec
`gensym-and-macroexpand`). The fallback arm answers the form unexpanded (petalisp's `transform` found
no variable); a `#+rontolisp` arm over `rontolisp::%macroexpand-all`
([gensym-macroexpand.md](gensym-macroexpand.md)).

**`eval/PetalispForms`** (cache only; "Libraries that load", petalisp). The native backend reads storage
by address per implementation, else `(error "Not implemented yet.")`. Nine `#+rontolisp` arms beside
upstream's under `#-rontolisp`: storage vector/pointer = the array, `cffi:pointer-eq` = `eq`,
`ntype-unboxed-p` nil (every allocation upstream's boxed one), `load-function` = `row-major-aref`,
`+n-fixnum-tag-bits+` 0, `*pin-current-thread-library*` nil. One in `petalisp.utilities`:
`with-pinned-objects`' portable arm answered the last pinned object, not the body's values.

**`eval/ClReexportForms`** (pinned by `ClReexportE2eTest`, all four). clingon's `src/package.lisp`
exports via top-level `(cl-reexport:reexport-from ...)` at load, but the compile paths read
`clingon:getopt` before running it (`is not external`). A `define-compiler-macro` runs the library's
helpers at the call and expands to `(progn (import '(...)) (export '(...)))`, consumed by both
resolvers (`.kb/packages.md`, "Directives consumed at read/compile time"). It declines a computed
argument, an undefined package, `:include` with `:exclude`; on compile paths that call then changes
nothing (`expandRuntimeExport`: registry frozen at run time). Why not a `PackageResolver` rule:
`tryConsumeExport` would restate `:include`/`:exclude` in Java and put a library name in the core for
one consumer (226 cached systems: clingon's four calls).

**`eval/ClsqlForms`** (pinned by `ClsqlE2eTest`, all four, SBCL 2.2.9's answers over the real
`clsql.asd`). The last MOP-package arm gets `#:closer-mop`; `sql/metaclasses.lisp`'s three
`ensure-generic-function` lambda-list probes become `pushnew`s (no generic-function protocol needed on
compile paths); the `#+(or sbcl allegro)` `finalize-inheritance :after` arm sets `key-slots` here (the
`#-` arm runs before effective slots exist, leaving `key-slots` nil). Measured: `clsql-sqlite3` (needs a
`libsqlite3.so` name on `clsql:*foreign-library-search-paths*`; `.so.0` alone stops SBCL too) and
`clsql-postgresql-socket3` against `postgres:16-alpine` answer as SBCL on interpreter and JVM; other
database backends unmeasured. The `[...]` syntax reads in a source file on all four (`ReadTimeImage`), not
in a compiled run-time `read-from-string` ([readtables.md](readtables.md), "Not honored").

**`eval/DeployForms`** (pinned by `AsdfProgramOpE2eTest`): "deploy: build-time hooks" above.

### Tier 5: a GENERATED component (`eval/ClUnicodeTables`)
cl-unicode's `lists.lisp`, `hash-tables.lisp`, `methods.lisp` **do not exist in the release** (real
ASDF builds them through `cl-unicode/build`, outside the subset). `ClUnicodeTables` emits the same
definitions in Java at load time via `ShimLibraries.leafModuleForms` (hence its base-directory and
loader arguments).
- **The emitted shapes match `build/dump.lisp` exactly, quirks included** (so upstream's tests stay
  meaningful): `build-range-list` returns at `+code-point-limit+ - 1`; `split-range-list`'s
  `(round (1- length) 2)`; `pushnew` lists in reverse order.
- **The dumps are not quoted literals**: ~208,000 constant-pool entries against 65534. Tables travel as
  PRINTED TEXT in ~230 string literals read back with `read-from-string`; the fourteen range trees became
  flat range tables `%lookup` binary-searches, BUILT ON FIRST LOOKUP (the one faithfulness deviation).
  `PackageResolver.resolveQuotedDatum` recurses per element (200,000 overflow the stack) and so does the
  reader: a chunk is capped at **1,000 elements**.
- Pinned by `ClUnicodeTablesTest` and `AsdfSystemsTest.parsesTheBundledClUnicodeReplacementAsd`.

## Libraries that load, and what is pinned
Every entry loads VERBATIM upstream sources unless a substitution is named. `AsdfLibraryE2eSupport`
hosts the four-backend E2Es and needs a VENDORED tree per system; a library living only in the
Quicklisp cache is verified MANUALLY on all four (a ci-spec case cannot carry a `.asd`).

- **cl-unification** (cache, all four, as SBCL 2.2.9). Needed a component type spelled as the class
  symbol. Pin: `AsdfMetaobjectsE2eTest`'s `meta-demo-tree` (`(tree-asd "sub")`).
- **40ants-doc** (cache, interpreter, as SBCL). Compile paths stop in serapeum.

- **petalisp** (cache, interpreter, as SBCL 2.2.9 but for one float format and SBCL's pretty-printer
  wrapping): `(petalisp:compute (petalisp:lazy #'+ #(1 2 3) 1))` prints `#(2 3 4)`; loading runs 115
  docstring examples through the native backend.
  - **The decision: an arm in petalisp's native backend, not a backend of rontolisp's own.** What reads
    an address is nine forms and the worker pinning (`eval/PetalispForms`); partitioning, schedule,
    allocation reuse, worker pool, kernels stay upstream's, and petalisp's own suite (native vs
    reference backend) keeps testing upstream's backend. A backend of ours would be a second protocol
    implementation to keep in step, without the parallel schedule or graphviz's view
    (`petalisp.native-backend::action-work-invocations`). The memory model is upstream's boxed buffer
    (a simple vector, one slot per element) for every element type: no third representation.
    `static-vectors` is not admitted ([cffi.md](cffi.md)).
  - **Threads**: `make-native-backend` starts `(ceiling cpus 2)` virtual workers nothing joins
    (`trivial-garbage:finalize` registers nothing); the barrier spins a core per waiting worker, as on
    SBCL.
  - **The compile paths**: the JVM compiles (~100 s) but stops at run time: the backend `compile`s
    forms naming the program's own macros, which the embedded eval runtime does not expand. One compile
    in six hung at the compile-time evaluator's barrier (unreproduced). Preview 1 and the component stop
    at `Cannot compile: FFI:POINTERP` (cffi; [cffi.md](cffi.md)), then threads ([threads.md](threads.md)).
  - Not measured: `(asdf:test-system :petalisp)`.
- **fset 2.2.0** (cache, all four, the same text as SBCL 2.2.9). Port arm is tier 4.
- **coalton** (cache, interpreter): `(ql:quickload "coalton")` loads `coalton-compiler` and all of
  `coalton/library` through eclector and the real typechecker/codegen, and a user `coalton-toplevel`
  plus 33 `coalton` forms print as SBCL (but SBCL's `d0` and infinity spelling: one float type here),
  with or without `(named-readtables:in-readtable coalton:coalton)` ([packages.md](packages.md), "T and NIL
  in a package that does not read them as cl's").
  - **Speed** (SBCL compiles+loads the tree in 61 s; `compile-file`+`load` per component 57.3 s + 1.0
    s): ours took ~60 min, then 2168 s -> 1460 s with hot bodies compiled
    ([interpreter-compiled-bodies.md](interpreter-compiled-bodies.md)); up to `math/bounded.lisp` 86.1-87.7 s
    -> 59.5-63.7 s; then 1018 s -> 821 s with the tree's own costs cut (same file, "Where the time goes
    now"). 30-240x SBCL per file; the dear files are the same on both: coalton's `reduce-context` maps
    the whole value environment through `fset:image` per binding group (31% of SBCL's samples, 51% of
    ours). `fset:compare` of two symbols 13.7 µs against SBCL's 37 ns: the per-form walk cost.
    Contributors: the dispatcher chain memo ([clos.md](clos.md), "The interpreter's chain memo"), the
    per-site memos ([interpreter-expansion-memo.md](interpreter-expansion-memo.md), "The call-site note",
    "The setf memo"), a native `equalp`. "A class per hot body" was built and not kept
    ([interpreter-compiled-bodies.md](interpreter-compiled-bodies.md)).
  - Float traps: `/` over a float is IEEE on every backend ([error-handling.md](error-handling.md),
    "Per operator"), as SBCL with coalton's traps off, so `with-float-traps-masked` needs nothing.
  - **The compile paths** do what SBCL's `compile-file` does: the read-time image
    ([readtables.md](readtables.md), "The compile paths' read") runs coalton's `(` reader macro, which
    typechecks every library `coalton-toplevel`, and the macro-time pass replays the generated code to
    build `*global-environment*`. So the library's interpreter time is the compile's too (image read
    2000-2250 s + replay 280-300 s). The whole library prints its 37 lines on JVM, P1 and the component:
    JVM 2940 s compile, 98 MB of classes, 41 s run; P1/component 2780/2690 s, 51.8 MB modules, 83/87 s
    run. Traps found, each general: a `make-load-form` method claiming instances by NAME (coalton's
    `Pathname` claimed every `cl:pathname`; [make-load-form.md](make-load-form.md), [clos.md](clos.md),
    "Name resolution gotcha"); 1.2 million load-time values, each a defvar, past wasmtime's 1,000,000
    globals -- now one vector ([compiler-macros.md](compiler-macros.md); 245 MB -> 52 MB of wasm); huge
    literal builds and top-level runs ([quoted-data.md](quoted-data.md);
    [wasm-function-body-size.md](wasm-function-body-size.md), "Keeping a literal's build bounded";
    [hot-path-method-size.md](hot-path-method-size.md), "The branch cut"); a user file's
    `coalton-toplevel` read only in the image (`ReaderMacroReads`).
  - Not measured: `coalton/tests`, `coalton/library/big-float`.
- **sento 3.4.2** (cache, interpreter and JVM): actors, `ask`, agents, 8 threads x 200 `tell`s = 1600. On
  wasm `make-actor-system` signals at the first `bt2:make-thread`. `ask` with `:time-out` outside a
  system is `bt2:with-timeout`, which signals. Its own tests' `assert-cond` 3 s are short for 20008
  `ask-s` on the interpreter (slow, not a lost message).
- **cl-who 1.1.5** — `ClWhoE2eTest` (interpreter + JVM). `with-html-output` expands at MACRO-EXPANSION
  time. The macro-time config replay is a **static purity judgment, not a data file**
  (`UserMacroExpander.isPureConfigSetf`/`isPure`): **deny by default**, no per-library registration.
  Lite: `:indent`, `(let ((*html-mode* ...)) ...)` ignored (use `(setf (html-mode) :html5)`).
  **NO render ci-spec case is possible** — the corpus re-compiles ci-spec sources WITHOUT `--system-path`.
- **cl-base64 3.4** — `ClBase64E2eTest` (all four); ci-spec `cl-base64-residue-features`. WASM degrades
  an integer beyond `i31` to a float, so `integer-to-base64-string` diverges for large integers.
- **assoc-utils** — `AssocUtilsE2eTest` + `AssocUtilsUpcaseE2eTest` (all four); ci-spec
  `assoc-utils-features`. Rests on `eval/LispPreludeLibrary` (`loadedPreludeNames`) and the 5-value
  `define-setf-expander` protocol (`LispEvaluator.setfExpanders`; `UserMacroExpander` rewrites user-place
  `setf`/`incf`/`decf` BEFORE the compilers), `LoopExpander.parseForBeingHash`. Lite: `equalp` on
  arrays/hash-tables/structures falls back to `eql`; `define-modify-macro` place subforms may
  double-evaluate.
- **ironclad 0.61 slice** — `IroncladE2eTest` (all four). `ironclad-slice.asd` declares `ironclad/core`,
  every `/digest/X` but sha3/kupyna/skein, `/prngs`, `/mac/hmac`, `/kdf/pkcs5`, `/kdf/hmac`,
  `/kdf/password-hash`, `/kdfs`, `/public-key/rsa`, aggregate `ironclad` (+ sha1 + md5 + prngs for uuid).
  - The other digests digest `"abc"` as SBCL on all four but are NOT in the aggregate: they would compile
    into every `"ironclad"` dependent (cl-postgres, mito). Only sha1/md5 pinned. Out: **sha3**
    (`keccak-rounds`' `macrolet` needs `&environment`, which a rontolisp macro gets as nil --
    [defmacro-backquote.md](defmacro-backquote.md)), kupyna/skein (ciphers).
  - **`public-key/public-key.lisp` is a CORE component, as upstream**: the prng shim calls its
    `octets-to-integer` (moved into RSA, `ironclad/prngs` alone broke at the first draw).
  - **One deliberate layout deviation, forced by eager compilation: kdf/kdf.lisp loads LAST** (it
    `make-instance`s every KDF class, expanded where it stands), so kdf/hmac.lisp is a DEPENDENCY of
    `ironclad/kdfs`.
  - Out of scope: ciphers, aead, octet-stream, non-RSA public-key, scrypt/argon2/bcrypt. **Not a
    rontolisp gap**: PS512 with a 1024-bit key trips ironclad's own assertion.
  - **`random-data` is a leaf shim** keeping upstream's REJECTION SAMPLING (a modulo fold would bias a
    SCRAM nonce). `:fortuna` not implemented.
  - The SCRAM names had to exist before cl-postgres could compile: an undefined function is a
    **compile-time** error on JVM/WASM.
  - `gen-client-proof` XORs two digests as 256-bit INTEGERS (`.kb/wasm-bignum.md`). **Pinned edge case**:
    `integer-to-octets` returns the MINIMAL vector, so a proof whose high bytes cancel must be padded; an
    off-by-one surfaces only as an auth failure.
  - **Native PBKDF2 on the INTERPRETER only** (`eval/IroncladNative` + `eval/DigestKernels`):
    `loadSystem` rebinds `IRONCLAD::PBKDF2-DERIVE-KEY` to a Java kernel when the DEFINING system finishes
    (the `LinalgSimd` interception shape). Always on, **not a divergence** (PBKDF2 is spec-defined).
    Hand-written rather than `MessageDigest` because it compiles into the native binary AND the browser
    Web Image. Pinned by `IroncladNativeTest`.
- **cl-postgres** — `ClPostgresE2eTest`, runs by DEFAULT (Docker is its only gate). Real round trip
  against PostgreSQL 17 under `trust`, `password`, `md5`, SCRAM-SHA-256 on interpreter, JVM and
  `--component`; **Preview 1 has no TCP by design** (`.kb/tcp-sockets.md`; CALL-TIME error stubs).
  **Its `#.*string-file*` component must stay `strings-utf-8`** — `strings-ascii` announces
  `client_encoding SQL_ASCII` and desyncs on a non-ASCII parameter. ONE ROLE PER METHOD so a broken rung
  cannot fall back. **The component leg connects to the server's network ALIAS**, pinning name lookup
  (`.kb/tcp-sockets.md`, "Inside sockets.lisp").
  - **What a SCRAM login costs** (PostgreSQL 18 `log_connections=all`, `examples/db/postgres-hello.lisp`,
    Xeon E5-2697A v4): interpreter ~260 ms (native kernel); `--component` (wasmtime 49) 147-211 ms; a
    fresh JVM class 4.1-4.3 s, JIT tier-up ([jvm-int-fusion.md](jvm-int-fusion.md)). A wasi:http handler
    (`postgres-web.lisp`, `wasmtime serve`) pays the command's cost; what is its own is the collector in
    serve mode's small heap ([wasm-gc-heap-pregrow.md](wasm-gc-heap-pregrow.md), "Why serve is
    different"). Shiori 170-323 ms: clack's `eval` cells every defun
    ([wasm-int-fusion.md](wasm-int-fusion.md), "A name with a cell", "Ranges, and sites with nothing to
    bail on", "A leaf compiled twice"). A derivation (`pbkdf2-hash-password` x4096) 153-158 ms. Where the
    round goes (component): `sha256-expand-block` 16%, `update-sha256-block` 14%, `xor-block` 11% (boxed
    index parameters, every store's `_idx_chk` a call), `fill-block-ub8-be` 7%, keyword scans of
    `update-digest`/`produce-digest` ~4%, `replace`/`check-sequence`/`seq-len` ~5%.
- **alexandria 1.0.1** — `AlexandriaE2eTest` (all four), vendored UNMODIFIED. **The exercise IS
  `examples/asdf/alexandria-demo.lisp` verbatim — keep the two in sync.** ci-spec `last-with-a-count` /
  `every-some-over-many-sequences` / `coerce-to-a-computed-result-type` /
  `read-sequence-into-a-character-buffer`.
  - **`#'mapcar` as a first-class VALUE** must take N lists on every backend — **read
    `.kb/map-family.md` before touching any of them**.
  - An injected wrapper calling `apply` must keep WASM's `apply` runtime emitted (`usesEval` scans the
    SOURCE; wrappers come later): `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` +
    `referencesApplyingWrapper`; pinned by
    `WasmLispCompilerIntegrationTest.applyUsingWrapperReachedByFuncallCompilesAndRuns` — **each assertion
    must be the ONLY form in its program**, so ci-spec cannot cover it.
  - NOT supported: `type=`; `format-symbol`/`ensure-symbol`/`ensure-function` on a symbol (interpreted
    only); `shuffle`/`random-elt`/`gaussian-random` use each backend's own entropy.
- **jzon 1.1.4** — `JzonE2eTest` (all four); numeric leaves are leaf shims. WASM divergences kept OUT of
  the exercise: large-float print shape, hash-table iteration order, non-ASCII `\u` escapes.
- **uax-15 0.1.3** — `Uax15E2eTest` (all four); tier 4. Rests on the **WASM UTF-8 string byte model**
  (`.kb/wasm-gc-strings.md`) and `WasmComponentBuilder.memModuleFor` (memory module's initial pages follow
  the core module's import minimum).
- **cl-unicode 0.1.6 + cl-ppcre-unicode + cl-str** — `(str:title-case "HELLO LISP!")` on **all four**.
  Rests on the **`equalp` key fold on every backend** (`LispEquality.equalpKey`, `.kb/hash-tables.md`;
  without it `Unknown property name "Cs".`), tier 5, and the WASM heap growth
  (`.kb/wasm-gc-heap-pregrow.md`). `make-array :displaced-to` gives a real string VIEW
  (`.kb/adjustable-arrays.md`).
- **s-sql** — ci-spec `s-sql-enablement-language-group`; quickload MANUAL; identical on ALL FOUR (its
  cl-postgres socket chain lowers through `LispMacroExpander.callTimeUnsupportedStub` on Preview 1: the
  pruner cannot drop a defmethod-anchored chain, `LibraryDefunPruner.definitionName`). Replay rule: a
  top-level `(let (...) <definitions only>)` is evaluated WHOLE at macro time
  (`UserMacroExpander.registerMacroTimeDefinitions`; ~230 `expand-sql-op` defmethods over one closure).
- **postmodern** — `PostmodernE2eTest` (opt-in `RONTOLISP_POSTGRES_E2E=1`); ci-spec
  `postmodern-non-mop-milestone`. Byte-identical on interpreter, JVM and `--component`. `postmodern-deps.asd`:
  - **`:postmodern-use-mop` ON**, `:if-feature` clauses VERBATIM so it stays a feature flip —
    `AsdfSystemsTest.thePostmodernMopBuildIsAFeatureFlip`.
  - **`:postmodern-thread-safe` ON** via `:rontolisp-features`: `bt:with-lock-held` really serializes
    (`.kb/mutexes.md`) and rontolisp runs concurrent handlers.
  - **`:depends-on` deliberately differs**: `global-vars` DROPPED (no call sites); `cl-ppcre` + `uax-15`
    ADDED (called, never declared; else eager compile paths depend on another `.asd`'s order).
  - A condition prints as a slot dump rather than via `:report`.
- **quri 0.7.0** — ci-spec `quri-enablement-language-group` + `QuriEtldTablesTest`; quickload MANUAL.
  - **`apply` through a COMPUTED designator has no arity ceiling**: a SPREAD dispatcher (`_invoke_v` /
    `FUNC_DISPATCH_SPREAD`) over EVERY callable; a variadic target gets the TAIL as its rest parameter.
  - **`format`'s destination is decided at RUN time** unless literal `nil`/`t`
    (`formatDestinationDispatch`; the Gray `format` rewrite does the same test,
    [gray-streams.md](gray-streams.md)). **Only the concatenated ci-spec program catches this.**
  - **ONE limitation remains**: `:lenient` percent-decoding of really malformed input crashes on the three
    compile backends — a `go` out of a `handler-bind` handler into an enclosing `tagbody`
    (`.kb/do-return-block.md`).
- **local-time 1.0.6** — ci-spec `local-time-enablement-language-group`; quickload MANUAL; timestamp API
  byte-identical on ALL FOUR. **Real TZif files load** wherever there is a filesystem; else local-time's
  own `handler-case` falls back to `+utc-zone+`, which is why a failed `open` must SIGNAL, not trap, on
  WASM (`.kb/read-load-streams.md`).
  - **The four load-context pathname variables** (`LispNames`, `PackageRegistry.CL_VARIABLES`): the
    `*load-*` pair holds the file being loaded on EVERY backend, at READ time too
    (`.kb/load-inliner.md`). **An ASDF COMPONENT is loaded by its RESOLVED path**, so its
    `*load-pathname*` equals `asdf:component-pathname` (`LoadContextE2eTest`).
    **The compile-file pair is permanently nil, at read time too, deliberately**; any reason expiring
    is the trigger: (a) `(or *compile-file-pathname* *load-truename*)` answers through its fallback;
    (b) local-time's `#.(or *compile-file-truename* '*load-truename*)` relies on the first arm being nil;
    (c) "am I being compiled to a fasl" is honestly no. **If a real `compile-file` ever exists, bind
    both pairs together and revisit local-time's spelling in the same pass.**
    **`*readtable*` rides the same list**: bound in the SAME `let`, so `injectMvSpillGlobal` declares
    whichever of the four the program mentions, or `Cannot compile symbol reference: *READTABLE*`.
    Its seed is a standard readtable ([readtables.md](readtables.md)).
  - `merge-pathnames` and `truename` are `LispPreludeLibrary` entries; `truename` is `%real-path`
    (`.kb/read-load-streams.md`), else the SIGNAL of `file-error`. local-time's DEFAULT repository path
    is computed by `(eval (read-from-string "(let ((system (asdf:find-system ...` -- folded by
    `CompileTimePathnameFolder` to a literal directory of the machine that COMPILED the artifact, so a
    portable JVM artifact or a WASM build without `--dir` still passes `:timezone-repository`.
  - **`now` carries the clock's fraction**: `eval/LocalTimeForms` replaces the `#-(or ...)` fallback arm
    of `%get-current-time` with `(rontolisp:unix-time)`
    ([time-environment-builtins.md](time-environment-builtins.md)). A rewrite, not a feature: every arm
    names another implementation, and a feature it read would claim that implementation. Not vendored:
    only a quickload meets the marker.
- **lack / clack `.asd` front end** — `AsdfSystemsTest` (`parsesTheVerbatimLackAsd`,
  `loadSystemNameIgnoresKeywordOptions`, the `:pathname` group). Rest: `.kb/clack.md`.
- **fast-io 1.0 + circular-streams** — `FastIoCircularStreamsE2eTest` (all four); see [clos.md](clos.md)
  (`with-slots` on a write-only unbound slot; `slot-value` of an undeclared slot is a RUN-time error).
  **Upstream facts the exercise encodes (as SBCL)**: circular-streams cannot see the end of a fast-io
  input stream (fast-io's `stream-read-byte` SIGNALS `end-of-file` where Gray wants `:eof`); the `#.`
  `:long-description` is dropped. **Blocker NOT fixed**: fast-io's `defmethod close` makes `close` a user
  generic that DROPS the built-in on the interpreter ("No applicable method: CLOSE on INTEGER") while
  compile paths ignore the method — the general "user definition of a built-in name" gap.
- **trivia** — `TriviaE2eTest` (all four); ci-spec `trivia-enablement-language-group`.
  **DIVERGENCE RECORD**: `trivia.asd` -> `trivia-trivial.asd`, over upstream's sanctioned `trivia.trivial`
  base. Upstream's `trivia.balland2006` is the match-clause OPTIMIZER: needs iterate + type-i, buys ZERO
  semantics. **Re-evaluation trigger**: a real consumer needing iterate itself, or interpreter match
  performance becoming the bottleneck (a 4-clause `match` in a defun ~0.31 s PER CALL interpreted; the
  interpreter re-expands user macros per evaluation; compiled backends unaffected). **General
  mechanism**: `UserMacroExpander` REPLAYS plain top-level forms of SPLICED SYSTEMS
  (`%begin-system`/`%end-system`) into the macro-time evaluator and honors a macro-EXPANDED `eval-when`
  ([defmacro-backquote.md](defmacro-backquote.md)).
- **sxql** — `SxqlE2eTest` (all four, as SBCL); ci-spec `sxql-enablement-language-group`. **Runtime slot
  names normalize to the base spelling** in `%slot-value-runtime` & co. -- `(intern (symbol-name n))`
  **deliberately IN the public defun, not at call sites**, so the WASM `usesIntern` gate sees it.
  **`subtypep`** walks struct `:include` ancestry, user deftypes and `(or ...)` on either side; the shared
  `LispMacroExpander.subtypep` serves the interpreter and the fold, and `subtypepUniverse` keeps
  `%subtypep-ancestor-table%` identical. Residue: lisp-namespace's `pprint-logical-block` compiles to a
  call-time error stub.
- **The mito-closure tolerance batch** — parse-level widenings, each a whitelist entry and **never a
  sink**. Ignoring esrap's `perform :after` capability pushes is a **VERIFIED decision** (zero `#+esrap.`
  reads in the cached dist). **Trigger**: if anything reads one, the method has to run ("The
  component-class surface"). `PackageResolver.resolve`: **a literal top-level qualified `define-package` is
  consumed like `defpackage`**, and **`pax:defsection` AUTOEXPORTS its `(SYMBOL LOCATIVE)` entries**
  (`consumeDefsectionExports`) — trivial-utf-8's ONLY export mechanism.
- **cl-dbi + dbd-postgres** — manual, three backends (a component needs `-S inherit-network=y`).
  **`dbi.asd` -> `dbi-deps.asd`**: upstream's cache selection rides a thread-capability feature expression
  that can never match and **MUST NOT be satisfied by claiming one** (additive-features rule); the
  replacement picks `thread.lisp` + real `bordeaux-threads` behind `:if-feature (:not :rontolisp-wasm)`,
  `single.lisp` on WASM. **DIVERGENCE RECORD + trigger**: re-evaluate if WASM gains threads or upstream's
  expression changes shape. `dbi:connect` probes `find-driver` FIRST, so the compile-path stub is dead once
  the driver is loaded.
- **mito-core** — ci-spec `mito-core-enablement-language-group`; quickload manual; DAO CRUD identical on
  interpreter, JVM, component. **dissect** is an empty-body no-op (no backend carries a Lisp call stack);
  **uuid** via ironclad + trivial-utf-8. `compiler/FreeVarAnalyzer`: case clause key lists are data
  (`(lambda flet labels)` as keys used to capture LABELS). `UserMacroExpander.paxDefsectionExportForm`
  emits a literal top-level `export` (defsection expands to a defvar at macro time).
- **esrap** — `EsrapE2eTest` (all four); ci-spec `esrap-enablement-language-group`; README example plus
  mito's `migration/sql-parse.lisp` grammar VERBATIM. Traps that gave WRONG answers, not errors:
  - **The SIZE of `(string N)` / `(simple-vector N)` / `(vector T N)` is checked**; **`(simple-vector N)`
    carries only a SIZE** while `(vector ELEMENT-TYPE SIZE)` leads with the element type.
  - **`(cons CAR-TYPE CDR-TYPE)`** used to fall to the ranged-NUMERIC default; a non-numeric atomic type
    spelled compound now ignores its arguments.
  - Others: **`reduce` over an EMPTY sequence calls its function with ZERO arguments** (on WASM a
    wrong-arity indirect call TRAPS); a `~/name/` is the only trace of a function REFERENCE, so
    `LibraryDefunPruner` scans string literals for it ([format.md](format.md)); a macro-GENERATED
    `deftype` reaches the compilers' registry (`UserMacroExpander.emitMacroGeneratedDeftypes`). Residue:
    `set` and `break` undefined.
- **tiny-routes** — `TinyRoutesE2eTest` (all four) plus the tiny-routes legs of `ClackE2eTest` (the only
  coverage of the LIVE `ql:quickload` and `tiny-routes-middleware-cookie`).
  - **LOOP's anaphoric `it` is matched by MEMBER (`splitQualified`) in any package**, not by raw name:
    the expander runs AFTER `PackageResolver` (same for `(loop-finish)`).
  - A serve component whose program ends in a non-`cl-user` package: the `eval/HttpLibrary` bridge is
    appended AFTER the program, so `%serve-dispatch`/`%serve-request-body`/`%serve-handle` carry an
    explicit `cl-user::`; the handler reference is deliberately unqualified.
  - `tiny-routes/test` is out of scope.
- **tiny-routes/lite** — the OPT-IN ppcre-free variant: `AsdOverrides` -> `tiny-routes-lite.asd` (primary
  VERBATIM + the secondary system), `ShimLibraries.LEAF_MODULES` swapping `path-template.lisp` under THAT
  system only, `AsdfSystems.locate`'s secondary-name rule, `DistClient.ensureAvailable` downloading a slash
  name's PRIMARY. **Both tiers are needed together** — the file swap alone is -0.9%: the engine stays
  anchored through its CLOS surface (`.kb/optimize-dead-code-elimination.md`). **Contract: "matches
  identically or refuses loudly"**: `/a/:x-:y` parses as ADJACENT tokens — **do not "fix" that, it is
  upstream's own behavior, empirically pinned**; live regex syntax or `:regex t` SIGNALS at route build.
  Pinned by `TinyRoutesLiteE2eTest` (vendored tree WITHOUT cl-ppcre) and
  `TinyRoutesLiteUpstreamParityTest`. **Co-loading the two is REFUSED by both loaders in both orders**
  (`ShimLibraries.conflictingSystem`). **A cl-ppcre/lite in the same pattern was built, parity-pinned and
  then REJECTED (user decision) — do not re-propose it.**
- **rove 0.10.0** — `RoveE2eTest` (all four). `src/test/resources/rove-demo` carries BOTH shapes rove's
  `run-system` dispatches on (package-inferred via `component-sideway-dependencies`; plain via the
  `*load-pathname*`-keyed file->package map). `AsdfLibraryE2eSupport.compileProgram` hands
  `LispPreludeLibrary.process` the TARGET features and runs `EnvironmentLibrary.process` per backend.
  ONE text divergence from SBCL: an accessible symbol prints package-qualified (`MY-APP/MAIN:ADD`) —
  **flip `RoveE2eTest`'s expected lines to the SBCL spellings when that lands.** Non-goals:
  `deftest`'s `:compile-at :run-time` interpreter-only; `:style :none` compiled needs
  `rove/reporter/none` loaded; a raw wasm TRAP ends the run.
- **The examples are rove's first consumer** (`examples/console/roman.lisp`,
  `cloudflare-workers/httpbin/check.lisp`, `browser/minesweeper/minesweeper-core-test.lisp`): single
  file, `(asdf:load-system :rove)` + `(uiop:quit (if (run-suite *package*) 0 1))`, the VENDORED route so
  `-Drontolisp.examples=true` needs no network. **`examples.yaml`'s `systemPath` is a LIST**
  (`ExamplesE2eTest.systemPathFlags`). `IndentRules` knows rove's body-taking operators.
- **`rontolisp test` — the CLI runner.** `TestCommand` (`cli`) is a SOURCE GENERATOR, not a mode: ONE
  program (rove prologue + target + verdict epilogue) through the ordinary pipeline, so `-o` compiles the
  SAME program with the identical exit contract (hence parsed by `CliOptions`, unlike `format`).
  **Upstream `roswell/rove.ros` is the spec** (quicklisp cache; NOT vendored).
  - **The one hook that is not cosmetic**: rove sets `*last-suite-report*` only in `call-with-suite`,
    which **`rove:run-suite` does not** reach; the prologue's `(defmethod rove:invoke-reporter :after
    ...)` sets it (`invoke-reporter` is the funnel EVERY entry point crosses). Without it the runner
    re-runs the tests (`RoveTestCommandE2eTest`'s `containsOnlyOnce("Summary:")`). The prologue also
    REPLACES `rove:run-suite` (same specializer), since rove's hard-codes `:spec` and `-r dot` would
    silently do nothing.
  - **Deliberate divergences from `rove.ros`**: failure exits **1**, not `-1` (the 8-bit mask makes 255);
    no `COVERAGE`; stdout NOT swallowed (on the `run-suite` path that would swallow the REPORT — **if rove
    ever moves the `*standard-output*` rebinding into `run-suite-tests`, the swallow becomes safe**); a
    `.lisp` naming no locatable system is LOADED; NO test run exits 1 (upstream: vacuous pass); a system
    designator is a target; `-r` only `spec|dot|none`, unknown exit **2**. A plain `rontolisp FILE` still
    exits 0 after a red suite (`.kb/toplevel-statement-values.md`) — deliberate.
  - Coverage: `RoveTestCommandE2eTest` (all four as SUBPROCESSES) + `RontoLispCliTest`. **No ci-spec case
    is possible.**
- **cl-mustache 0.12.3** — `ClMustacheE2eTest` + `ClMustacheSpecE2eTest`, all four. Traps it exposed:
  **`gethash`'s present-p surviving a FUNCTION return** (else every `{{name}}` empty,
  `.kb/multiple-values.md`); `#.` seeing `*load-truename*` on the COMPILE path; an unhandled `signal` must
  not be fatal; a runtime absolute path resolves against preopen NAMES, not fd 3
  (`.kb/read-load-streams.md`). `ClMustacheSpecE2eTest` runs the vendored 194-case spec suite, one
  character per case: **158/194 is parity with upstream, not a shortfall** (SBCL fails the same 36) —
  **a REGRESSION pin: if it moves, a rontolisp change moved it.** `t/test-api.lisp` (20/20) is NOT in the
  E2E: its `#.` bakes the HOST path.
- **jose** — `JoseE2eTest` (HS/RS/PS/none; vs SBCL 2.2.9 AND HMAC tokens vs Python's `hmac`/`hashlib`)
  and `JoseTestSuiteE2eTest` (upstream's `jose/tests/jwt` through rove), all four, shared `JoseSystems`.
  Package-inferred; **the whole dependency graph loads unpatched**. Traps: `with-input-from-string` over a
  MUTABLE character vector on the JVM; **a multi-pair `setq` whose later value builds a closure**
  (`FreeVarAnalyzer` walked only the first pair: silently wrong on JVM, `Cannot find variable for
  closure` on WASM). `jose/tests/jws` is excluded for good (`(:use #:pem)`, in no dist). **Non-goal:
  JWE.**
- **iterate 1.6.0** — `IterateE2eTest` (all four); ci-spec `sharp-l-comma-dot-and-hash-table-iterator`.
  **`macro-function` answers nil for rontolisp's own `while`** ([symbol-runtime-api.md](symbol-runtime-api.md))
  — a yes made `(iter ... (while test))` loop forever. A clause head is read in the CURRENT package (the
  exercise `use-package`s), as SBCL. `iterate-test.lisp` (written against `rt`) does not ride along;
  **trigger**: vendoring `rt` or porting the suite to rove. Iterate WORKING does not fire trivia's trigger
  (`trivia.balland2006` still needs `type-i`).
- **array-operations 1.2.1** — ci-spec `array-operations-enablement-language-group`; quickload manual;
  identical on all four. **Standing result**: upstream's clunit2 suite **217/219 here against SBCL 2.2.9's
  219/219**; the 2 are ONE CONFORMANT answer: `(array-element-type (make-array 4 :element-type 'fixnum))`
  is `T`. `equalp` over arrays is ITERATIVE (recursion blew the interpreter stack on rank-5 arrays); a
  `defmacro` a macro expands to inside `progn`/`let` ([defmacro-backquote.md](defmacro-backquote.md)).
- **yason 0.8.3** — ci-spec `yason-enablement-language-group`; quickload MANUAL; loads UNPATCHED,
  identical on all four. `check-type` signals a `simple-type-error` (yason catches `type-error`;
  [declarations-type-checks.md](declarations-type-checks.md)).

## Why this is a shim and not real ASDF (spike)
Upstream ASDF 3.3.7 (14,130 lines) **loads end to end** in the INTERPRETER with a patch set. Decided
against: (1) it is a PORT — upstream refuses an unknown implementation, `detect-os` refuses without
`:unix`/`:windows`/`:genera`/`:os-macosx`, 34 `not-implemented-error` sites sit behind 230
implementation conditionals; (2) startup — its load costs many times the whole current startup. Also the
compile paths resolve systems at COMPILE time and the browser playground has no filesystem, so real ASDF
could only be a plan resolver inside the compiler, never in the artifact. **Re-evaluate when** the
identity model gains `eq` tables, a per-library shim fix stops being cheaper, or upstream gains a portable
no-`compile-file` mode.

Reproducing the spike: fetch `asdf.lisp` from `https://common-lisp.net/project/asdf/archives/asdf.lisp`;
rename the `uiop`/`asdf` SYSTEMS (`sed 's/UIOP/XIOP/g; ...'`); rewrite the 39
`uiop/package:define-package` forms to `defpackage` (expand `:use-reexport`, add `:common-lisp` to every
`:use`, drop `:recycle`/`:unintern`/`:intern`); delete the port guard and `(pushnew :unix *features*)`,
make `not-implemented-error` a no-op; inject the missing `cl` names as per-package defuns.

## The `.asd` runs as Lisp: the decision and its measurement (h35, 2026-10-30)
Libraries that are NOT per-implementation layers (cl-csv, function-cache, cl-containers, cl-pdf) need a
`.asd` evaluated (guarded `defpackage`, `find-system` probe, computed feature push read by `#-`). Rejected:
one recognized idiom per library (for cl-pdf a second evaluator in Java), in favor of the evaluator the
image already has.

Measured over the 339 cached, 41 vendored and 8 bundled `.asd` files (`Features.INTERPRETER`,
`Features.JVM`):
- **Plans**: every file the old data parse took produces IDENTICAL systems; 6 more load; 0 regressions.
- **Determinism**: leaving pushes in `*features*` changed 187 of 233 plans in alphabetical load order
  (cl-json.asd's `(pushnew :cl-json *features*)` reached every later file). Hence the binding: a push is
  the file's declaration. `find-system` answers registered systems only, so the cache's CONTENTS never
  decide a plan, but load ORDER can.
- **Startup**: first evaluator in a JVM 370-630 ms (class loading the macro-time evaluator pays anyway),
  warm 3-11 ms + asdf runtime 20-60 ms; per file 0.5 ms parsed vs 1.7 ms loaded. A compile whose systems
  are all built in or inline creates no build image.
- Status: cl-csv (all four) and function-cache (interpreter) load as SBCL; cl-containers stops at
  metatilities-base's implementation guard; cl-pdf loads (interpreter, JVM) and writing stops at
  `PDF::EXTENDED-ASCII-P is undefined`, identically on SBCL (upstream's).

### Earlier: the gate was not what stopped portableaserve
Evaluating `.asd` files buys portableaserve nothing: `acl-compat` needs `puri`, `cl-fad` and SBCL
contribs, and either announces `#+sbcl` (needing SBCL's internals) or is refused by its own guard. **SBCL
on this machine cannot load it either** (`Component :PURI not found`): no oracle. The reachable path is a
shim `net.aserve`/`acl-compat` surface over rontolisp's own HTTP server (`.kb/http-server.md`,
`.kb/clack.md`).

### The _Practical Common Lisp_ book corpus, as a standing result
`practicals-1.0.3` (twelve systems, eleven chapters) diffed byte for byte against SBCL 2.2.9 on the
interpreter. **The corpus needed no shim, no replacement `.asd` and no leaf substitution.**
Byte-identical: `spam` (23), `binary-data` (24), `id3v2` (25), `mp3-database` (27), `html` (30/31),
`macro-utilities` (8), `profiler` (32). Differ ONLY by the missing right margin: `simple-database` (3),
`test-framework` (9), `pathnames` (15). Chapters 15, 25, 27 need `--feature sbcl`; 26/28/29 are the
`net.aserve` rows above. `gensym`'s default prefix is CL's uppercase `G` (a lowercase `"g3"` needs
`|...|` under `:case :downcase`).
