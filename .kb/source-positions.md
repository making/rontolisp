# Source positions: `file:line:column` in reader AND frontend errors, and the two literals a program can read

Three mechanisms. Positions never reach an emitter: compiled output is byte-identical
with and without any of this. `am.ik.rontolisp.SourceLocation` (`file`, 1-based
`line`/`column`; `at`, `prefix`) lives in the AST package, NOT `reader`, because
`compiler`/`codegen.*` may not import `reader`. **No file means no prefix** — `""` when
`file` is null (runtime `read`, REPL), so runtime error text stays byte-identical.

## Phase 1 — reader errors (all backends, interpreter included)
`LispLexer` -> `List<LocatedToken>`; `LispReader` unpacks offsets into a parallel `int[]`,
computing line/column lazily on error; `LispReadException` prefixes its own message.
- **Every reader gets its origin file**: `LoadInliner.spliceFile`, `AsdfSystems`,
  `RontoLispCli.compileToFile`/`interpret`, and `LispEvaluator.loadFile`, which does NOT
  go through the inliner. Trap: omit that last one and a stray token names its file when
  compiled and nothing when interpreted.
- A construct that fails only once input runs out (unterminated string, `#|`, `|...|`,
  unclosed list) reports where it OPENED (`LispLexer.errAt` / `LispReader.errAtToken`).

## Phase 2 — per-form provenance for the frontend passes (compile path only)
`SourceProvenance`: an `IdentityHashMap<LispCons, (unit, offset)>` filled by
`LispReader.readExpr`, keyed by cons IDENTITY. Only conses; an error about an atom reports
against the containing form.
- Passes must not wrap the exception: each recursive pass does `catch (RuntimeException
  ex) { throw SourceProvenance.noteFailure(cons, ex); }`, returning the SAME exception;
  innermost wins. A top-level-only pass calls `enterTopLevelForm(form)`, the fallback when
  nothing hooked. Hooked: `UserMacroExpander.expandAll`, `Jvm/WasmExprCompiler`'s cons
  dispatch, `FreeVarAnalyzer.collectCapturedVars` AND `collectFreeVars`.
- `RontoLispCli.compileToFile` opens the recording scope and re-reports as
  `cli.LispCompileException`; `SourceProvenance.prefix(form)` gives the same string for
  warnings.
- **A warning goes through `compiler.CompileWarnings.warn`, never `System.err`** —
  `JvmLispCompiler` may compile a program TWICE, so an attempt buffers (`startAttempt`),
  only the shipping one prints (`flushAttempt`), a retry drops its own
  (`discardAttempt`); with no attempt open (WASM) `warn` prints straight through.

**Half 1 — the cons-identity rule every AST pass must honour.** A pass that changes
nothing must return the object it was given: a rebuilt parent forces rebuilt children, so
ONE gratuitous copy drops the position of the whole program below the top level. Use
`LispCons.rebuilt` / `LispCons.rebuiltList` (return `original` when nothing changed;
`rebuiltList` refuses a DOTTED original). Made identity-preserving:
`CompileTimePathnameFolder`, `PackageResolver`, `TlsPemInliner`, `LambdaLists.desugar`,
`CrossLambdaExitLowering`, `JsonLibrary`, `GrayStreamsLibrary`, `UserMacroExpander`,
`ShadowedBuiltins`, `WasmSocketsRewrite`, `WasmArityBundler`. Traps: `UserMacroExpander`'s
`print()`-equality check restored the ORIGINAL top-level form, so top-level positions
looked right while everything below was gone; `WasmArityBundler` / `ShadowedBuiltins` /
`WasmSocketsRewrite` run INSIDE `Jvm/WasmLispCompiler.compile`, so probing the CLI's own
pipeline shows nothing wrong. **Adding or touching an AST pass means adding the unchanged
check.**

**Half 2 — a pass that legitimately REWRITES** transfers the position with
`SourceProvenance.inherit(original, rewritten)`; `PureBuiltinFolder` routes every rebuild
through it, the model for the next such pass. A pass can owe BOTH halves;
`PackageResolver` owes them most. Legitimately coarse: a CONSUMED top-level directive
(`in-package`, `defpackage`, `export`).

## Phase 3 — source position literals a PROGRAM can read
`rontolisp:current-file` / `rontolisp:current-line` (`LispNames.CURRENT_FILE` /
`CURRENT_LINE`), substituted by `LispReader.sourceLiteral` beside `pi` and
`most-positive-fixnum`: a `LispString` of the origin file (`nil` when the read has none)
and a `LispInteger` of the 1-based line the SYMBOL stands on. **In the reader, not at
expansion time** — the only place that knows each occurrence's position, so no emitter
sees anything but a string and an integer.
- In a `defmacro` template they name the macro's DEFINITION site, so a logging macro takes
  them as ARGUMENTS at its call site.
- Only the qualified spellings (`rontolisp:`, `rontolisp::`, `rl:`) are recognized: the
  reader runs before `in-package` is interpreted. Substitution is unconditional, quoted
  data included. A `load`ed / ASDF-spliced file names ITSELF.

## The compile-path-only divergence
Recording is opt-in per thread (a `ThreadLocal`); only `RontoLispCli.compileToFile` opts
in, because (a) a prefix on the interpreter's path would change runtime error text pinned
byte for byte by `ci-spec.yaml` and the doc examples; (b) a served request may `load` at
run time, so a process-wide table would grow unbounded and race. **Trigger**: if the
interpreter grows a separate frontend phase, (a) stops holding and this should be retired.

## Tests
`LispReaderTest` (opening-delimiter cases, `currentFileAndCurrentLineReadAsTheirOwnPosition`),
`LoadInlinerTest#readerErrorIn*`,
`LispEvaluatorTest#readerErrorInARuntimeLoadNamesTheLoadedFile`, `SourceProvenanceTest`,
`LispConsTest`, `PureBuiltinFolderTest.aFoldedFormKeepsTheSourcePositionItReplaced`,
`RontoLispCliTest` (the four `aMalformedForm*`, the two undefined-function warning cases,
`theRecordingScopeIsClosedEvenWhenTheCompileFails`,
`theInterpreterKeepsItsBareErrorText`,
`theSourcePositionLiteralsNameTheLoadedFileNotTheEntryFile`), ci-spec
`source-position-literals`.
