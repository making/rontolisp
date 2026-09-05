# asdf (limited system-definition subset)

An API-compatible mini-ASDF, **not a port**. Shared core `eval/AsdfSystems.java` parses
`defsystem` forms and whole `.asd` files **as plain data — never evaluated**.

Surface: `asdf:defsystem` + `asdf:load-system` + `asdf:test-system`, with component
METAOBJECTS real at run time. `asdf:find-system` answers a memoized `asdf:system` CLOS instance
per name (`eq` across calls); the classes (`component`, `child-component`/`parent-component`,
`module`, `system`, `package-inferred-system`, `source-file`, `cl-source-file`, `static-file`)
are real on every backend so `typecase`/`typep`/defmethod specializers work; the readers
(`component-name`/`-pathname`/`-children`/`-sideway-dependencies`/`-parent`/`-system`,
`registered-systems`, `*user-cache*` = nil) walk the model. `test-op` is the ONE op with
machinery. **No general CLOS `operate`/`perform`, no `compile-op`/fasl output-translations.**
Docs: `doc/*/guides/asdf-systems.md`, `reference/functions/asdf-{defsystem,load-system}.md`.

## What a `.asd` may contain
Anything not listed is a hard error naming the form — deny by default.
- `defsystem` in any package spelling.
- `register-system-packages` — RECORDED into the loader's package -> system map (only the
  package-inferred consumer reaches here).
- `in-package`, `defpackage` — skipped.
- top-level `defparameter` — evaluated into a parse-time env by `evalDataForm` when the value is
  pure data. An IMPURE value does NOT fail the file: `defineParameter` records it as unevaluable
  and only a later form that READS it errors, naming the parameter.
- top-level `progn` — **FLATTENED** onto the worklist; an unsupported form inside still errors by
  its OWN name, never `PROGN`'s.
- `(eval-when (...) (pushnew :F *features*))` or a bare top-level `pushnew`/`push` — see below.
- component-class `defclass` and any top-level `defmethod` — see below.

### Options
- `:components` ordered by a stable topological sort of `:depends-on`; `:serial` = implicit dep
  on the previous sibling; `:module` = path prefix with files kept contiguous; `:static-file` =
  ordering-only.
- A SYSTEM-level `:pathname "dir"` prefixes every component; a component-level one decouples the
  component's NAME (its identity in the sibling dependency graph) from the path it contributes.
  On a `:file` the namestring is used verbatim when it carries an extension, else `.lisp` is
  appended. An EMPTY pathname adds no directory level. **A computed `:pathname` is a hard
  error.**
- A component NAME accepts a string OR a symbol (real ASDF's `coerce-name` downcases).
- `locate()` finds `NAME.asd` by *attempting to read* each search dir through `SourceLoader` — no
  existence check, so the browser playground's in-memory loader works. Secondary names
  (`lib/tests`) map to the primary `.asd`.
- Features (`parseAsdSource`/`parseDefsystem` take a `reader.Features`): component-level
  `:if-feature EXPR` keeps the component in the dependency graph but drops its files; a
  `:depends-on` entry may be `(:feature EXPR DEP)` (`dependencyName`); a *surviving*
  `(:require MODULE)` is a hard error; elements past the dependency are IGNORED, matching
  `resolve-dependency-combination`.
- `(:version NAME "1.2.3")` resolves to the plain dependency, constraint NOT checked. `#:lib`
  designators are stripped in `designator`/`symbolName`.
- **`:rontolisp-features (...)`** — rontolisp-added, parsed by `AsdfSystems.declaredFeatures`
  **before** the option loop onto `LispSystem.features()`. Each loader WIDENS its own base set
  (`Features.with(...)`, **additive only**); a DEPENDENCY keeps the outer set. Exists because a
  real `.asd` pushes a feature at LOAD time while component conditionals resolve at READ time
  (`.kb/reader-features.md`).
- **`:defsystem-depends-on`** parsed and recorded on `LispSystem.defsystemDependsOn`; resolved
  BEFORE `:depends-on`. Deliberately NOT merged into `dependsOn` — `component-sideway-dependencies`
  must not list it (pinned on all four backends).
- `:class :package-inferred-system` — the ONE `:class` value implemented.
- **`IGNORED_OPTIONS` — never resolved, never mentioned**: `:name :description
  :long-description :version :author :maintainer :license :licence :homepage :bug-tracker
  :source-control :mailto :long-name`, plus `:in-order-to`/`:perform`. It is the CLOSED list, so
  an option added later is load-bearing by default. `:version` is on it even though
  `asdf:component-version` reads it BACK: a plain STRING is recorded as written, every other
  spelling answers nil.

### `#.` in a `.asd` — the CONSUMER decides, never the evaluator
- The tolerant lexer (`LispLexer` with `tolerateReadEval`, used only by
  `readAllSkippingReadEval`/`readFirstForm`) re-lexes the skipped datum into a
  `(%read-eval datum)` marker (`LispNames.READ_EVAL`) or a
  `(%read-eval-unreadable "RAW")` marker (`LispNames.READ_EVAL_UNREADABLE`). **One datum either
  way**, so a `#.` inside a plist never shifts the surrounding pairing.
- **Neither marker is resolved where it is read.** `parseDefsystem`'s option loop resolves per
  option (`resolveReadEval` against the enclosing `.asd`'s defparameter env + path, threaded as
  `AsdContext`; other callers pass `AsdContext.NONE`).
- **Everything outside `IGNORED_OPTIONS` is resolved, and unresolvable is a HARD ERROR** naming
  the `.asd` and the clause. A silent nil drops a dependency or a source file and surfaces much
  later as an undefined symbol far from the cause.
- **Do not teach `evalDataForm` `with-open-file`/`uiop:read-file-string`.** A top-level marker of
  either kind is ignored whole (the ASDF version-guard idiom). Non-`.asd` sources support `#.`
  everywhere (`.kb/reader-features.md`).

### Feature announcements read out of a real `.asd`
`AsdfSystems.collectFeaturePushes` hands a top-level push to `parseDefsystem` as if the system
had declared `:rontolisp-features (:F)` (`mergedFeatures`, pushes first).
- Pushes accumulate in FILE order and reach only systems defined AFTER them. Only the
  announcement shape is accepted inside the `eval-when`; a `(:compile-toplevel)`-ONLY situation
  list is inert; the `*features*` argument must be the symbol itself.
- **A DEPENDENCY announces features to the system that names it**:
  `BuiltinSystems.declaredFeatures` is read at PARSE time, so announced names hold for this
  system's `:if-feature`/`(:feature ...)` and for reading its components. **Only a BUILT-IN
  system announces** — a real third-party one announces by RUNNING. Both `:depends-on` and
  `:defsystem-depends-on` announce. Divergence: an announcement also reaches this definition's
  own `:if-feature` clauses (a one-pass parse has no "load between the clauses").

### The component-class surface
Only two things can reach a data-only front end: whether an instance contributes a SOURCE file,
and which file EXTENSION its name gets. Model: `AsdfSystems.ComponentClass(source, fileType)` +
a per-`.asd` `ComponentClasses` scope.
- **ASDF's own classes are component types and superclasses without a defclass**
  (`BUILTIN_COMPONENT_CLASSES`): `cl-source-file` (`.lisp`, `DEFAULT_COMPONENT_CLASS`),
  `cl-source-file.cl`, `cl-source-file.lsp`, `static-file`, `doc-file`, `html-file`.
- **A top-level `defclass` declares a component class** (`collectComponentClass`): every
  superclass must resolve in that scope, `source`/`fileType` inherit from the first source
  superclass, a `(type :initform "cl")` slot overrides the extension. A defclass that is NOT a
  component class stays a hard error.
- **`(defmethod source-file-type ((c CLASS) (s module)) "ext")` sets that class's extension**,
  collected in a PRE-PASS (`collectSourceFileTypes`, descending into `progn`) so its position
  does not matter. **The CLASS still has to precede its use**; only the METHOD is position-free.
- **Every other top-level `defmethod` is tolerated and ignored** — the one deliberately opened
  part of the closed world, safe because a method that really moved a file surfaces as the
  missing file, NAMED.
- **`:default-component-class`** is what a bare `(:file "name")` takes, readable on the system
  AND on a module. NOT in `IGNORED_OPTIONS`: its `#.` resolves and an unknown class is an error.
- Driving library: **portableaserve** (`aserve.asd`, `htmlgen.asd`, `webactions.asd` all parse).

## `:class :package-inferred-system`
Consumers: ningle, rove, array-operations, jose. Every other `:class` value is a hard error
naming the clause. Such a system has NO `:components`; `LispSystem.packageInferredDir` (non-null
iff declared) is the whole marker. All rules in `AsdfSystems`:
- **A sub-system name is a FILE PATH.** `x/a/b` -> `a/b.lisp` under the PRIMARY system's
  directory + `packageInferredDir`; the primary is the part before the FIRST slash whatever the
  depth.
- **Dependencies are read out of the file's own `defpackage`** (`packageDependencies`): every
  package in `:use`, `:mix`, `:reexport`, `:use-reexport`, `:mix-reexport`, plus the FIRST
  argument of each `:import-from`/`:shadowing-import-from`. This is the WHOLE dependency graph.
  A file with NO `defpackage`/`uiop:define-package` is a hard error naming the file; forms BEFORE
  the package declaration are skipped; only the FIRST package form counts.
- **A package name becomes a SYSTEM name** (`packageSystemName`): what `register-system-packages`
  recorded, else the downcased name; `cl`/`common-lisp`/`cl-user`/`common-lisp-user`/`keyword`/
  `asdf` drop out.
- **Only forms up to the package declaration are read**
  (`LispReader.readFirstFormMatching`/`readFirstForm`). Every source is opened here, which is why
  the tolerant lexer may not WARN; no provenance is recorded.
- **Derivation is on demand, whole-closure per call** (`inferPackageInferredSystems`); an edge
  back to an already-registered sibling is not followed, so a `defpackage` cycle terminates.
- Coverage: `AsdfSystemsTest` (verbatim `ningle.asd`, nested rove shape, array-operations
  `:pathname`, sibling cycle) + `PackageInferredSystemE2eTest` (four backends over
  `src/test/resources/package-inferred-demo`).

## Interpreter / compile path / search order
- **Interpreter**: `asdf:defsystem` is an `evalCons` case on `LispNames.ASDF_DEFSYSTEM` (special
  form — options are data); `asdf:load-system` is a global function accepting computed names,
  driving `loadFile` with the system's `baseDir` on `loadDirStack`. State:
  `asdfSystems`/`loadedSystems`/`loadingSystems` + `systemPath`.
- **Compile path**: handled *inside* `LoadInliner`'s recursion; the `Ctx` record threads the
  system registry, loaded set, cycle stack and search path. A non-literal `load-system` name is a
  hard error at inline time; a *nested* asdf form is rejected by both compilers in the same
  `case` as `REQUIRE`/`PROVIDE`.
- **`load-system`/`quickload` keyword options are accepted and IGNORED on every path**
  (`AsdfSystems.checkIgnoredLoadOptions`): there is no `operate` machinery for
  `:force`/`:verbose`/`:silent`, and rejecting them would make lack's `find-package-or-load`
  unloadable. The SHAPE is still checked.
- **Search order**: dir of the loading file, then `--system-path`, then
  `RONTOLISP_SOURCE_REGISTRY`, plus a `ql:quickload`'s download cache ([dists.md](dists.md)).
  `RontoLispCli.systemPath()` parses the `File.pathSeparator`-joined lists. The `asdf` package is
  seeded in `PackageRegistry`.
- **No ci-spec case is possible**: the compile path needs the `.asd` on disk at compile time,
  which the concatenated ci-spec driver cannot provide. Coverage is `AsdfSystemsTest` +
  `LispEvaluatorAsdfTest` + `LoadInlinerTest` + the per-library E2Es.

### Compile-time pathname folding (`cli.CompileTimePathnameFolder`)
After `LoadInliner.inline`, a walker folds the pathname primitives real libraries call at load
time: `(asdf:system-source-directory X)` -> `LispSystem.baseDir` + `/`;
`(asdf:system-relative-pathname X REL)`; `asdf:component-pathname`; `(make-pathname ...)` ->
`eval/PathnameOps.makePathname`; `(uiop:merge-pathnames* A [B])`. A top-level
`(defparameter *X* <folded string>)` is recorded so a later reference reduces too. `find-system`
itself is no longer folded (the runtime answer is an object) but a nested literal
`(asdf:find-system 'x)` in a system-designator position is unwrapped (`systemDesignator`).
Quoted data is opaque and a `let`/`lambda` rebinding never triggers substitution.
- Same pass rewrites `(with-open-file (var <literal utf-8 path> [:external-format :UTF-8]) BODY)`
  into `(with-input-from-string (var <inlined contents>) BODY)`, **chunked at 20k Java-char
  boundaries** and reassembled with `concatenate` so the **JVM 65535 UTF-8 byte per-string
  ceiling** is never crossed. `:element-type` present SUPPRESSES the inlining.
- **The bundling rewrite skips a path the program itself opens for OUTPUT**
  (`collectWrittenPaths`, a pre-pass over literal namestrings behind `:output`/`:append`): baking
  a file the program then WRITES makes it read stale data. Only non-conservative shape, and it
  fails SILENTLY — hence a pre-pass, not a local check.
- Interpreter unaffected. Coverage: `LoadInlinerTest` (incl.
  `doesNotBundleAFileTheProgramItselfWrites`) + ci-spec
  `open-if-exists-append-keeps-the-existing-content`.

## ASDF component metaobjects at run time
One Lisp source `eval/asdf.lisp` (`eval.AsdfRuntimeLibrary`) defines the class family +
`find-system` + the readers + `system-source-directory`/`system-relative-pathname`/
`component-pathname` (designator-accepting, answering NAMESTRINGS, `.kb/pathnames.md`) +
`registered-systems` + `*user-cache*`. The one per-backend seam is
`%asdf-system-record`/`%asdf-system-names`; a record is `(CLASS DIR FILES DEPS LOADED-P VERSION)`
with FILES `(RELATIVE . RESOLVED)` pairs.
- **Interpreter**: Java built-ins over the live insertion-ordered `asdfSystems`/`loadedSystems`;
  a built-in shim system answers a record even before it loads. `asdf.lisp` loads lazily
  (`ensureAsdfRuntimeLoaded`) on resolution of any name it defines,
  `defsystem`/`load-system`/`quickload`/`test-system`, or any form MENTIONING a component class
  (`mentionsComponentClass`). `load-system`/`test-system` stay Java and accept the metaobject as
  a designator (`asdfDesignator`).
- **Compile paths**: `LoadInliner.inline` ends fold-then-splice — `CompileTimePathnameFolder.fold`
  FIRST (so a program whose only asdf use folds away splices NOTHING), then
  `AsdfRuntimeLibrary.process` prepends `asdf.lisp` + the baked `%asdf-registry%` table + the
  compile seam. The `Jvm/WasmExprCompiler` `ASDF_LOAD_SYSTEM`/`QL_QUICKLOAD`/`ASDF_FIND_SYSTEM`
  cases compile an ordinary call when the defun exists (`ctx.functions.containsKey`), keeping the
  stubs only as the no-pipeline fallback.
- `asdf.lisp` needs its `resource-config.json` entry (`AsdfRuntimeLibrary` `typeReachable`).

### test-op
- `parseDefsystem` records `:perform (test-op (o c) BODY...)` into `LispSystem.testOp` (body
  pre-qualified by `normalizeAsdUserForm` — bare uiop members to their home spelling, bare asdf
  FUNCTION members to `asdf:`, and a uiop member the `.asd` qualified ITSELF to that same home
  spelling) and `:in-order-to` into `testOpEdges`. A body with an unresolved `#.`, or a qualified
  `test-op :after` method, stays tolerated-and-ignored.
- `LispSystem.packageInferredClass` marks which systems instantiate
  `asdf:package-inferred-system` (the branch rove's `run-system` typecase takes).
- Interpreter `test-system` = `loadSystem` + follow edges (visited-set guard) + eval
  `((lambda (o c) BODY) nil <metaobject>)`. Compile path: `spliceSystem` emits
  `(defun %asdf-test-op-<name> (o c) BODY)`; a top-level literal `(asdf:test-system NAME)`
  splices the system AND its test-op closure (`spliceTestOpClosure`) and KEEPS the call;
  `%asdf-run-test-op` dispatches per name (edges via `%asdf-test-edge`).
- Pinned by `AsdfMetaobjectsE2eTest` (all four; fixture `src/test/resources/asdf-metaobjects-demo`),
  `PackageInferredSystemE2eTest`, `LispEvaluatorAsdfTest`, `AsdfSystemsTest`'s test-op group,
  `LoadInlinerTest.nestedLoadSystemOfASplicedSystemAnswersNilOnJvm`.

## The substitution ladder (five tiers, widest first)
1. **Whole shim system** (`eval/ShimLibraries` + `BuiltinSystems`).
2. **Replacement `.asd`** (`eval/AsdOverrides`) — system metadata only, real sources.
3. **Leaf-module shim** (`ShimLibraries.leafModuleForms`) — one component file.
4. **Derived forms** (`ShimLibraries.rewriteComponentSource`) — individual FORMS of a component.
5. **Generated component** (cl-unicode) — a component absent from the release.

Beyond the end: **refused systems** (`ShimLibraries.refusalReason`) map a name to a SENTENCE,
checked at the top of both loaders beside the conflict check. Registered: `cffi-grovel`
(grovelling compiles and runs a C program) and `cffi-libffi` (structs by value already work).

### Tier 1: built-in shim systems
Bundled shims (`src/main/resources/am/ik/rontolisp/eval/*.lisp`) resolved instead of
downloading. `BuiltinSystems.DEPENDENCIES` records edges between shims; one exists,
`flexi-streams -> trivial-gray-streams`. `ShimLibraries.forms` takes the TARGET backend's
`Features`. Replacement-by-real-library is a standing item.

- `usocket`; `trivial-gray-streams` (adapts onto rontolisp's Gray protocol,
  `.kb/gray-streams.md`); `closer-mop` (`class-slots` -> `(name declared-type)` pairs from
  `%class-slot-defs`, plus `compute-slots`, signalling `generic-function-lambda-list`);
  `flexi-streams` (REAL `flexi-stream` wrapper + in-memory octet pair); `float-features`
  (interpreter + JVM, no WASM); `bordeaux-threads` (nickname `bt`, locking subset over
  `rontolisp:*-mutex`, `.kb/mutexes.md`); `trivial-garbage` (nickname `tg`; `finalize` registers
  nothing — consequence: a leaked prepared statement lives until the connection closes);
  `trivial-cltl2` (nickname `cltl2`; `declaration-information` always nil, routing trivia's
  match2*+ onto `:trivial`); `cl+ssl` (over `rontolisp:tls-upgrade`, `.kb/tcp-sockets.md`);
  `mgl-pax-bootstrap` (package `mgl-pax`, nickname `pax`; `defsection` -> `(defvar NAME nil)`;
  its real `.asd` declares `:around-compile`, which is why the shim stays); `swank`
  (`create-server` SIGNALS — clack's `.asd` hard-depends on it).
- **`trivial-features`** — whose whole content IS the announcement
  (`BuiltinSystems.DECLARED_FEATURES` + generated `pushnew` forms from the same list, so the
  read-time and run-time halves cannot drift). Declares **`:unix`**, **`:little-endian`** and
  **`:64-bit`** (cffi's `types.lisp` reads exactly this). **The HOST half is a probe, not a
  table**: `:darwin` + `:bsd` or `:linux`, and `:arm64` or `:x86-64`
  (`BuiltinSystems.hostFeatures` from `os.name`/`os.arch`), announced on the JVM-family targets
  ONLY, so the announcement is TARGET-AWARE (`declaredFeatures(names, target)`). Needed because
  cffi's library resolution runs on `featurep` (without `:darwin` cl-sqlite picked LINUX names;
  `:arm64` is what puts `/opt/homebrew/lib` in the DYLD fallback path). An unrecognized OS or CPU
  announces NOTHING; `:32-bit`/`:windows` are never announced. **The base `Features` sets stay
  machine-independent**, keeping ci-spec `reader-features-variable`'s `(length *features*)`
  stable.
- **`babel`** (packages `babel` + `babel-encodings`) — upstream's own two layers; the MAPPING
  protocol (`lookup-mapping` over `babel:*string-vector-mappings*` -> `code-point-counter` /
  `octet-counter` / `decoder` / `encoder`) IS the codec and `string-to-octets`/`octets-to-string`/
  `string-size-in-octets` are drivers over it, because an INCREMENTAL decoder (dexador's
  `decoding-stream.lisp`) has no whole octet vector. In the one-character model an encoding IS
  its name and a mapping IS its encoding. **The counter and the decoder MUST agree on where a
  character ends** (a disagreement is a wrong-length string, not a crash) — hence the 5- and
  6-octet UTF-8 forms are consumed WHOLE by both. Errors are upstream's
  (`character-coding-error` hierarchy, `*suppress-character-coding-errors*` substituting U+FFFD /
  U+001A). **Both packages are Java-seeded, so a `.lisp`-only widening publishes nothing** — a
  new name needs its `PackageRegistry` entry. Stops at three encodings (`:utf-8`,
  `:latin-1`/`:us-ascii`); `string-size-in-octets` answers ONE value where upstream answers two
  (`.kb/multiple-values.md`). Pinned by `BabelMappingE2eTest`.
- **`uiop`** — a package stub whose definitions arrive through `eval.UiopLibrary`
  (`.kb/uiop.md`; 15 sub-packages, every unimplemented member a `not-implemented-error` stub).
  Real members: `add-package-local-nickname`, `file-exists-p`, `merge-pathnames*`, the LISTING
  family over `%list-directory`, `getenv`, `symbol-call`, `split-string`, `emptyp`/`first-char`/
  `last-char`, `if-let`/`when-let`/`when-let*`/`with-deprecation`, the temporary-file quartet,
  `native-namestring`, `uiop::get-pathname-defaults` (`""`), `print-condition-backtrace`.
  - **`uiop:symbol-call` is REAL on every backend**: the interpreter does a runtime
    name-to-function lookup; the compile paths lower it in `expandUiopStubCall` to
    `(funcall (intern (string name) (find-package pkg)) args...)` AND carry uiop's own Lisp
    definition beside the fold, because the fold covers CALL position only while the idiom is
    usually a VALUE (`.kb/symbol-runtime-api.md`). Reading it at all was the requirement: lack's
    `src/util.lisp` spells it with a SINGLE colon.
  - `print-condition-backtrace` is defined in `uiop/image` and IMPORTED into `uiop`, so both
    spellings are ONE symbol and one prelude splice.
  - `uiop:run-program` signals `not-implemented-error` (outside every backend's sandbox).
  - **`with-temporary-file` is a MACRO** and cannot reach `expandUiopStubCall`; it is a built-in
    expansion in `LispEvaluator.evalCons` and both expression compilers. A LITERALLY true `:keep`
    drops the delete entirely (keeping smart-buffer's spill path clear of the WASM
    unlink-shaped error), and `%temp-file-name`/`uiop:delete-file-if-exists` must be selected by
    `LispPreludeLibrary.referencedBySurfaceForm` and rooted in `LibraryDefunPruner`, or the
    compile paths emit "%TEMP-FILE-NAME is undefined".
  - **`EnvironmentLibrary.process` runs AFTER the whole library-splice chain**:
    `uiop:default-temporary-directory` reads `TMPDIR` through `uiop:getenv`.
- **`clack-handler-rontolisp`** — the one shim inverting two conventions, forced by clack's
  late-bound discovery: its package is NOT seeded in `PackageRegistry` (the shim carries its own
  `defpackage`, because lack's `find-package-or-load` loads only when `find-package` MISSES) and
  it is registered under TWO system names (plus the dotted `clack.handler.rontolisp`). Three
  loader rules: the interpreter's runtime `find-system` answers a BUILT-IN name before it loads;
  the builtin branch of `loadSystem` evaluates shim forms through the RESOLVING `eval(form)`; and
  `LoadInliner.spliceSystem` splices it EAGERLY after system `"clack"`. Mechanics: `.kb/clack.md`.

### Tier 2: replacement `.asd` files (`eval/AsdOverrides`)
Maps the `.asd` FILE NAME to a bundled replacement (`eval/*.asd`, written in the supported
subset); `AsdfSystems.locate` substitutes it **after locating the real file — keeping the located
PATH**, so component files still resolve against the real tree. One entry serves every backend.

| `.asd` | replacement | reason |
| --- | --- | --- |
| `ironclad.asd` | `ironclad-slice.asd` | component classes, `defmacro`-generated defsystems, `perform :around` |
| `cl-postgres.asd` | `cl-postgres-deps.asd` | under-declared deps (alexandria, cl-ppcre, usocket) + `#.*string-file*` |
| `postmodern.asd` | `postmodern-deps.asd` | top-level `eval-when` pushing features per implementation |
| `trivia.asd` | `trivia-trivial.asd` | routes `trivia` onto upstream's `trivia.trivial` base |
| `dbi.asd` | `dbi-deps.asd` | cache selection rides a thread-capability feature expression that can never match |
| `tiny-routes.asd` | `tiny-routes-lite.asd` | parseable; replaced only to ADD the opt-in `tiny-routes/lite` |
| `cffi.asd` | `cffi-rontolisp.asd` | opens with `(error "Sorry, this Lisp is not yet supported")`; names rontolisp's `cffi-sys` (`.kb/cffi.md`) |
| `cl-unicode.asd` | `cl-unicode-built.asd` | drops the build system, `component-depends-on`, fiveam |

**Every bundled replacement `.asd` and every leaf-module shim `.lisp` also needs a
`resource-config.json` entry** (`AsdOverrides` resp. `ShimLibraries` as `typeReachable`). A
missing entry fails ONLY in the native binary, as `<name> is missing from the classpath` at
`asdf:load-system` time — `./mvnw test` cannot catch it.

RETIRED: `chipz.asd` -> `chipz-crc32-slice.asd`; the real one loads verbatim on all four
(`ChipzE2eTest`) now that `fill` exists.

### Tier 3: leaf-module shims (`ShimLibraries.leafModuleForms`)
Substitutes individual COMPONENT FILES inside a real system. Both loaders consult it before
reading a component. Each shim carries the replaced file's own `defpackage` followed by
canonical-shape qualified defuns; a shim MAY instead select a package with `in-package`, and both
loaders BRACKET it like a real component (`%push-package`/`%pop-package` on the compile path,
emitted only when `selectsAPackage`).

**Key shape**: keyed by SYSTEM name, the component key being the path RELATIVE TO THE SYSTEM'S
BASE DIR (`src/prng/prng.lisp`). A substituted component **does not have to exist on disk** — the
shim short-circuits before the read.

Four motives, and a shim lives exactly as long as its motive:
- **portability**: jzon's `eisel-lemire.lisp`, `ratio-to-double.lisp` and `schubfach.lisp` (the
  last = `write-string` of `princ-to-string`, itself a Schubfach shortest round trip everywhere),
  killing both the `#.` power-of-ten table crash and the u64/u128 arithmetic; cffi's
  `src/strings.lisp` (the real file drives a babel code generator). **Making macro-time globals
  LAZY did not retire the first reason**: the special is a `defvar` with NO value form.
- **the backends already provide it**: ironclad's `src/prng/prng.lisp` -> `ironclad-prng.lisp`
  over `rontolisp:random-bytes`.
- **size of dependency, opt-in**: `tiny-routes/lite`'s `path-template.lisp`.
- **the implementation seam**: cffi's `src/cffi-rontolisp.lisp` does not exist upstream at all.
- **size of file** EXPIRES (`ironclad-public-key.lisp` retired once the real file loads). **A
  size-motivated leaf shim lives exactly as long as the real file has no route in.**

### Tier 4: derived forms (`ShimLibraries.rewriteComponentSource`)
Substitutes INDIVIDUAL FORMS and hands the rest to the caller's normal read. Each span is located
by a marker that must occur **EXACTLY ONCE** — a moved marker THROWS, naming marker and file.

**`eval/Uax15Tables`** (pinned by `Uax15TablesTest`). uax-15 builds its tables at LOAD time by
parsing 2.7 MB of Unicode text through cl-ppcre. Two rewrites:
1. **Derived spans** in `src/precomputed-tables.lisp` and `src/uax-15.lisp`: the `*unicode-data*`
   reader becomes `nil`; the combining-class + two decomposition maps become data plus one
   BUILDER `defun` each; `*canonical-comp-map*`'s maphash is RELOCATED verbatim into a builder;
   `*unicode-letters*` + the NINE hardcoded CJK/Hangul/Tangut range loops become sorted inclusive
   codepoint RANGES searched by `%lite-unicode-letter-p`; the four illegal lists become source
   RANGE rows expanded on demand in `get-illegal-char-list`. Everything that COMPUTES a
   normalization is verbatim.
2. **Forced reads**: each of the NINE bare reads of a derived table becomes `(or *T* (%lite-build-T))`.
   Read-site forcing keeps the granularity and cannot be bypassed by calling `uax-15::nfc`
   directly. The counts are an explicit inventory in `Uax15Tables.FORCED_READS` and a mismatch
   THROWS; a component NOT in the inventory is scanned for the five names and throws if it has one.

**Three things make the `(or ...)` protocol correct and are the whole trap surface**: (a) every
table global must start `nil`, so two upstream `defparameter`s of a fresh `make-hash-table` are
demoted to `(defvar ... nil)`; (b) reads inside `precomputed-tables.lisp` are NEVER rewritten
(that file is full of `(setf (gethash K *T*) V)` write places), so the composition-map builder
forces its dependency explicitly as its first form; (c) `hardcodedLetterRanges` THROWS on a loop
spelled any other way or behind any other conditional.
- The letter table is **GONE rather than lazy**: ~127,000 entries replaced by 1,332 integers of
  merged ranges; the table only pays off past ~11,000 calls interpreted and ~34,000-39,000
  compiled, and the only caller in the corpus is postmodern's `valid-sql-identifier-p`. **If a
  program ever crosses that, the answer is a memo keyed by the characters it asks about, not the
  table back.** The search shape is tuned for the INTERPRETER
  (`Uax15Tables.LETTER_PREDICATE`). Soundness: the ranges are the replaced table's key set MEMBER
  FOR MEMBER, holes included.
- **Bulk numbers are emitted as decimal runs inside STRING literals scanned by a generated
  helper, never numeric literals** (`.kb/jvm-method-size-limits.md`). The runs are a QUOTED LIST
  of **1,000-character chunks cut between integers**, never one long literal, because
  **`(char s i)` costs O(i) on every COMPILE backend**, making a single-literal scan quadratic.
  **The interpreter is 1.0x on purpose** — `Environment.charRef` now indexes the slot — so **do
  not A/B the chunking rationale interpreted**.
- When the bundled data files cannot be read, the real source loads eagerly and `rewriteTables`
  appends four IDENTITY builders from the same name list the forced reads use, plus a
  `%lite-unicode-letter-p` that is NOT an identity. Routing `unicode-letter-p` through a NAME is
  what buys that.
- Two behavior changes: `(uax-15:unicode-letter-p #\A)` answers T where the real load answers NIL
  (`.kb/reader-features.md`); and the four internal table globals read `nil` from user code until
  the API is called, `*unicode-letters*` forever.
- **The module does NOT shrink**: an ASDF-spliced tree is never pruned
  (`.kb/library-defun-pruning.md`) and the fold bakes each `with-open-file` per site, so laziness
  moves work in time, never out of the artifact. Retiring the letter table IS the exception:
  -19,926 bytes of wasm, -15,671 of JVM class.
- Pinned by `Uax15E2eTest` (all four; leads with the two lines pinning the deferral) and
  `Uax15TablesTest` (every loud guard included).

**`eval/QuriEtldTables`** (pinned by `QuriEtldTablesTest`). Upstream writes
`(defvar *etlds* '#.(load-etld-data))`, whose value is a list of two HASH TABLES — no literal
syntax (`Cannot quote: #<HASH-TABLE>`). Three spans move: the `defvar` -> `nil` +
`%lite-build-etlds`; the three `*etlds*` reads in `parse-domain` -> `(or *etlds* (...))`; and the
`with-open-file` header's path -> the LITERAL namestring **with `:element-type 'character`
dropped**, since that option is precisely what suppresses `CompileTimePathnameFolder`'s inlining.

Retired from this tier: `eval.AlexandriaSymbols`, deleted once `*package*` became a genuine
dynamic variable (`.kb/packages.md`).

### Tier 5: a GENERATED component (`eval/ClUnicodeTables`)
Three components cl-unicode names — `lists.lisp`, `hash-tables.lisp`, `methods.lisp` — **do not
exist in the release**; real ASDF materializes them through `cl-unicode/build` with
`:output-files` + a `component-depends-on` method, all outside the subset. `ClUnicodeTables`
parses the same bundled data files and emits the same definitions in Java at load time, reached
through `ShimLibraries.leafModuleForms` (which is why that method takes the base directory and
the loader).
- **The emitted shapes match `build/dump.lisp` exactly, quirks included**, so upstream's own test
  suite stays meaningful: `build-range-list` returns at `+code-point-limit+ - 1`;
  `split-range-list` picks its middle with `(round (1- length) 2)`; the `pushnew` lists come out
  in reverse order of first appearance.
- **The dumps are not quoted literals**: ~5 MB, ~140,000 numbers and ~68,000 names (~208,000
  constant pool entries against 65534). Each table travels as PRINTED TEXT in ~230 string
  literals read back with `read-from-string` (570 entries), and each of the fourteen range trees
  became a flat range table `%lookup` binary-searches, BUILT ON FIRST LOOKUP. Retiring the
  balanced tree is the one FAITHFULNESS deviation and it is free. A quoted list is walked
  recursively in `PackageResolver.resolveQuotedDatum`, so 200,000 elements in one literal
  overflows the stack, and the reader recursing per element caps a chunk at **1,000 elements**.
- Pinned by `ClUnicodeTablesTest` and `AsdfSystemsTest.parsesTheBundledClUnicodeReplacementAsd`.

## Libraries that load, and what is pinned
Every entry loads VERBATIM upstream sources unless a substitution is named.
`AsdfLibraryE2eSupport` hosts the four-backend E2Es and needs a VENDORED tree per system; a
library living only in the quicklisp cache is verified MANUALLY on all four.

- **cl-who 1.1.5** — `ClWhoE2eTest` (interpreter + JVM). `with-html-output` expands at
  MACRO-EXPANSION time, so backends see baked string constants. The macro-time config replay is a
  **static purity judgment, not a data file**: `UserMacroExpander.isPureConfigSetf`/`isPure`
  accept a top-level `(setf (PLACE ...) V)` only when the `(defun (setf PLACE) ...)` writer is a
  pure config setter. **Deny by default**; no per-library registration, no
  `eval-when (:compile-toplevel)` escape hatch. Top-level `defvar`/`defparameter`/`defconstant`
  register LAZILY (`Environment.defineLazy`, `.kb/defmacro-backquote.md`). Lite: `:indent`,
  `(let ((*html-mode* ...)) ...)` ignored (use `(setf (html-mode) :html5)`), hyperdoc's
  external-symbols loop empty. **NO render ci-spec case is possible** — the corpus tests
  re-compile ci-spec sources WITHOUT `--system-path`.
- **cl-base64 3.4** — `ClBase64E2eTest` (all four); ci-spec `cl-base64-residue-features`. Limits:
  WASM degrades an integer beyond `i31` to a float, so `integer-to-base64-string` diverges for
  large integers; `base64-stream-to-*` untested.
- **assoc-utils** — `AssocUtilsE2eTest` + `AssocUtilsUpcaseE2eTest` (all four); ci-spec
  `assoc-utils-features`. Introduced `eval/LispPreludeLibrary` (recursive rontolisp-source defuns
  spliced by `process` when referenced, lazy-loaded by the interpreter in `resolveFunction` via
  `loadedPreludeNames`), the 5-value `define-setf-expander` protocol
  (`LispEvaluator.setfExpanders`; `UserMacroExpander` rewrites `setf`/`incf`/`decf` on a user
  place BEFORE the compilers, so the expr-compiler case is a nil no-op) and
  `LoopExpander.parseForBeingHash`. Lite: `equalp` on arrays/hash-tables/structures falls back to
  `eql`; `define-modify-macro` place subforms may double-evaluate.
- **ironclad 0.61 slice** — `IroncladE2eTest` (all four). `ironclad-slice.asd` declares
  `ironclad/core`, `/digest/sha256`, `/digest/sha512`, `/mac/hmac`, `/kdf/pkcs5`, `/kdf/hmac`,
  `/kdf/password-hash`, `/kdfs`, `/public-key/rsa`, aggregate `ironclad`.
  - **One deliberate layout deviation, forced by eager compilation: kdf/kdf.lisp loads LAST.** It
    `make-instance`s every KDF class and the compilers expand `make-instance` where it stands, so
    kdf/hmac.lisp is declared a DEPENDENCY of `ironclad/kdfs`, not a sibling.
  - `bordeaux-threads` dropped (zero call sites). Out of scope: ciphers, aead, octet-stream, the
    rest of prng/, non-RSA public-key, scrypt/argon2/bcrypt.
  - **Not a rontolisp gap**: PS512 with a 1024-bit key trips ironclad's own assertion.
  - **`random-data` is a leaf shim** over `rontolisp:random-bytes` keeping upstream's REJECTION
    SAMPLING (a modulo fold would bias a SCRAM nonce). `:fortuna` is not implemented.
  - **Why the SCRAM names had to exist before cl-postgres could compile at all**: an undefined
    function is a **compile-time** error on JVM/WASM while the interpreter defers it.
  - `gen-client-proof` XORs two 32-byte digests as 256-bit INTEGERS (`.kb/wasm-bignum.md`).
    **Pinned edge case**: `integer-to-octets` returns the MINIMAL vector, so a proof whose high
    bytes cancel comes back shorter than 32 and must be padded; an off-by-one is a silently wrong
    proof surfacing only as an auth failure.
  - **Native PBKDF2 on the INTERPRETER only** (`eval/IroncladNative` + `eval/Sha2Kernels`):
    `loadSystem` rebinds `IRONCLAD::PBKDF2-DERIVE-KEY` to a Java kernel when the DEFINING system
    finishes (the `LinalgSimd` interception shape, keyed on the definition). Always on and **not
    a `.kb` divergence** — PBKDF2 is a spec-defined function of its arguments (interpreted
    17,091 ms vs 9 ms). `Sha2Kernels` is hand-written rather than `MessageDigest` because it is
    compiled into the native binary AND the browser Web Image. Pinned by `IroncladNativeTest`.
- **cl-postgres** — `ClPostgresE2eTest`, which runs by DEFAULT (Docker is its only gate).
  `open-database` + `exec-query` complete a real round trip against PostgreSQL 17 under `trust`,
  `password`, `md5` AND SCRAM-SHA-256 on interpreter, JVM and `--component`; **Preview 1 has no
  TCP by design** (`.kb/tcp-sockets.md`) and its socket calls compile to CALL-TIME error stubs.
  **Its `#.*string-file*` component must stay `strings-utf-8`** — `strings-ascii` announces
  `client_encoding SQL_ASCII` and desyncs the connection on a non-ASCII parameter. Harness: a
  Testcontainers `postgres:17-alpine` with ONE ROLE PER METHOD so a broken rung cannot fall back.
  **The component leg connects to the container's IP ADDRESS**, because `tcp-connect` takes only
  IPv4 literals on WASM. Recorded costs (regression = a change to this file): scram connect
  first/second interpreter 155/68 ms, JVM 214/100, component 140/137.
- **alexandria 1.0.1** — `AlexandriaE2eTest` (all four); vendored UNMODIFIED. **The exercise IS
  `examples/asdf/alexandria-demo.lisp` verbatim — keep the two in sync.** ci-spec
  `last-with-a-count` / `every-some-over-many-sequences` / `coerce-to-a-computed-result-type` /
  `read-sequence-into-a-character-buffer`.
  - **Two cross-backend CORRECTNESS bugs fell out.** (1) **`#'mapcar` as a first-class VALUE
    silently dropped every list but the first on both compile backends**; every member now takes
    N lists everywhere — **read `.kb/map-family.md` before touching any of them**. (2) **An
    injected wrapper whose body calls `apply` was reachable while WASM's `apply` runtime was not
    emitted** (`usesEval` scans the SOURCE program, wrappers are injected after it), answering
    `(NIL NIL)`. `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` + `referencesApplyingWrapper`
    name that set and the gate consults it; pinned by
    `WasmLispCompilerIntegrationTest.applyUsingWrapperReachedByFuncallCompilesAndRuns` — **each
    assertion must be the ONLY form in its program**, so ci-spec cannot cover it.
  - Still NOT supported: `type=`; `format-symbol`/`ensure-symbol`/`ensure-function` on a symbol
    (interpreted only, loud compile errors); `shuffle`/`random-elt`/`gaussian-random` draw each
    backend's own entropy.
- **jzon 1.1.4** — `JzonE2eTest` (all four). Its numeric leaves are the jzon leaf shims. Accepted
  WASM divergences kept OUT of the exercise: large-float print shape, hash-table iteration order,
  non-ASCII `\u` escapes.
- **uax-15 0.1.3** — `Uax15E2eTest` (all four). Table substitution is tier 4. Forced the pathname
  primitives + `with-open-file` inlining, a `LOOP` per-clause iteration-head rewrite, and the
  **WASM UTF-8 string byte model** (`.kb/wasm-gc-strings.md`). Also drove
  `WasmComponentBuilder.memModuleFor` (the shared canonical-memory module's initial page count
  follows the core module's memory-import minimum).
- **cl-unicode 0.1.6 + cl-ppcre-unicode + cl-str** — `(ql:quickload "str")` loads and
  `(str:title-case "HELLO LISP!")` answers on **all four**. Three prerequisites in order: (1) the
  **`equalp` key fold on every backend** (`LispEquality.equalpKey`, `.kb/hash-tables.md`) —
  without it the load dies at `Unknown property name "Cs".`; (2) the generated components
  stopped being LITERALS (tier 5); (3) the WASM bump heap stopped getting a fixed three growth
  pages (`.kb/wasm-gc-heap-pregrow.md`). cl-ppcre's `nsubseq` shim is RETIRED
  (`make-array :displaced-to` gives a real string VIEW, `.kb/adjustable-arrays.md`). Also needed
  `*compile-verbose*`/`*compile-print*` and two character names.
- **s-sql** — ci-spec `s-sql-enablement-language-group`; quickload MANUAL. Renders identically on
  ALL FOUR — s-sql opens no sockets, and its cl-postgres dependency's socket chain lowers through
  `LispMacroExpander.callTimeUnsupportedStub` on Preview 1 (the pruner cannot drop a
  defmethod-anchored chain: `LibraryDefunPruner.definitionName` prunes defun/defvar-family only).
  **One replay rule landed**: a top-level `(let (...) <definitions only>)` is evaluated WHOLE into
  the macro-time evaluator (`UserMacroExpander.registerMacroTimeDefinitions`), because
  `register-sql-operators` closes ~230 `expand-sql-op` defmethods over a `make-expander` closure.
  The ~230-method eql dispatcher stays under the method-size guards with no chunking.
- **postmodern** — `PostmodernE2eTest` (opt-in `RONTOLISP_POSTGRES_E2E=1`); ci-spec
  `postmodern-non-mop-milestone`. `with-connection`/`create-table`/`insert-into`/`query`/
  `with-transaction`/`update`/`query :single` byte-identical on interpreter, JVM and
  `--component`, with `:reconnect` and `retry-transaction` driven for real. Preview 1 is a
  compile error by design. `postmodern-deps.asd` takes both feature decisions statically:
  - **`:postmodern-use-mop` ON** — `table.lisp` joins ahead of `deftable.lisp`, `closer-mop`
    arrives through upstream's own `(:feature :postmodern-use-mop "closer-mop")`, and the
    `defpackage` takes its `#+postmodern-use-mop` branch (`.kb/packages.md`). The `:if-feature`
    clauses stay VERBATIM so the decision remains a feature flip — pinned by
    `AsdfSystemsTest.thePostmodernMopBuildIsAFeatureFlip`.
  - **`:postmodern-thread-safe` ON** via `:rontolisp-features`. Honest because
    `bt:with-lock-held` really serializes (`.kb/mutexes.md`); OFF was a genuine narrowing, since
    rontolisp runs concurrent handlers.
  - **`:depends-on` differs from upstream three ways, all deliberate**: `global-vars` DROPPED
    (zero call sites), `bordeaux-threads` follows the feature, `cl-ppcre` + `uax-15` ADDED (called
    but never declared upstream; leaving them out would make the eagerly-resolving compile paths
    depend on somebody else's `.asd` order). `postmodern/tests` is not reproduced; `simple-date`
    is not a dependency at all. `:nicknames (:pomo)` registers through `PackageResolver`.
  - One deviation remains visible: a condition prints as a slot dump rather than via `:report`.
- **quri 0.7.0** — ci-spec `quri-enablement-language-group` + `QuriEtldTablesTest`; quickload
  MANUAL. Deps: alexandria, split-sequence, cl-utilities, idna + the babel shim. Substitution:
  `QuriEtldTables` (tier 4).
  - **`apply` through a COMPUTED designator has no arity ceiling.** `_apply` used to fall off the
    per-arity ladder — nil on the JVM, an `unreachable` trap on WASM. Both backends gained a
    SPREAD dispatcher (`_invoke_v` / `FUNC_DISPATCH_SPREAD`) over EVERY callable; a variadic
    target gets the remaining TAIL, which IS its physical rest parameter.
  - **`format`'s destination is decided at RUN time** when it is not literal `nil`/`t`
    (`formatDestinationDispatch`). The Gray-streams `format` rewrite used to override it and now
    performs the same test ([gray-streams.md](gray-streams.md)). **Only the concatenated ci-spec
    program catches this.**
  - A plain BUG found here: `LispMacroExpander.rewriteLocalCalls` tested for a non-symbol head
    BEFORE testing for an improper list, and that branch rebuilds from `cons.toList()`, DROPPING
    an improper tail. The improper-list test now runs first.
  - **ONE limitation remains**: quri's `:lenient` percent-decoding crashes on the three compile
    backends when the input really is malformed — a `go` out of a `handler-bind` handler into an
    enclosing `tagbody` (`.kb/do-return-block.md`). Well-formed input never reaches it.
- **local-time 1.0.6** — ci-spec `local-time-enablement-language-group`; quickload MANUAL. The
  whole timestamp API is byte-identical on ALL FOUR. **Real TZif timezone files load** wherever
  the host has a filesystem; where they cannot be read local-time's own `handler-case` falls back
  to `+utc-zone+`, which is why a failed `open` had to SIGNAL rather than trap on WASM
  (`.kb/read-load-streams.md`).
  - **The four load-context pathname variables** `*load-pathname*`/`*load-truename*`/
    `*compile-file-pathname*`/`*compile-file-truename*` (`LispNames`,
    `PackageRegistry.CL_VARIABLES`). The first pair holds the file being loaded on EVERY backend
    (interpreter REBINDS per file; the compile paths ASSIGN per spliced file from `LoadInliner`'s
    `%begin-file` brackets) and is established at READ time too (`.kb/load-inliner.md`). **An
    ASDF COMPONENT is loaded by its RESOLVED path**, which makes its `*load-pathname*` equal
    `asdf:component-pathname` — `LoadContextE2eTest` pins that equality.
    **The compile-file pair is permanently nil, at read time too, deliberately**, for three
    reasons — any one expiring is the trigger: (a) `(or *compile-file-pathname* *load-truename*)`
    now answers through its fallback arm; (b) local-time spells
    `#.(or *compile-file-truename* '*load-truename*)`, whose whole point is that the first arm is
    nil; (c) a library branching on it is asking "am I being compiled to a fasl", and the honest
    answer is no. **If a real `compile-file` ever exists, bind both pairs together and revisit
    local-time's spelling in the same pass.**
    **`*readtable*` rides the same list**: nil everywhere, but a loader binds it in the SAME `let`
    as the pathname pair, so `injectMvSpillGlobal` declares whichever of the four the program
    mentions, or the rebinding fails with `Cannot compile symbol reference: *READTABLE*`.
  - `merge-pathnames` and `truename` are `LispPreludeLibrary` entries (one Lisp definition over
    primitives), unlike `make-pathname`/`uiop:merge-pathnames*`, which stay Java + compile-time
    folding. `truename` is `(or (probe-file p) (error ...))`, whose load-bearing half is the
    SIGNAL. **One residue**: local-time's DEFAULT repository path is computed with a runtime
    `make-pathname`, so the compile paths need an explicit `:timezone-repository`.
  - Also landed: the `find` family taking the whole position keyword set (`buildPositionScan`,
    `positionScanValues.elementResult`), `make-array :initial-contents` from a packed vector, and
    `:element-type 'unsigned-byte` opening a BINARY stream.
  - **Two pre-existing backend defects had to be fixed**: argument evaluation order was
    right-to-left for `list` and backquote on all three compile backends
    (`.kb/argument-evaluation-order.md`), and `MAX_CALLABLE_ARITY` was raised to 10.
- **lack / clack `.asd` front end** — the system-level `:pathname` prefix, the
  `register-system-packages` skip and the ignored keyword options let the unpatched `lack.asd`
  parse whole. Pinned by `AsdfSystemsTest` (`parsesTheVerbatimLackAsd`,
  `loadSystemNameIgnoresKeywordOptions`, the `:pathname` group). Rest: `.kb/clack.md`.
- **fast-io 1.0 + circular-streams** — `FastIoCircularStreamsE2eTest` (all four). Three general
  mechanisms: the `.asd` feature announcement, `with-slots` binding a write-only unbound slot,
  and a `slot-value` naming a slot NO class declares lowering to a RUN-time error instead of
  failing the compile (both [clos.md](clos.md)). **Two upstream facts the exercise encodes,
  verified against SBCL 2.2.9**: circular-streams cannot see the end of a fast-io input stream
  (fast-io's `stream-read-byte` SIGNALS `end-of-file` where Gray wants `:eof`), and the `#.`
  `:long-description` is dropped unresolved. **One blocker is NOT fixed**: fast-io's
  `defmethod close` makes `close` a user generic that DROPS the built-in on the interpreter
  ("No applicable method: CLOSE on INTEGER" anywhere in the image) while the compile paths ignore
  the method — a general "a user definition of a built-in name" gap.
- **trivia** — `TriviaE2eTest` (all four); ci-spec `trivia-enablement-language-group`.
  **DIVERGENCE RECORD**: `trivia.asd` -> `trivia-trivial.asd`, declaring `trivia` as metadata over
  upstream's own sanctioned `trivia.trivial` base. Upstream `trivia` depends on
  `trivia.balland2006`, the match-clause OPTIMIZER, which needs iterate + type-i and buys ZERO
  semantics. **Re-evaluation trigger**: a real consumer needing iterate itself, or interpreter
  match performance becoming the bottleneck. Measured: a 4-clause `match` in a defun costs
  ~0.31 s PER CALL interpreted (the interpreter re-expands user macros every evaluation); the
  compiled backends are unaffected. Shims it needed: `trivial-cltl2` + two `closer-mop`
  additions. **General mechanism**: `UserMacroExpander` REPLAYS plain top-level forms of SPLICED
  SYSTEMS (inside `%begin-system`/`%end-system` brackets) into the macro-time evaluator and honors
  the compile-file situations of a macro-EXPANDED `eval-when`
  ([defmacro-backquote.md](defmacro-backquote.md)).
- **sxql** — `SxqlE2eTest` (all four); ci-spec `sxql-enablement-language-group`. `sxql:yield`
  produces SQL text + binds as multiple values BYTE-IDENTICALLY on all four, verified against SBCL
  2.2.9. Two shapes worth carrying: **runtime slot names normalize to the base spelling** inside
  `%slot-value-runtime`/`%slot-value-set-runtime`/`%slot-boundp-runtime` — `(intern (symbol-name
  n))` **deliberately IN the public defun, not at call sites**, so the spelling is visible to the
  WASM `usesIntern` gate; and **`subtypep` walks struct `:include` ancestry** (with
  `structure-object` as every struct's supertype), resolves a user **deftype** on either side, and
  takes an **`(or ...)`** compound on either side. The shared static `LispMacroExpander.subtypep`
  serves the interpreter and the literal fold, and `subtypepUniverse` includes struct names,
  `structure-object` and deftype names so `%subtypep-ancestor-table%` answers identically.
  Residue: lisp-namespace's `pprint-logical-block` compiles to a call-time error stub.
- **The mito-closure tolerance batch** — four parse-level widenings, each a whitelist entry and
  **never a sink**. (1) `(:version NAME "1.2.3")` entries. (2) A top-level `(defmethod perform
  ...)` tolerated, since WIDENED to every top-level method with `source-file-type` read; ignoring
  esrap's `perform :after` capability pushes is a **VERIFIED decision** (a grep of the cached dist
  found zero `#+esrap.` reads). **Trigger**: if anything starts reading one, fold the keywords
  into `LispSystem.features()` via `collectFeaturePushes`. (3) A top-level component-class
  `defclass`, generalized to any component class. (4) `mgl-pax-bootstrap` as a shim.
  Two resolver rules landed in `PackageResolver.resolve`: **a literal top-level qualified
  `define-package` is consumed exactly like `defpackage`** (its extra clauses still error until a
  consumer needs them), and **`pax:defsection` AUTOEXPORTS its `(SYMBOL LOCATIVE)` entries**
  (`consumeDefsectionExports`) — trivial-utf-8's ONLY export mechanism.
- **cl-dbi + dbd-postgres** — manual three-backend run; Preview 1 out, and a component needs
  `-S tcp=y -S inherit-network=y`. **`dbi.asd` -> `dbi-deps.asd`**: upstream PARSES fine, but its
  cache selection rides a thread-capability feature expression that can never match and **MUST
  NOT be satisfied by claiming one** (the additive-features rule), so the verbatim parse picks
  single-threaded `cache/single.lisp` on backends that really run concurrent handlers. The
  replacement takes the decision per backend (`thread.lisp` + real `bordeaux-threads` behind
  `:if-feature (:not :rontolisp-wasm)`, `single.lisp` on WASM). **DIVERGENCE RECORD + trigger**:
  re-evaluate if WASM gains threads or upstream's expression changes shape. Also:
  `rontolisp:current-thread` (`.kb/threads.md`), the bt shim's `make-lock` widened to `&rest`,
  `uiop:define-package` + `:use-reexport` + `:shadowing-import-from` (`.kb/packages.md`), the
  `trivial-garbage` shim, and `asdf:missing-component`/`asdf:retry` as resolve-only externals
  (dead here — a missing system is a hard Java-side error, never a signaled condition). **The
  runtime driver load short-circuits as designed**: `dbi:connect` probes `find-driver` FIRST, so
  the compile-path stub is dead once the driver is loaded.
- **mito-core** — ci-spec `mito-core-enablement-language-group`; quickload manual. The DAO CRUD
  round trip runs identically on interpreter, JVM and component. New deps: **dissect** (empty-body
  no-op — no backend carries a Lisp call stack) and **uuid** (via ironclad + trivial-utf-8).
  Enablement owned per topic: `.kb/clos.md`, `compiler/FreeVarAnalyzer` (case clause key lists as
  data — `(lambda flet labels)` as keys used to capture LABELS),
  [jvm-method-size-limits.md](jvm-method-size-limits.md), `.kb/packages.md`, plus the
  `pax:defsection` export residue (`UserMacroExpander.paxDefsectionExportForm` emits a literal
  top-level `export`, since the defsection expands to a defvar at macro time).
- **esrap** — `EsrapE2eTest` (all four; Preview 1 in); ci-spec
  `esrap-enablement-language-group`. Exercise: esrap's README example plus mito's
  `migration/sql-parse.lisp` grammar VERBATIM. Two gaps gave a WRONG answer rather than an error:
  - **The SIZE of `(string N)` / `(simple-vector N)` / `(vector T N)` is checked.** Unchecked,
    `(typep sub '(or character (string 1)))` answered true for every string and
    `(typep cell '(simple-vector 41))` answered nil for the packrat cache's own vector.
    **`(simple-vector N)` carries only a SIZE** while `(vector ELEMENT-TYPE SIZE)` leads with the
    element type; reading one as the other produced both.
  - **`(cons CAR-TYPE CDR-TYPE)`** was not a compound specifier at all — its arguments fell
    through to the ranged-NUMERIC default. A non-numeric atomic type spelled compound now ignores
    its arguments.
  Others owned per topic: a constant DOTTED pair surviving a nested backquote
  ([defmacro-backquote.md](defmacro-backquote.md)), multiple `(:constructor ...)`
  ([defstruct.md](defstruct.md)), `:test-not` as ONE shared `TestSpec`, **`reduce` over an EMPTY
  sequence calling its function with ZERO arguments** (on WASM a wrong-arity indirect call TRAPS),
  the printer surface ([pretty-printer.md](pretty-printer.md)), `~<...~:>` and `~/name/`
  ([format.md](format.md); a `~/name/` is the only trace of a function REFERENCE, so
  `LibraryDefunPruner` scans string literals for it), `*modules*`, `(compile nil lambda-form)`
  EVALUATING the form, and **a macro-GENERATED `deftype` reaching the compilers' registry**
  (`UserMacroExpander.emitMacroGeneratedDeftypes`). Residue: `set` and `break` are undefined;
  `char-name` answers nil for a graphic character, which is CL.
- **tiny-routes** — `TinyRoutesE2eTest` (all four) plus the three tiny-routes legs of
  `ClackE2eTest` (the only coverage of the LIVE `ql:quickload` and of
  `tiny-routes-middleware-cookie`). Three general gaps, each a hard failure:
  1. **The LOOP anaphoric `it` was unbound in any package but `cl-user`** — the substitution
     matched the symbol by RAW name and the expander runs AFTER `PackageResolver`. **Matching the
     MEMBER (`splitQualified`) in any package is the rule**; `(loop-finish)` carried the identical
     compare.
  2. `uiop:if-let`/`when-let`/`when-let*`/`with-deprecation` as built-in macro expansions.
  3. **A serve component whose program ends in a non-`cl-user` package**: the bridge
     `eval/HttpLibrary` synthesizes is appended AFTER the program, so `%serve-dispatch`/
     `%serve-request-body`/`%serve-handle` now carry an explicit `cl-user::` qualifier; the
     handler reference is deliberately left unqualified.
  `tiny-routes/test` is out of scope (fiveam -> `net.didierverna.asdf-flv` stops on `:long-name`).
- **tiny-routes/lite** — the OPT-IN ppcre-free variant; three tiers composed (`AsdOverrides` ->
  `tiny-routes-lite.asd` redeclaring the primary VERBATIM and adding the secondary system;
  `ShimLibraries.LEAF_MODULES` substituting `path-template.lisp` under THAT system name only;
  `AsdfSystems.locate`'s secondary-name rule; `DistClient.ensureAvailable`'s fallback that a slash
  name downloads its PRIMARY's release). **Both tiers are needed together** — the file swap alone
  is -0.9%, because the unreferenced engine stays anchored through its CLOS surface
  (`.kb/optimize-dead-code-elimination.md`). **The matcher's contract is "matches identically or
  refuses loudly"**: tokens are `:([A-Za-z_][A-Za-z0-9_-]*)` anywhere in the template, so
  `/a/:x-:y` parses as ADJACENT tokens — **do not "fix" that, it is upstream's own behavior,
  empirically pinned**. A template containing live regex syntax or `:regex t` SIGNALS at
  route-build time. Pinned by `TinyRoutesLiteE2eTest` (vendored tree WITHOUT cl-ppcre — passing
  at all proves the dependency is gone) and `TinyRoutesLiteUpstreamParityTest`. **Co-loading the
  two systems is REFUSED by both loaders in both orders** (`ShimLibraries.conflictingSystem`).
  **A cl-ppcre/lite in the same pattern was built, parity-pinned and then REJECTED (user
  decision) — do not re-propose it.**
- **rove 0.10.0** — `RoveE2eTest` (all four). `src/test/resources/rove-demo` carries BOTH system
  shapes rove's `run-system` typecase dispatches on: a `:package-inferred-system` (suite found
  through the `component-sideway-dependencies` walk) and a plain `defsystem` (suite found through
  the `*load-pathname*`-keyed file->package map). Harness catch-up:
  `AsdfLibraryE2eSupport.compileProgram` hands `LispPreludeLibrary.process` the TARGET feature set
  and runs `EnvironmentLibrary.process` per backend. Expected lines verified against SBCL 2.2.9
  modulo durations, `#+sbcl` lines, dissect stacks, and ONE text divergence: the printer spells an
  accessible symbol package-qualified (`MY-APP/MAIN:ADD` where SBCL prints `ADD`) — **flip
  `RoveE2eTest`'s expected lines to the SBCL spellings when that lands.** Non-goals that hold:
  `uiop:lispize-pathname` unreached, `deftest`'s `:compile-at :run-time` interpreter-only,
  `:style :none` on a compiled program needs the program to load `rove/reporter/none`, and a raw
  wasm TRAP still ends the run.
- **The examples are rove's first consumer** (`examples/console/roman.lisp`,
  `cloudflare-workers/httpbin/check.lisp`, `browser/minesweeper/minesweeper-core-test.lisp`).
  Shape, taught in `guides/testing.md`: a single file with `(asdf:load-system :rove)` +
  `(use-package :rove)` + `(uiop:quit (if (run-suite *package*) 0 1))`, spelling the VENDORED
  route so `-Drontolisp.examples=true` passes with no network. **`examples.yaml`'s `systemPath`
  is a LIST** (each element absolutized separately; `ExamplesE2eTest.systemPathFlags`).
  `IndentRules` gained rove's body-taking operators (`testing`/`failing` -> `body(1,2)`,
  `setup`/`teardown` -> `body(0,2)`, `defhook` -> `body(2,2)`). **What the conversion FOUND**: an
  inner `handler-case` not shadowing an enclosing `handler-bind` (`.kb/error-handling.md`) and the
  `#'coerce`/`#'elt` sweep (`.kb/core-representation.md`).
- **`rontolisp test` — the CLI runner.** `TestCommand` (package `cli`) is a SOURCE GENERATOR, not
  a mode: it builds ONE program (rove prologue + target + verdict epilogue) and hands it to the
  ordinary `RontoLispCli` pipeline, so `-o` compiles the SAME program and every artifact carries
  the identical exit contract. Hence `test` is parsed by `CliOptions` like an ordinary run, unlike
  `format`. **Upstream `roswell/rove.ros` is the spec** (read it in the quicklisp cache; it is NOT
  vendored).
  - **The one hook that is not cosmetic**: rove sets `*last-suite-report*` in `call-with-suite`
    ONLY, which **`rove:run-suite` — the shape this command exists for — does not** reach. So the
    prologue defines `(defmethod rove:invoke-reporter :after ...)` setting it from
    `rove/core/stats:stats-results`: `invoke-reporter` is the one funnel EVERY entry point
    crosses. Without it the runner would re-run the target's tests
    (`RoveTestCommandE2eTest`'s `containsOnlyOnce("Summary:")`). A second replacement in the same
    prologue: `(defmethod rove:run-suite (suite &key (style rove:*default-reporter*)) ...)`, same
    specializer so CL redefinition REPLACES rove's method, because rove's own hard-codes `:spec`
    and `-r dot` would silently do nothing for exactly the FAQ shape.
  - **Deliberate divergences from `rove.ros`** (triggers): failure exits **1**, not `-1` (the
    8-bit mask turns `-1` into 255); no `COVERAGE` arm; the target's stdout is NOT swallowed,
    because on the `run-suite` path that would swallow the REPORT — **if rove ever moves the
    `*standard-output*` rebinding into `run-suite-tests`, the swallow becomes safe**; a `.lisp`
    file whose `defpackage` names no locatable system is LOADED instead of erroring; running NO
    test exits 1 where upstream's `(every #'passedp '())` is a vacuous pass; a system designator
    is a valid target; `-r` accepts only `spec|dot|none`, an unknown one being exit **2**, and
    `none` IS loaded up front; colors follow the destination. A plain `rontolisp FILE` still
    exits 0 after a red suite (`.kb/toplevel-statement-values.md`) — deliberate.
  - Coverage: `RoveTestCommandE2eTest` (8 tests, all four backends as SUBPROCESSES) + two
    `RontoLispCliTest` cases. **No ci-spec case is possible.** Docs: `guides/testing.md`.
- **cl-mustache 0.12.3** — `ClMustacheE2eTest` + `ClMustacheSpecE2eTest`, both all four. **It was
  the FORCING FUNCTION for seven general gaps**: `ctypecase`; **`gethash`'s present-p surviving a
  FUNCTION return** (without it every `{{name}}` rendered empty — `.kb/multiple-values.md`); `#.`
  read-time eval seeing `*load-truename*` on the COMPILE path; `signal` of an unhandled condition
  being fatal on the three compiled backends; hash-table printing (it TRAPPED both WASM
  backends); WASM float printing; and a runtime-computed absolute path resolving against the
  preopen NAMES rather than fd 3 (`.kb/read-load-streams.md`). `ClMustacheSpecE2eTest` loads the
  vendored 194-case spec suite verbatim through a 20-line stand-in printing ONE character per
  case, so the expected line pins the pass/fail SET. **158/194 is parity with upstream, not a
  shortfall** (SBCL 2.x fails the same 36) — **a REGRESSION pin: if it moves, a rontolisp change
  moved it.** Upstream's `t/test-api.lisp` scores 20/20 but is NOT in the E2E (its `#.` bakes the
  HOST path of `t/test.mustache`).
- **jose** — `JoseE2eTest` (HS/RS/PS/none, every correctable condition; pinned against SBCL 2.2.9
  AND the HMAC tokens against Python's `hmac`/`hashlib`) and `JoseTestSuiteE2eTest` (upstream's
  OWN `jose/tests/jwt` through rove, the `rontolisp test jose/tests/jwt` shape), both all four,
  shared path in `JoseSystems`. A `:class :package-inferred-system`. **The whole dependency graph
  loads unpatched** — cl-json, cl-base64, ironclad, split-sequence, assoc-utils, alexandria,
  trivial-utf-8. Two general bugs found finishing it: `with-input-from-string` over a MUTABLE
  character vector cast to `String` on the JVM, and **a multi-pair `setq` whose later value form
  builds a closure** — `FreeVarAnalyzer` walked the first pair only, so captures were never
  recorded (silently wrong on the JVM, `Cannot find variable for closure` on WASM). The second is
  cl-json's. `jose/tests/jws` is excluded and always will be (it `(:use #:pem)`, in no dist).
  **Non-goal: JWE.**
- **iterate 1.6.0** — `IterateE2eTest` (all four; Preview 1 in). Nothing iterate-specific lives in
  the tree; the five prerequisites are owned per topic: the `,.` splice and the `#L`/`#nL` reader
  macro, the `ldiff`/`sublis`/`gentemp` prelude defuns, `with-hash-table-iterator`, and
  **`macro-function` answering nil for rontolisp's own `while`**
  ([symbol-runtime-api.md](symbol-runtime-api.md)) — a yes made `(iter ... (while test))` loop
  forever. ci-spec `sharp-l-comma-dot-and-hash-table-iterator`. Two shape facts matching SBCL: a
  clause head is an ordinary symbol read in the CURRENT package (the exercise does
  `(use-package :iterate)`), and `iterate-test.lisp` does not ride along (written against `rt`).
  **Trigger to revisit**: vendoring `rt` or porting the suite to rove. Note the trivia DIVERGENCE
  RECORD — iterate WORKING does not fire its trigger; `trivia.balland2006` still needs `type-i`.
- **array-operations 1.2.1** — ci-spec `array-operations-enablement-language-group`; quickload
  manual. A `:class :package-inferred-system` over let-plus + anaphora; the API runs IDENTICALLY
  on all four. The `.asd` needed ONE widening, `:long-name`. Everything else owned per topic: the
  nested `with-slots` instance temp ([clos.md](clos.md)), the read-modify-write place once-only
  rule ([argument-evaluation-order.md](argument-evaluation-order.md)), **a `defmacro` a macro
  expands to inside a `progn` or a closing `let`** ([defmacro-backquote.md](defmacro-backquote.md)
  — without it the JVM and both WASM builds died on "The function DEFMACRO is undefined"), the
  float-range constants (`LispReader.CL_FLOAT_CONSTANTS`), and an ITERATIVE `equalp` over an array
  and a list (the old `labels` recursion blew the interpreter stack on two rank-5 arrays).
  **Standing result**: upstream's clunit2 suite scores **217/219 here against SBCL 2.2.9's
  219/219**. The remaining 2 are ONE rontolisp answer and it is CONFORMANT:
  `(array-element-type (make-array 4 :element-type 'fixnum))` answers `T`. The two gaps that
  closed were the RANK-0 array (`.kb/array-literals.md`) and the ARRAY TYPE LATTICE
  (`.kb/declarations-type-checks.md`).

## Why this is a shim and not real ASDF (spike)
Upstream ASDF 3.3.7 (14,130 lines, 700 KB) **loads end to end** in the INTERPRETER with a patch
set, and `find-system` really walks `*central-registry*` and runs `parse-defsystem`. Three things
decided against it: (1) it is a PORT — upstream refuses on an unknown implementation, `detect-os`
refuses without `:unix`/`:windows`/`:genera`/`:os-macosx`, and 34 `not-implemented-error` sites
sit behind 230 implementation reader conditionals (though filling exactly ONE hole, `getcwd`,
made `probe-file*`/`ensure-pathname`/`sysdef-central-registry-search` work); (2) startup — 2.85 s
wall / 16.7 s CPU against a 0.40 s floor for `(print 1)`, ~7x the whole current startup, which
only "do not load it unless the program needs it" survives; (3) the cyclic-key
`StackOverflowError` that was OURS has since landed (`.kb/hash-tables.md`), so reasons 1 and 2
still decide it. Still no `eq` table. Also: the compile paths resolve systems at COMPILE time and
the browser playground has no filesystem, so real ASDF could only run inside the compiler's own
interpreter as a plan resolver — never in the artifact. **Re-evaluate when** the identity model
gains `eq` tables, a per-library shim fix stops being cheaper, or upstream gains a portable
no-`compile-file` mode.

Reproducing the spike: fetch `asdf.lisp` from
`https://common-lisp.net/project/asdf/archives/asdf.lisp`; rename the `uiop`/`asdf` SYSTEMS
(`sed 's/UIOP/XIOP/g; ...'`, the packages are pre-seeded); rewrite the 39
`uiop/package:define-package` forms to `defpackage` (expanding `:use-reexport` transitively,
adding `:common-lisp` to every `:use`, dropping `:recycle`/`:unintern`/`:intern`); delete the
port guard and `(pushnew :unix *features*)` and make `not-implemented-error` a no-op; inject the
missing `cl` names as per-package defuns.

## The `.asd`-as-data boundary is not what stops portableaserve
**Measured against `portableaserve-20190813-git`: widening the boundary into an evaluator buys
nothing.** The first refusal is `acl-compat.asd`'s top-level `(defun lisp-system-shortname ...)`
called from a `(defmethod component-pathname ...)` routing four source files into
per-implementation subdirectories. Behind that door: dependencies that do not exist here (`puri`,
`cl-fad`, and the SBCL contribs `sb-bsd-sockets`/`sb-posix`); `acl-compat/packages.lisp` does
`#+sbcl (:use #:sb-bsd-sockets)` while without `#+sbcl` the `.asd` signals its own implementation
guard — so the two ways through are "announce `#+sbcl` and need SBCL's internals" or "announce
nothing and be refused by name"; and the files the method selects are a port of SBCL's internals
(62 `sb-thread:` references in `acl-mp.lisp` alone). **SBCL on this machine cannot load it
either** (`Component :PURI not found`), so there is not even an oracle. The reachable path is the
substitution ladder — a shim `net.aserve`/`acl-compat` surface over rontolisp's own HTTP server
(`.kb/http-server.md`, `.kb/clack.md`). **Re-evaluate when** something OTHER than a
per-implementation compatibility layer is refused by that gate.

### The _Practical Common Lisp_ book corpus, as a standing result
`practicals-1.0.3` (twelve systems over eleven chapters) diffed byte for byte against SBCL
2.2.9.debian on the interpreter. **The corpus needed no shim, no replacement `.asd` and no leaf
substitution.** Byte-identical: `spam` (23), `binary-data` (24), `id3v2` (25), `mp3-database`
(27), `html` (30/31), `macro-utilities` (8), `profiler` (32). Differ ONLY by the missing right
margin: `simple-database` (3), `test-framework` (9), `pathnames` (15). Chapters 15, 25, 27 need
`--feature sbcl`; 26/28/29 are the `net.aserve` rows above. `macro-utilities` used to diverge
because `gensym`'s default prefix was lowercase `g` (a name like `"g3"` needs `|...|` escaping
under `:case :downcase`); fixed by matching CL's uppercase `G`.
