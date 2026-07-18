# Reader features (`#+`/`#-`, `*features*`, block comments, `#.`)

The `.todo/054` Phase-2 read-layer additions, implemented entirely in the frontend (`reader` package) — no evaluator/compiler changes, no per-backend codegen. The **runtime** readers emitted into compiled output (`JvmReadRuntimeBuilder` / the WASM runtime reader) do NOT know any of this, like backquote (documented in `read-load-limitations.md`).

**`Features` (reader pkg)**: the active feature set. Constants `INTERPRETER`/`JVM`/`WASM` = `rontolisp` + one backend feature (`rontolisp-interpreter`/`rontolisp-jvm`/`rontolisp-wasm`); `of(...)` for tests. `contains()` is case-insensitive and strips `:`/`#:`; `isEnabled(LispVal)` evaluates a feature expression (`and`/`or`/`not`, bare or keyword spelling; `LispNil` = false, preserving the `#+nil` comment idiom). Deliberately NO `:common-lisp` (do not lie) and no `(setq *features*)`.

**Threading**: `LispReader.readAllFromString(input, features)` (1-arg overload = INTERPRETER). Reading happens once at the frontend, so a compiled program's feature set is fixed at compile time: `RontoLispCli.compileToFile` picks `WASM` iff the output file ends `.wasm` (incl. `--no-gc`/`--component`), else `JVM`; interpret/REPL use the default. `LoadInliner.Ctx` carries the features so loaded/spliced files read with the target set (5-arg `inline` overload; short overloads default INTERPRETER); the playground (`src/web/java`, `-Pweb`!) passes JVM/WASM per compile entry point. `Environment`'s runtime `read`/`read-from-string` and `LispEvaluator.loadFile` use the 1-arg default (correct: interpreter).

**Lexer (`LispLexer`)**: new `#`-dispatch cases before the `#\`/`#(`/radix cases — `#|...|#` (nesting, `skipBlockComment`), `#+`/`#-` (`readFeatureConditional`), `#.` (error, or tolerant skip). The feature expression is parsed by a mini reader over symbols/lists only (`readFeatureExpr`). A **failing** guard skips the next datum at the RAW character level (`skipDatum`): strings/escapes, line+block comments, `#\)` char literals, quote/backquote/unquote prefixes, `#(`/`#2A(` opens, nested `#+`/`#-` (skips feature expr + guarded form, like `*read-suppress*`), and `#.`. This is the point: a skipped form may use syntax rontolisp cannot parse. A **passing** guard just continues tokenizing.

**`*features*`**: substituted in `LispReader.readSymbol` like `pi` — becomes `(quote (:rontolisp :rontolisp-<backend>))`, so all backends get parity free. Bare spelling only (`cl:*features*` is not special-cased, same as `pi`). `LispNames.FEATURES_VAR`; NOT added to `PackageRegistry.CL_VARIABLES` (the resolver never sees the bare name — the reader consumed it).

**`#.`**: three lexer modes. Normal mode = clear `LispReadException` ("#. read-time evaluation is not supported"; previously it silently mis-lexed as a `#.` symbol) — today only the runtime readers (`read`/`read-from-string`) and the playground Compile buttons still hit it. Marker mode (`LispReader.readAllWithReadEvalMarkers`, `LispLexer.ReadEvalMode.MARKER`) wraps each `#.` datum in a `(%read-eval datum)` cons; consumers resolve the markers per top-level form just before it runs. Consumers: `LispEvaluator.loadFile` (global env), `RontoLispCli.interpret` (same), and the COMPILE PATH — `RontoLispCli.compileToFile` + `LoadInliner.spliceFile` read with markers whenever the source textually contains `#.`, and `UserMacroExpander.expand` resolves them via `LispEvaluator.resolveReadTimeEval` against its macro-time evaluator (marker presence alone activates the pass, before package resolution, so a datum sees the defuns/defvars of preceding forms). A marker SPLIT into code position by a backquote template resolves through the `%read-eval` identity instead: an Environment function on the interpreter, a 1-arg identity emit in `Jvm/WasmExprCompiler`. Tolerant mode (`LispReader.readAllSkippingReadEval`, used ONLY by `AsdfSystems.parseAsdSource`) skips the datum with a `System.err` warning — the `.asd` version-guard idiom.

**`#:foo`**: still lexed as a plain symbol whose name keeps the `#:` prefix (NOT renamed — a defpackage/asdf designator needs the original name, so gensym-style freshness is out of scope). `PackageResolver.resolveSymbol` passes `#:`-prefixed symbols through unresolved (like keywords/`&`-markers); `PackageResolver.designator` and `AsdfSystems.designator/symbolName` strip the prefix.

ci-spec cases: `reader-block-comments`, `reader-feature-conditionals`, `reader-per-backend-features` (first use of `expectedByBackend`), `reader-features-variable`. Unit pins: the feature-conditional block in `LispReaderTest`, `loadedFilesAreReadWithTheGivenFeatures` in `LoadInlinerTest`.

Symbol single-escapes (added for parse-number, 2026-07-05): a backslash in a
symbol token makes the NEXT character part of the name verbatim -- even a
terminating one -- and is itself dropped (`LispLexer.readSymbol`), so locals
like parse-number's `\(-pos` read as a symbol named `(-pos`. `|...|` multiple
escape (added for jzon, 2026-07-18): everything between the pipes -- whitespace
and terminating characters included -- is part of the name, case verbatim, and
a backslash inside still escapes the next character. The compiled runtime
readers know neither syntax, matching the other frontend-only reader features
above.
