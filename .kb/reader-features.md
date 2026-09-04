# Reader features (`#+`/`#-`, `*features*`, block comments, `#.`)

Almost entirely frontend (`reader` package) -- no per-backend codegen. The exception is the
RUN-TIME `*features*` variable: the reader decides its initial value, the backends hold it. The
**runtime** readers emitted into compiled output (`JvmReadRuntimeBuilder` / the WASM runtime
reader) know none of this, like backquote (`read-load-limitations.md`).

## `Features` (reader pkg)
The active READ-time set, immutable for the duration of a read. The run-time `*features*` list is a
separate ordinary special seeded from it.

- Constants `INTERPRETER`/`JVM`/`WASM` = `rontolisp` + one backend feature
  (`rontolisp-interpreter`/`rontolisp-jvm`/`rontolisp-wasm`) + `unicode`. `of(...)` for tests (no
  implicit `unicode`).
- **`unicode`** is the portable spelling (CLISP/ECL/CMUCL/LispWorks; SBCL says `sb-unicode`) of
  "characters are code points, not octets" (`.kb/characters-code-points.md`). It is a CLAIM
  LIBRARIES ACT ON: cl-postgres' `#+(or sb-unicode unicode ics openmcl-unicode-strings abcl)`
  picks `strings-utf-8` vs `strings-ascii`, and the ascii branch announces
  `client_encoding SQL_ASCII` and writes one octet per code point. It is the only `#+unicode` site
  in the loadable corpus -- re-check when a new library lands.
- `contains()` is case-insensitive and strips `:`/`#:`. `isEnabled(LispVal)` evaluates a feature
  expression (`and`/`or`/`not`, bare or keyword spelling; `LispNil` = false, preserving the `#+nil`
  comment idiom). Deliberately NO `:common-lisp`.

## Target-shape features beside the backend one
- `WASM_REACTOR` -> `rontolisp-reactor` (`--no-wasi` or `--no-gc`; `.kb/clack.md`).
- `Features.COMPONENT` -> `rontolisp-component`, widening whichever WASM set is in force under
  `--component`. NOT redundant with the reactor feature (`--component --no-wasi` carries BOTH);
  what a source usually branches on is the BOUNDARY -- `rontolisp:wasm-import` and `:bytes` are
  core-module-only, so such a program writes `#-rontolisp-component`
  (`examples/cloudflare-workers/httpbin/worker.lisp`). Added in `RontoLispCli` beside the reactor
  choice so it reaches every frontend read (shims and `.asd` component files included). Pinned by
  `RontoLispCliTest.aComponentBuildReadsTheSourceWithTheComponentFeature`.
- `Features.BODY_IMPORTS` -> `rontolisp-body-imports`, when a build really declares the reactor's
  `:bytes` body imports (a `--no-wasi` wasm-GC core module on `--host-boundary=streaming`, the
  default). First target-shape feature that follows a FLAG rather than the output shape.
  **The rule it illustrates: name the thing the source is branching on, not the targets that
  happen to lack it** -- a feature is additive (`with` cannot switch one off), so the complement
  must be spellable as `#-NAME`. Pinned by
  `RontoLispCliTest.theStreamingBoundaryReadsTheSourceWithTheBodyImportsFeature` (ONE source with
  a `#+` declaration and a `#-` fallback, compiled three ways). No Java reads the feature back, so
  only a source branching on it can pin it; `theEnvelopeBoundaryBuildsAModuleWithNoBodyImports`
  beside it pins the MODULE's imports, a different claim.
- `Features.JVM_SERVLET` -> `rontolisp-servlet`, when `-o` ends in `.war`: a servlet container owns
  the port, so `rontolisp:http-handler` registers its handler and returns
  (`.kb/http-server.md`). A feature and not an internal flag because `clack-handler-rontolisp`
  branches on features and nothing else (`.kb/clack.md`).

## Widening the set (`Features.with`, additive only)
Three callers; nothing may switch a backend feature off and claim to be that backend.

1. **Per-system**: an ASDF system declares `:rontolisp-features (...)` in defsystem -- the static
   encoding of a `.asd` that pushes onto `*features*` from an `eval-when`, which cannot reach the
   reads of the system's COMPONENT FILES on its own. Each loader widens ITS OWN base set and reads
   that system's component files with the result; a dependency keeps the outer set. The `.asd`'s
   own `(eval-when ... (pushnew :F *features*))` is read into the same place
   (`AsdfSystems.collectFeaturePushes`). It is a CROSS-FILE mechanism: the push already reaches a
   `#+` in the same `.asd` for free (`FeaturePushes`), so what this adds is carrying the
   declaration out to `:if-feature`/`(:feature ...)` clauses and component reads (`.kb/asdf.md`).
   It also reaches SHIM sources: `ShimLibraries.forms` takes the target backend's features instead
   of hardcoding INTERPRETER, so `bordeaux-threads`' `#+rontolisp-wasm nil` `*supports-threads-p*`
   is true per backend (`.kb/mutexes.md`).
2. **USER (`--feature NAME`)**: `RontoLispCli.declaredFeatures` parses the repeatable,
   comma-separated option (leading `:`/`#:` stripped, downcased, deduplicated); `CompileFrontend`
   applies it LAST (after component/reactor/body-imports), and the interpreter in
   `LispEvaluator.setDeclaredFeatures`, which also re-seeds the run-time `*features*`
   (`Environment.featureKeywordList`, shared with `createGlobal`) so `(member :F *features*)` and
   the `#+F` beside it cannot disagree. For a portable library whose `#+sbcl`/`#+clisp` chain names
   no feature of ours (with `--feature sbcl` the _PCL_ chapters 15 and 27 are byte-identical to
   SBCL). **Two boundaries**: it REFUSES `rontolisp` and any `rontolisp-*` name (those describe the
   build, decided by `-o` and the flags), and it reaches only the source the USER brought (entry
   file, everything it `load`s, every `.asd` and component under it) -- never the sources rontolisp
   ships (`BuiltinSystems.forms`, `ShimLibraries`, the prelude, the Lisp-source libraries), which
   keep reading with the backend constant. Embedders: `JvmSourceCompiler.features(...)`. Pinned by
   `RontoLispCliTest.declaredFeaturesAreParsedLikeDistsAndRefuseTheBuildsOwnNames`,
   `.aDeclaredFeatureReachesTheReadTheLoadedFileAndTheRuntimeFeaturesList`,
   `.aDeclaredFeatureReachesTheCompiledBackendsToo`.
3. A source's own feature push -- `FeaturePushes`, below.

## Threading
`LispReader.readAllFromString(input, features)` (1-arg overload = INTERPRETER). Reading happens
once at the frontend, so a compiled program's `#+` set is fixed at compile time.
`RontoLispCli.compileToFile` picks `WASM` iff the output ends `.wasm` (incl.
`--no-gc`/`--component`), else `JVM`; interpret/REPL use the default. `LoadInliner.Ctx` carries the
features (5-arg `inline` overload; short overloads default INTERPRETER); the playground
(`src/web/java`, `-Pweb`) passes JVM/WASM per compile entry point. `Environment`'s runtime
`read`/`read-from-string` use the 1-arg default; `LispEvaluator` reads through its own `features`
field, and `loadFile`, `parseAsdSource`, `parseDefsystem`, `inferPackageInferredSystems` take it
from there.

## Lexer (`LispLexer`)
New `#`-dispatch cases before `#\`/`#(`/radix: `#|...|#` (nesting, `skipBlockComment`), `#+`/`#-`
(`readFeatureConditional`), `#.` (error, or tolerant skip). The feature expression is parsed by a
mini reader over symbols/lists only (`readFeatureExpr`). A **failing** guard skips the next datum
at the RAW character level (`skipDatum`): strings/escapes, line+block comments, `#\)` char
literals, quote/backquote/unquote prefixes, `#(`/`#2A(` opens, nested `#+`/`#-` (skips feature expr
+ guarded form, like `*read-suppress*`), and `#.`. **This is the point: a skipped form may use
syntax rontolisp cannot parse.**

## `*features*` is a VARIABLE on every backend, never a read-time substitution
`LispNames.FEATURES_VAR`, in `PackageRegistry.CL_VARIABLES`, seeded with the target's feature names
as an ordinary special holding a list -- the interpreter in `Environment.createGlobal`, the compile
paths from `LispMacroExpander.injectMvSpillGlobal` (a `(defvar *features* '(:A :B ...))` emitted
only when the program mentions the name). A program pushes, `setq`s and BINDS it like any special,
and the binding is DYNAMIC on every backend -- which the interpreter needed told (`LispEvaluator`'s
`specialVars` seed, beside `*read-eval*`); the compile paths get it free from the injected `defvar`.
`RontoLispCli` hands the compilers the set it READ with (`Jvm/WasmLispCompiler.runtimeFeatures`,
defaulted per backend by `LispMacroExpander.backendFeatures` for direct-compiler callers), so a
run-time `(member :rontolisp-component *features*)` and the `#+rontolisp-component` beside it
cannot disagree.

**Trap the old design left**: read-time substitution is for CONSTANTS (`pi`,
`array-dimension-limit`, `char-code-limit`, `lambda-list-keywords`). A name a program may bind or
assign must survive to the backend as a NAME -- substituting `*features*` made every `push`/`setq`
a silent no-op and, in a binding position, died with `LispCons cannot be cast to LispSymbol` in
`FreeVarAnalyzer` (what `clack:clackup`'s `(let* ((*features* (cons :clackup *features*))) ...)`
hit). Watch for the workaround shape: `uiop-os.lisp` had renamed upstream's
`(&optional (*features* *features*))` parameter to dodge it.

## A source's own feature push IS visible to its own `#+` (`reader.FeaturePushes`)
A top-level `(pushnew :F *features*)` / `(push :F *features*)`, bare or inside an
`eval-when`/`progn`, widens the set the file is read with (the announcement idiom: fast-io,
trivial-utf-16, cl-json's float-lattice probe). **The reader announces** rather than "load goes
form at a time", because the reader is the ONE layer the interpreter and all three compile backends
share; on the compile path "read the program" cannot depend on values the program computes at run
time. `LispReader.readAll` calls `FeaturePushes.widen` first, gated on the text containing
`*features*` at all (the scan is a second, provenance-free parse -- `LispReader.readAllForScan`,
recording no positions and swallowing read errors so the real read reports them) and looping to a
fixpoint.

Two deliberate limits:
- **Only a LITERAL keyword push is seen.** A computed value
  (`(pushnew (intern name :keyword) *features*)`, trivial-utf-16's
  `(case char-code-limit (#x110000 (pushnew :utf-32 *features*)))`) stays invisible on every
  backend. A source needing it declares statically via ASDF `:rontolisp-features`, or by depending
  on a built-in system that ANNOUNCES the feature (`trivial-features` -> `:unix`, `:little-endian`,
  and the HOST: `:darwin`+`:bsd` or `:linux`, `:arm64` or `:x86-64`, on JVM-family targets only);
  one channel, three spellings (`.kb/asdf.md`). **The host names are in the ANNOUNCEMENT and not in
  the base sets**, keeping a `Features` constant the same on every machine --
  `reader-features-variable`'s `(print (length *features*))` would otherwise vary per build host.
- **Widening is WHOLE-FILE, not positional** (a `#+` above the push sees it too), because reading
  is one pass over the token stream. Re-evaluation trigger: if the reader ever becomes incremental
  (lexing one top-level form at a time), the positional rule becomes free and the literal-only
  limit does not.

Pinned by `LispReaderTest.readOwnFeaturePush*`,
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`'s
`*FeaturePushIsVisibleToTheSameSourcesConditionals` + `featuresIsAnOrdinarySpecialVariable`,
`LispEvaluatorTest.evalFeaturesIsAnOrdinarySpecialVariableAndItsOwnPushIsRead`, ci-spec
`reader-features-own-push-is-visible`.

## `array-dimension-limit`
Substituted in `readSymbol` like `most-positive-fixnum`, backend-dependent: 2147483639 (the
interpreter's `Environment` global, kept for `symbol-value`) on interpreter/JVM, `2^30 - 1` on WASM
(inside i31). A read-time literal is sound here and was not for `*features*`: it is a CL CONSTANT.

## `#.` -- three lexer modes
- **Normal**: `LispReadException` ("#. read-time evaluation is not supported"). Only the playground
  COMPILE buttons still hit it (their `frontend` pipeline has no macro-time marker-resolution pass).
- **Marker** (`LispReader.readAllWithReadEvalMarkers`, `LispLexer.ReadEvalMode.MARKER`): wraps each
  `#.` datum in a `(%read-eval datum)` cons; consumers resolve markers per top-level form just
  before it runs. Consumers: `LispEvaluator.loadFile`, `RontoLispCli.interpret`, both REPLs
  (`ReplBuffer.eval`, the playground's `evalLine` -- only a buffer textually containing `#.` pays
  for the marker read), the interpreter's RUNTIME `read`/`read-from-string`
  (`Environment.readRuntimeDatum` + the resolver `LispEvaluator` installs via
  `Environment.setReadTimeEvalResolver`; a bare `Environment` keeps the error-mode read, matching
  the compiled embedded readers), and the COMPILE PATH -- `RontoLispCli.compileToFile` +
  `LoadInliner.spliceFile` read with markers whenever the source textually contains `#.`, and
  `UserMacroExpander.expand` resolves them via `LispEvaluator.resolveReadTimeEval` against its
  macro-time evaluator (marker presence alone activates the pass, before package resolution, so a
  datum sees preceding forms' defuns/defvars).
- **Tolerant** (`LispLexer.ReadEvalMode.SKIP_UNREADABLE`; `LispReader.readAllSkippingReadEval`, used
  only by `AsdfSystems.parseAsdSource`, and `LispReader.readFirstForm`): emits the same
  `%read-eval` marker, and for a datum it cannot re-lex a `(%read-eval-unreadable "RAW TEXT")`
  marker (`LispNames.READ_EVAL_UNREADABLE`). **It prints nothing**: the lexer cannot know whether
  the position is load-bearing, and in a `.asd` most `#.` sits in metadata the parse discards, so
  the marker travels and `AsdfSystems` decides -- silent in an ignored option, hard error quoting
  the raw text where the value would pick a dependency or a source file (`.kb/asdf.md`).

**A datum in a SPLICED file sees that file's `*load-pathname*`/`*load-truename*`**, established by
the same loop from `LoadInliner`'s `%begin-file` bracket -- deferred resolution is exactly why it
must, since the bracket's own lowering is a `setq` statement that runs far too late
(`.kb/load-inliner.md`). A datum reading such a `defvar` FORCES its lazy value thunk
(`.kb/defmacro-backquote.md`) -- the SECOND forcing site: cl-ppcre's ~200
`(declare #.*standard-optimize-settings*)` sites reach the macro-time evaluator only this way, so
narrowing the forcing to the macro-expansion entry point would break it on all four backends. A
marker SPLIT into code position by a backquote template resolves through the `%read-eval` identity:
an Environment function on the interpreter, a 1-arg identity emit in `Jvm/WasmExprCompiler`.

**`*read-eval*`** (`LispNames.READ_EVAL_VAR`, in `PackageRegistry.CL_VARIABLES`): seeded `t` in
`Environment.createGlobal` and proclaimed special (the `LispEvaluator` initializer, next to
`*print-escape*`); `resolveReadTimeEval` checks the current -- dynamic-first -- value before
evaluating any marker and signals "cannot read #. while *read-eval* is nil" (CLHS), covering every
consumer including the compile path. INTERPRETER only: the compiled runtime readers signal on `#.`
unconditionally (`.kb/read-load-streams.md`). Pinned by `evalRuntimeReadSharpDotEvaluates` /
`evalReadEvalNilMakesSharpDotSignal` / `evalSharpDotGeneratedDefconstants` (LispEvaluatorTest),
`compileAndRunReadTimeEvalGeneratedDefconstants` (JVM), `readTimeEvalGeneratedDefconstants` (WASM),
ci-spec `reader-sharp-dot-generated-defconstants`.

**A marker value that is a SYMBOL in an evaluated position splices QUOTED.**
`resolveReadTimeEval` has two entries: the plain one substitutes every value RAW (the runtime
`read` family's contract, where the whole form is data); `resolveReadTimeEvalInCode` -- called by
`loadFile`, `RontoLispCli.interpret`, both REPLs and `UserMacroExpander`, i.e. every consumer whose
form is about to be EVALUATED -- wraps a non-keyword SYMBOL value in `quote`, so the value stands
for the OBJECT it renders (sxql's `(intern name #.*package*)`). **Every other value splices raw,
like CL's object splice** -- notably a CONS in code position IS code (fast-http's
`` #.`(eval-when ...) `` defconstant generator; a caller wanting list DATA spells `'#.`). Position
rules inside the walk: a marker inside a well-formed `(quote DATUM)` splices raw; a whole
`defpackage` form is data (alexandria-2 splices its re-export list as `(:export . #.(let ...))`); a
marker inside backquote construction code arrives as the reader's renamed TEMPLATE variant, which
always spliced quoted. Downstream, the public `eval` re-resolves the substituted form, which is why
`packageDesignator` (intern/find-package/...) takes a symbol designator by its MEMBER name
(`LispSymbol.memberName`; CL's symbol-name rule). Pinned by
`loadSharpDotSymbolValueInCodePositionIsTheObject` (LispEvaluatorTest) and the `#.` leg of the
`sxql-enablement-language-group` ci-spec case.

## Other syntaxes
- **`#P"..."`**: the lexer emits `Token.PathnameOpen` and `LispReader` builds the pathname VALUE
  directly -- a `LispInstance` over the fixed `LispLayout.PATHNAME` (`.kb/pathnames.md`); no
  registry needed (the layout is a constant) and the instance is self-evaluating like a folded
  `#S(...)`. Only `#P` immediately followed by `"` dispatches; anything else still reads as a
  symbol (`#PFOO`), and the `#+`-skip's generic tail skips a string directly following a
  `#`-prefix so a guarded `#P"..."` disappears whole. Pinned by `LispReaderTest`.
- **`#:foo`**: lexed as a plain symbol whose name KEEPS the `#:` prefix (not renamed -- a
  defpackage/asdf designator needs the original name; gensym freshness is out of scope).
  `PackageResolver.resolveSymbol` passes `#:`-prefixed symbols through unresolved;
  `PackageResolver.designator` and `AsdfSystems.designator/symbolName` strip the prefix.
- **`#N@(...)`** (ironclad): a vector literal whose element WIDTH is dropped --
  `#32@(#x428A2F98 ...)` reads exactly like `#(...)`. Native in `LispLexer` because a user dispatch
  macro cannot extend the Java-side reader: `set-dispatch-macro-character` is an accepted no-op
  returning `t`, `copy-readtable` a no-op returning nil, `*readtable*` a variable seeded nil
  (`LispNames.COPY_READTABLE` and friends) -- enough for the
  `(defparameter *lib-readtable* (copy-readtable nil))` header idiom to load. **Any OTHER user
  dispatch character is therefore silently not honored**: its literals hit the ordinary lexer, a
  read error unless they happen to parse.
- **`#L(...)` / `#nL(...)`** (iterate): a lambda whose arguments are named `!1`, `!2`, ...
  positionally -- `#L(list !2 !3)` reads as `#'(lambda (!1 !2 !3) (list !2 !3))`. Native for the
  same reason as `#N@(`; without it `#L!2` (iterate.lisp:773) lexes as ONE symbol named `#L!2`.
  Arity is the highest `!n` the datum mentions unless `#nL` spells it out (a smaller count is a
  read error); a datum whose first element is itself a cons other than a `lambda` call is a LIST of
  body forms (iterate's `list-of-forms?`). Upstream's gensym + `symbol-macrolet` hygiene is skipped
  and the `(declare (ignore ...))` it emits is dropped. **Inside a backquote template the lambda
  cannot be built as a datum** -- `` `(... (delete-if #L(member !1 var :test ,test) ...)) `` -- so
  `LispReader.readSharpLTemplate` reads the datum RAW once (unquote = a marker cons) to get the
  arity, rewinds, and lets the ordinary template machinery build the body; the lambda is assembled
  around it by `(list 'function (list 'lambda '(!1) BODY-CODE))`. `readRawTemplate` has its own
  case, so a `#L` inside a NESTED backquote goes through the CLtL2 path whole. Tests:
  `LispReaderTest.readSharpL*`, `LispEvaluatorTest.evalSharpLIsIteratesNumberedArgumentLambda`,
  `JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`'s
  `*SharpLAndCommaDotAndWithHashTableIterator`, ci-spec
  `sharp-l-comma-dot-and-hash-table-iterator`.
- **Symbol single-escapes** (parse-number): a backslash in a symbol token makes the NEXT character
  part of the name verbatim -- even a terminating one -- and is dropped (`LispLexer.readSymbol`),
  so `\(-pos` reads as a symbol named `(-pos`.
- **`|...|` multiple escape** (jzon): everything between the pipes -- whitespace and terminating
  characters included -- is part of the name, case verbatim; a backslash inside still escapes the
  next character. The compiled runtime readers know neither escape syntax.

## Tests
ci-spec: `reader-block-comments`, `reader-feature-conditionals`, `reader-per-backend-features`
(first use of `expectedByBackend`), `reader-features-variable`. Unit: the feature-conditional block
in `LispReaderTest`, `loadedFilesAreReadWithTheGivenFeatures` in `LoadInlinerTest`.
