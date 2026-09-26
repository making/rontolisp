# Source positions: `file:line:column` in reader AND frontend errors, the two literals a program can read, and where an uncaught condition happened

Five mechanisms. Positions reach ONE emitter, the JVM backend's line numbers (Phase 5); every
other output is byte-identical with and without any of this, and so is a JVM class in which
nothing was located. `am.ik.rontolisp.SourceLocation` (`file`, 1-based
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
  warnings. `locate` resolves a line through a line-start index built once per unit
  (a backend asks for every form it emits; the scan from the text's start made that
  quadratic); `locatedInFile` answers "read from a named file" without resolving one.
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
check.** Found by the JVM location lines (2026-09-26), each dropping a whole function's
positions until then: `CrossLambdaExitLowering`'s special-form arms (`block`, `return(-from)`,
the loop macros, `tagbody`, `prog`, `flet`/`labels` rebuilt every one), the `flet`/`labels`
call rewrite (`LispMacroExpander.rewriteLocalCalls`, shared with the interpreter),
`UserMacroExpander.requalifyShadowedClNames` (copied every cell of a form a macro call sat in)
and `rewriteNextMethod` (every `defmethod` body).

**Half 2 — a pass that legitimately REWRITES** transfers the position with
`SourceProvenance.inherit(original, rewritten)`; `PureBuiltinFolder` routes every rebuild
through it, the model for the next such pass. A pass can owe BOTH halves;
`PackageResolver` owes them most. Legitimately coarse: a CONSUMED top-level directive
(`in-package`, `defpackage`, `export`). A user macro's (and compiler macro's) expansion
inherits its call's position (`UserMacroExpander.expandAllLocated`), as do the
`flet`/`labels` expansion, a rewritten local call and a `call-next-method` rewrite.

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

## Phase 4 — runtime positions for the interpreter's uncaught report
The compile path's table is per-thread and opens only in `RontoLispCli.compileToFile`; a
read with no scope open is the interpreter's, and there the reader answers each datum's
OUTERMOST cons (for a named file) as `am.ik.rontolisp.LocatedCons` -- a `LispCons`
subclass carrying `file`/`line`, identical to a plain cell for everything a program can
observe. Used ONLY by the top-level uncaught report (`eval/ConditionTrace`, the location
lines of [error-handling.md](error-handling.md)); no message ever gets a prefix, so
`handler-case`, `princ` and every pinned output are unchanged.
- **Why a subclass** (measured 2026-09-26, GraalVM 25.0.3): a third field on `LispCons` is
  free on HotSpot's default layout (24 bytes either way) but grows EVERY cons 16 -> 24
  bytes in the native image and under compact headers; a weak identity table costs ~56
  bytes an entry plus a synchronized probe. The subclass costs +8 bytes per LIST read and
  a type test, which is what lets `evalCons` track the innermost located form on every
  loop step (`located`/`locatedIn` locals). fib 27 / 300k-element list build / 50k caught
  errors: within noise of the build before it.
- **Lifetime = the cons's**, so a served request's run-time `load` leaves nothing behind
  (the reason the compile table is thread-local).
- **Keeping it through rewrites**: `LispCons.rebuilt`/`rebuiltList` rebuild a located
  original as located; `SourceProvenance.inherit` with no scope open answers a located
  COPY of the rewritten top cell (callers use its return value -- all do). That is what
  carries positions through `PackageResolver`, i.e. every `in-package` file.
- **Reader labels**: the located cell is a copy, so `readExpr` never converts a `#n#`
  result (a datum read elsewhere, or `#n=`'s identity-patched placeholder) and a `#n=`
  datum is located by its own inner read.
- **Not located**: `-e`/stdin programs (no file), library source spliced from the jar,
  macro-built forms (the macro CALL is).
- **Scheme**: `SchemeReader.recorded` builds each list's head as a `LocatedCons` under the
  same condition (named file, no scope open; the reader has no datum labels). The lowering's
  rewrites reach `SourceProvenance.inherit`, so they stay located; `SchemeLowering.positioned`
  records its ANSWER (the located copy) in the reader's own offset map too, which syntax
  errors are positioned from. A `syntax-rules` expansion is positioned at its USE.
- The `evalCons` locals carry the FUNCTION the frame runs for (`frameFunction`, set only by a
  `LispLambda.sourced` lambda), not the lambda it is in: a tail call into an anonymous lambda
  keeps the caller ([error-handling.md](error-handling.md), "Which function").

## Phase 5 — the JVM backend's location lines
The compile path's table reaches the JVM emitter: `codegen/jvm/JvmSourceSites` numbers each
located form's (file, line, function) as a SITE, and every method's `LineNumberTable` maps its
instructions to site ids, which the uncaught report reads back off the stack trace
([error-handling.md](error-handling.md), "JVM -- read off the stack trace"). Only a position
with a FILE counts, so the forms located are exactly the interpreter's `LocatedCons` ones. A
class in which nothing was located is byte-identical to one compiled without a recording scope.
## Tests
`LispReaderTest` (opening-delimiter cases, `currentFileAndCurrentLineReadAsTheirOwnPosition`),
`LoadInlinerTest#readerErrorIn*`,
`LispEvaluatorTest#readerErrorInARuntimeLoadNamesTheLoadedFile`, `SourceProvenanceTest`,
`LispConsTest`, `PureBuiltinFolderTest.aFoldedFormKeepsTheSourcePositionItReplaced`,
`RontoLispCliTest` (the four `aMalformedForm*`, the two undefined-function warning cases,
`theRecordingScopeIsClosedEvenWhenTheCompileFails`,
`theInterpreterKeepsItsBareErrorText`,
`theSourcePositionLiteralsNameTheLoadedFileNotTheEntryFile`), ci-spec
`source-position-literals`; Phase 4: `LispReaderTest#aNamedFilesDatumsAreLocatedAndAStringsAreNot`,
`#locatingADatumKeepsEveryLabelReferenceToIt`, `LispConsTest#aRebuildOfALocatedConsStaysLocated`,
`RontoLispCliStreamsTest`'s `anUncaught*` cases, `SchemeReaderTest#aNamedFilesListHeadsAreLocatedAndABuffersAreNot`;
Phase 5: `cli/UncaughtReportParityTest` (every rewrite above that used to drop a function's
positions has a case), `am.ik.jvm.LineNumberTableTest`.
