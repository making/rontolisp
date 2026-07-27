# Reader features (`#+`/`#-`, `*features*`, block comments, `#.`)

The `.todo/054` Phase-2 read-layer additions, implemented entirely in the frontend (`reader` package) — no evaluator/compiler changes, no per-backend codegen. The **runtime** readers emitted into compiled output (`JvmReadRuntimeBuilder` / the WASM runtime reader) do NOT know any of this, like backquote (documented in `read-load-limitations.md`).

**`Features` (reader pkg)**: the active feature set. Constants `INTERPRETER`/`JVM`/`WASM` = `rontolisp` + one backend feature (`rontolisp-interpreter`/`rontolisp-jvm`/`rontolisp-wasm`) + `unicode`; `of(...)` for tests (no implicit `unicode` — a test asks for exactly what it lists). **`unicode` (2026-07-27)** is the portable spelling (CLISP/ECL/CMUCL/LispWorks; SBCL says `sb-unicode`) of "this implementation's characters are code points, not octets", which is true of every backend — see `.kb/characters-code-points.md`. It is a CLAIM LIBRARIES ACT ON, not decoration: cl-postgres' `#+(or sb-unicode unicode ics openmcl-unicode-strings abcl)` header picks `strings-utf-8` vs `strings-ascii`, and the ascii branch announces `client_encoding SQL_ASCII` and then writes one octet per code point, so `(exec-prepared ... (list "お茶"))` sent `0x00`-bearing garbage the server rejected AND left the connection desynced. The feature was absent until a Japanese string went through the driver; the hand-authored `cl-postgres-deps.asd` had the ascii file pinned WITH the missing feature as its stated reason, which is the shape to watch for — a workaround whose comment records the root cause instead of fixing it. Adding it was safe because it is the only `#+unicode` site in the loadable corpus (uiop's is gated behind other implementations, uax-15's is `#+utf-16`); re-check that when a new library lands. `contains()` is case-insensitive and strips `:`/`#:`; `isEnabled(LispVal)` evaluates a feature expression (`and`/`or`/`not`, bare or keyword spelling; `LispNil` = false, preserving the `#+nil` comment idiom). Deliberately NO `:common-lisp` (do not lie) and no `(setq *features*)`.

**Threading**: `LispReader.readAllFromString(input, features)` (1-arg overload = INTERPRETER). Reading happens once at the frontend, so a compiled program's feature set is fixed at compile time: `RontoLispCli.compileToFile` picks `WASM` iff the output file ends `.wasm` (incl. `--no-gc`/`--component`), else `JVM`; interpret/REPL use the default. `LoadInliner.Ctx` carries the features so loaded/spliced files read with the target set (5-arg `inline` overload; short overloads default INTERPRETER); the playground (`src/web/java`, `-Pweb`!) passes JVM/WASM per compile entry point. `Environment`'s runtime `read`/`read-from-string` and `LispEvaluator.loadFile` use the 1-arg default (correct: interpreter).

**Lexer (`LispLexer`)**: new `#`-dispatch cases before the `#\`/`#(`/radix cases — `#|...|#` (nesting, `skipBlockComment`), `#+`/`#-` (`readFeatureConditional`), `#.` (error, or tolerant skip). The feature expression is parsed by a mini reader over symbols/lists only (`readFeatureExpr`). A **failing** guard skips the next datum at the RAW character level (`skipDatum`): strings/escapes, line+block comments, `#\)` char literals, quote/backquote/unquote prefixes, `#(`/`#2A(` opens, nested `#+`/`#-` (skips feature expr + guarded form, like `*read-suppress*`), and `#.`. This is the point: a skipped form may use syntax rontolisp cannot parse. A **passing** guard just continues tokenizing.

**`*features*`**: substituted in `LispReader.readSymbol` like `pi` — becomes `(quote (:rontolisp :rontolisp-<backend> :unicode))`, so all backends get parity free. Bare spelling only (`cl:*features*` is not special-cased, same as `pi`). `LispNames.FEATURES_VAR`; NOT added to `PackageRegistry.CL_VARIABLES` (the resolver never sees the bare name — the reader consumed it).

**`array-dimension-limit`** (todo-146, jzon's `(1- array-dimension-limit)` / `(#.array-dimension-limit)` bounds): substituted in `readSymbol` like `most-positive-fixnum`, backend-dependent — 2147483639 (the interpreter's `Environment` global, kept for `symbol-value`) on interpreter/JVM, `2^30 - 1` on WASM (inside i31). Fixed at read time like `*features*`, so a program binding the name would break there (it is a CL constant; rebinding is illegal anyway).

**`#.`**: three lexer modes. Normal mode = clear `LispReadException` ("#. read-time evaluation is not supported"; previously it silently mis-lexed as a `#.` symbol) — today only the runtime readers (`read`/`read-from-string`) and the playground Compile buttons still hit it. Marker mode (`LispReader.readAllWithReadEvalMarkers`, `LispLexer.ReadEvalMode.MARKER`) wraps each `#.` datum in a `(%read-eval datum)` cons; consumers resolve the markers per top-level form just before it runs. Consumers: `LispEvaluator.loadFile` (global env), `RontoLispCli.interpret` (same), and the COMPILE PATH — `RontoLispCli.compileToFile` + `LoadInliner.spliceFile` read with markers whenever the source textually contains `#.`, and `UserMacroExpander.expand` resolves them via `LispEvaluator.resolveReadTimeEval` against its macro-time evaluator (marker presence alone activates the pass, before package resolution, so a datum sees the defuns/defvars of preceding forms). A datum reading such a `defvar` FORCES its lazy value thunk (`.kb/defmacro-backquote.md`) — this is the SECOND forcing site and the one a reader would not infer from "macros read globals": cl-ppcre's ~200 `(declare #.*standard-optimize-settings*)` sites reach the macro-time evaluator only this way, so narrowing the forcing to the macro-expansion entry point would break it on all four backends. A marker SPLIT into code position by a backquote template resolves through the `%read-eval` identity instead: an Environment function on the interpreter, a 1-arg identity emit in `Jvm/WasmExprCompiler`. Tolerant mode (`LispReader.readAllSkippingReadEval`, used ONLY by `AsdfSystems.parseAsdSource`) skips the datum with a `System.err` warning — the `.asd` version-guard idiom.

**`#:foo`**: still lexed as a plain symbol whose name keeps the `#:` prefix (NOT renamed — a defpackage/asdf designator needs the original name, so gensym-style freshness is out of scope). `PackageResolver.resolveSymbol` passes `#:`-prefixed symbols through unresolved (like keywords/`&`-markers); `PackageResolver.designator` and `AsdfSystems.designator/symbolName` strip the prefix.

ci-spec cases: `reader-block-comments`, `reader-feature-conditionals`, `reader-per-backend-features` (first use of `expectedByBackend`), `reader-features-variable`. Unit pins: the feature-conditional block in `LispReaderTest`, `loadedFilesAreReadWithTheGivenFeatures` in `LoadInlinerTest`.

`#N@(...)` (added for ironclad, 2026-07-25): a vector literal whose element
WIDTH is dropped — `#32@(#x428A2F98 ...)` reads exactly like `#(...)`. In
ironclad this syntax comes from a `set-dispatch-macro-character` handler
returning a `(make-array n :element-type '(unsigned-byte N) :initial-contents
'(...))` form, and rontolisp arrays are generic, so the literal vector IS that
form's value. Native in `LispLexer` because a user dispatch macro cannot extend
the Java-side reader: `set-dispatch-macro-character` is an accepted no-op
returning `t`, `copy-readtable` a no-op returning nil, and `*readtable*` a
variable seeded nil (`LispNames.COPY_READTABLE` and friends) — enough for the
`(defparameter *lib-readtable* (copy-readtable nil))` header idiom to load. Any
OTHER user dispatch character is therefore silently not honored: its literals hit
the ordinary lexer, which is a read error unless they happen to parse.

Symbol single-escapes (added for parse-number, 2026-07-05): a backslash in a
symbol token makes the NEXT character part of the name verbatim -- even a
terminating one -- and is itself dropped (`LispLexer.readSymbol`), so locals
like parse-number's `\(-pos` read as a symbol named `(-pos`. `|...|` multiple
escape (added for jzon, 2026-07-18): everything between the pipes -- whitespace
and terminating characters included -- is part of the name, case verbatim, and
a backslash inside still escapes the next character. The compiled runtime
readers know neither syntax, matching the other frontend-only reader features
above.
