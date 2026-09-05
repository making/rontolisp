# Reader features (`#+`/`#-`, `*features*`, block comments, `#.`)

Almost entirely frontend (`reader` package) -- no per-backend codegen. The exception is the
RUN-TIME `*features*` variable: the reader decides its initial value, the backends hold it. The
**runtime** readers emitted into compiled output know none of this, like backquote
([[read-load-limitations]]).

## `Features` (reader pkg)
The active READ-time set, immutable for the duration of a read; the run-time `*features*` list is
a separate ordinary special seeded from it.
- Constants `INTERPRETER`/`JVM`/`WASM` = `rontolisp` + one backend feature
  (`rontolisp-interpreter`/`-jvm`/`-wasm`) + `unicode`. `of(...)` for tests (no implicit `unicode`).
- **`unicode`** is the portable spelling of "characters are code points, not octets"
  ([[characters-code-points]]) and a CLAIM LIBRARIES ACT ON: cl-postgres'
  `#+(or sb-unicode unicode ...)` picks `strings-utf-8` vs `strings-ascii`, whose branch announces
  `client_encoding SQL_ASCII`. It is the only `#+unicode` site in the loadable corpus.
- `contains()` is case-insensitive and strips `:`/`#:`. `isEnabled(LispVal)` evaluates a feature
  expression (`and`/`or`/`not`; `LispNil` = false, preserving the `#+nil` comment idiom).
  Deliberately NO `:common-lisp`.

## Target-shape features beside the backend one
- `WASM_REACTOR` -> `rontolisp-reactor` (`--no-wasi` or `--no-gc`; [[clack]]).
- `Features.COMPONENT` -> `rontolisp-component` under `--component`; NOT redundant with the
  reactor feature (`--component --no-wasi` carries BOTH). Added in `RontoLispCli` beside the
  reactor choice so it reaches every frontend read (shims and `.asd` component files included).
- `Features.BODY_IMPORTS` -> `rontolisp-body-imports`, when a build declares the reactor's
  `:bytes` body imports. First target-shape feature that follows a FLAG rather than the output
  shape. **The rule it illustrates: name the thing the source is branching on, not the targets
  that happen to lack it** -- a feature is additive (`with` cannot switch one off), so the
  complement must be spellable as `#-NAME`. No Java reads it back, so only a source branching on
  it can pin it.
- `Features.JVM_SERVLET` -> `rontolisp-servlet` when `-o` ends in `.war`: a servlet container owns
  the port ([[http-server]]). A feature and not an internal flag because
  `clack-handler-rontolisp` branches on features and nothing else ([[clack]]).
- Pinned by `RontoLispCliTest.aComponentBuildReadsTheSourceWithTheComponentFeature`,
  `.theStreamingBoundaryReadsTheSourceWithTheBodyImportsFeature` (ONE source with a `#+`
  declaration and a `#-` fallback, compiled three ways), and
  `.theEnvelopeBoundaryBuildsAModuleWithNoBodyImports` beside it (a different claim: the MODULE's
  imports).

## Widening the set (`Features.with`, additive only)
Three callers; nothing may switch a backend feature off and claim to be that backend.
1. **Per-system**: an ASDF system's `:rontolisp-features (...)` in defsystem -- the static encoding
   of a `.asd` that pushes onto `*features*` from an `eval-when`, which cannot reach the reads of
   the system's COMPONENT FILES on its own. Each loader widens ITS OWN base set; a dependency keeps
   the outer set. The `.asd`'s own push is read into the same place
   (`AsdfSystems.collectFeaturePushes`). What it adds over `FeaturePushes` is CROSS-FILE reach:
   `:if-feature`/`(:feature ...)` clauses and component reads ([[asdf]]). It also reaches SHIM
   sources -- `ShimLibraries.forms` takes the target backend's features instead of hardcoding
   INTERPRETER ([[mutexes]]).
2. **USER (`--feature NAME`)**: `RontoLispCli.declaredFeatures` parses the repeatable,
   comma-separated option; `CompileFrontend` applies it LAST, the interpreter in
   `LispEvaluator.setDeclaredFeatures`, which also re-seeds the run-time `*features*`
   (`Environment.featureKeywordList`) so `(member :F *features*)` and the `#+F` beside it cannot
   disagree. **Two boundaries**: it REFUSES `rontolisp` and any `rontolisp-*` name (those describe
   the build), and it reaches only the source the USER brought -- never the sources rontolisp ships
   (`BuiltinSystems.forms`, `ShimLibraries`, the prelude, the Lisp-source libraries). Embedders:
   `JvmSourceCompiler.features(...)`. Pinned by
   `RontoLispCliTest.declaredFeaturesAreParsedLikeDistsAndRefuseTheBuildsOwnNames`,
   `.aDeclaredFeatureReachesTheReadTheLoadedFileAndTheRuntimeFeaturesList`,
   `.aDeclaredFeatureReachesTheCompiledBackendsToo`.
3. A source's own feature push -- `FeaturePushes`, below.

## Threading
`LispReader.readAllFromString(input, features)` (1-arg overload = INTERPRETER). Reading happens
once at the frontend, so a compiled program's `#+` set is fixed at compile time.
`RontoLispCli.compileToFile` picks `WASM` iff the output ends `.wasm`, else `JVM`; interpret/REPL
use the default. `LoadInliner.Ctx` carries the features; the playground (`src/web/java`) passes
JVM/WASM per compile entry point. `LispEvaluator` reads through its own `features` field, from
which `loadFile`, `parseAsdSource`, `parseDefsystem`, `inferPackageInferredSystems` take it.

## Lexer (`LispLexer`)
New `#`-dispatch cases before `#\`/`#(`/radix: `#|...|#` (nesting, `skipBlockComment`), `#+`/`#-`
(`readFeatureConditional`, feature expression via `readFeatureExpr`), `#.`. A **failing** guard
skips the next datum at the RAW character level (`skipDatum`): strings/escapes, comments, `#\)`
char literals, quote prefixes, `#(`/`#2A(` opens, nested `#+`/`#-`, `#.`. **This is the point: a
skipped form may use syntax rontolisp cannot parse.**

## `*features*` is a VARIABLE on every backend, never a read-time substitution
`LispNames.FEATURES_VAR`, in `PackageRegistry.CL_VARIABLES`, seeded with the target's feature
names as an ordinary special: the interpreter in `Environment.createGlobal`, the compile paths
from `LispMacroExpander.injectMvSpillGlobal` (a `(defvar *features* '(...))` emitted only when the
program mentions the name). The binding is DYNAMIC on every backend -- which the interpreter
needed told (`LispEvaluator`'s `specialVars` seed). `RontoLispCli` hands the compilers the set it
READ with (`Jvm`/`WasmLispCompiler.runtimeFeatures`, defaulted by
`LispMacroExpander.backendFeatures`).

**Trap the old design left**: read-time substitution is for CONSTANTS (`pi`,
`array-dimension-limit`, `char-code-limit`, `lambda-list-keywords`). A name a program may bind or
assign must survive to the backend as a NAME -- substituting `*features*` made every `push`/`setq`
a silent no-op and, in a binding position, died with `LispCons cannot be cast to LispSymbol` in
`FreeVarAnalyzer`. Watch for the workaround shape: `uiop-os.lisp` had renamed upstream's
`(&optional (*features* *features*))` parameter to dodge it.

`array-dimension-limit` IS substituted in `readSymbol` like `most-positive-fixnum`,
backend-dependent: 2147483639 on interpreter/JVM, `2^30 - 1` on WASM (inside i31).

## A source's own feature push IS visible to its own `#+` (`reader.FeaturePushes`)
A top-level `(pushnew :F *features*)` / `(push ...)`, bare or inside an `eval-when`/`progn`,
widens the set the file is read with. **The reader announces** rather than "load goes form at a
time", because the reader is the ONE layer the interpreter and all three compile backends share.
`LispReader.readAll` calls `FeaturePushes.widen` first, gated on the text containing `*features*`
at all (the scan is a second, provenance-free parse, `LispReader.readAllForScan`) and looping to a
fixpoint. Two deliberate limits:
- **Only a LITERAL keyword push is seen.** A computed value stays invisible on every backend; such
  a source declares statically via ASDF `:rontolisp-features`, or depends on a built-in system
  that ANNOUNCES the feature (`trivial-features` -> `:unix`, `:little-endian`, and the HOST
  `:darwin`+`:bsd` / `:linux`, `:arm64` / `:x86-64`, on JVM-family targets only) -- one channel,
  three spellings ([[asdf]]). **The host names are in the ANNOUNCEMENT and not in the base sets**,
  keeping a `Features` constant the same on every machine.
- **Widening is WHOLE-FILE, not positional** (a `#+` above the push sees it too), because reading
  is one pass. If the reader ever becomes incremental the positional rule becomes free; the
  literal-only limit does not.

Pinned by `LispReaderTest.readOwnFeaturePush*`,
`JvmLispCompilerTest`/`WasmLispCompilerIntegrationTest`'s
`*FeaturePushIsVisibleToTheSameSourcesConditionals` + `featuresIsAnOrdinarySpecialVariable`,
`LispEvaluatorTest.evalFeaturesIsAnOrdinarySpecialVariableAndItsOwnPushIsRead`, ci-spec
`reader-features-own-push-is-visible`.

## `#.` -- three lexer modes
- **Normal**: `LispReadException`. Only the playground COMPILE buttons still hit it.
- **Marker** (`LispReader.readAllWithReadEvalMarkers`, `LispLexer.ReadEvalMode.MARKER`): wraps each
  `#.` datum in a `(%read-eval datum)` cons, resolved per top-level form just before it runs.
  Consumers: `LispEvaluator.loadFile`, `RontoLispCli.interpret`, both REPLs, the interpreter's
  runtime `read` family (`Environment.readRuntimeDatum` + `setReadTimeEvalResolver`; a bare
  `Environment` keeps the error-mode read, matching the compiled embedded readers), and the
  COMPILE PATH (`compileToFile` + `LoadInliner.spliceFile` read with markers whenever the source
  textually contains `#.`; `UserMacroExpander.expand` resolves them before package resolution, so
  a datum sees preceding forms' defuns/defvars).
- **Tolerant** (`ReadEvalMode.SKIP_UNREADABLE`; `readAllSkippingReadEval`, used only by
  `AsdfSystems.parseAsdSource`, and `readFirstForm`): same marker plus
  `(%read-eval-unreadable "RAW TEXT")` (`LispNames.READ_EVAL_UNREADABLE`) for a datum it cannot
  re-lex. **It prints nothing** -- the marker travels and `AsdfSystems` decides: silent in an
  ignored option, hard error where the value would pick a dependency or a source file ([[asdf]]).

Traps:
- A datum in a SPLICED file sees that file's `*load-pathname*`/`*load-truename*` from
  `LoadInliner`'s `%begin-file` bracket -- deferred resolution is why it must ([[load-inliner]]).
  Such a read FORCES a `defvar`'s lazy value thunk ([[defmacro-backquote]]) -- the SECOND forcing
  site, which cl-ppcre's ~200 `(declare #.*standard-optimize-settings*)` sites reach only this way.
- A marker split into code position by a backquote template resolves through the `%read-eval`
  identity (an Environment function; a 1-arg identity emit in the compilers).
- `*read-eval*` (`LispNames.READ_EVAL_VAR`, `CL_VARIABLES`) is seeded `t`, proclaimed special, and
  checked dynamically by `resolveReadTimeEval` before any marker evaluates. INTERPRETER only --
  the compiled runtime readers signal on `#.` unconditionally ([[read-load-streams]]).
- **A marker value that is a SYMBOL in an evaluated position splices QUOTED**
  (`resolveReadTimeEvalInCode`); plain `resolveReadTimeEval` substitutes RAW, the runtime `read`
  family's contract. **Every other value splices raw, like CL's object splice** -- a CONS in code
  position IS code; a caller wanting list DATA spells `'#.`. A marker inside a well-formed
  `(quote DATUM)` splices raw, a whole `defpackage` form is data, and one inside backquote
  construction arrives as the reader's renamed TEMPLATE variant, which always spliced quoted.
  Downstream `eval` re-resolves, which is why `packageDesignator` takes a designator by its MEMBER
  name (`LispSymbol.memberName`).
- Pinned by `LispEvaluatorTest.evalRuntimeReadSharpDotEvaluates`,
  `.evalReadEvalNilMakesSharpDotSignal`, `.evalSharpDotGeneratedDefconstants`,
  `.loadSharpDotSymbolValueInCodePositionIsTheObject`;
  `JvmLispCompilerTest.compileAndRunReadTimeEvalGeneratedDefconstants`;
  `WasmLispCompilerIntegrationTest.readTimeEvalGeneratedDefconstants`; ci-spec
  `reader-sharp-dot-generated-defconstants` and the `#.` leg of `sxql-enablement-language-group`.

## Other syntaxes
- **`#P"..."`**: `Token.PathnameOpen`; `LispReader` builds a `LispInstance` over the fixed
  `LispLayout.PATHNAME` ([[pathnames]]), self-evaluating. Only `#P` immediately followed by `"`
  dispatches (`#PFOO` is a symbol).
- **`#:foo`**: a plain symbol whose name KEEPS the `#:` prefix (an asdf/defpackage designator needs
  the original name); `PackageResolver.resolveSymbol` passes it through, `designator` and
  `AsdfSystems.designator`/`symbolName` strip it.
- **`#N@(...)`** (ironclad) reads like `#(...)`, element width dropped. Native in `LispLexer`
  because a user dispatch macro cannot extend the Java-side reader --
  `set-dispatch-macro-character` is an accepted no-op returning `t`, `copy-readtable` a no-op
  returning nil, `*readtable*` a variable seeded nil. **Any OTHER user dispatch character is
  therefore silently not honored.**
- **`#L(...)` / `#nL(...)`** (iterate): a lambda with positional `!1`, `!2`, ... arguments; native
  for the same reason (without it `#L!2` lexes as ONE symbol). Arity is the highest `!n` mentioned
  unless `#nL` spells it out (a smaller count is a read error); a datum whose first element is a
  cons other than a `lambda` call is a LIST of body forms. **Inside a backquote template the lambda
  cannot be built as a datum**, so `LispReader.readSharpLTemplate` reads the datum RAW once for the
  arity, rewinds, lets the template machinery build the body, and assembles
  `(list 'function (list 'lambda '(!1) BODY-CODE))` around it; `readRawTemplate` has its own case
  so a `#L` in a NESTED backquote goes through the CLtL2 path whole. Tests
  `LispReaderTest.readSharpL*`, `LispEvaluatorTest.evalSharpLIsIteratesNumberedArgumentLambda`,
  `*SharpLAndCommaDotAndWithHashTableIterator`, ci-spec `sharp-l-comma-dot-and-hash-table-iterator`.
- **Symbol single-escapes** (parse-number) and **`|...|`** (jzon) keep the next character / the
  whole run verbatim in the name. The compiled runtime readers know neither.

## Tests
ci-spec `reader-block-comments`, `reader-feature-conditionals`, `reader-per-backend-features`
(first use of `expectedByBackend`), `reader-features-variable`. Unit: the feature-conditional
block in `LispReaderTest`, `LoadInlinerTest.loadedFilesAreReadWithTheGivenFeatures`.
