# Source positions: `file:line:column` in reader AND frontend errors, and the two literals a program can read

Three mechanisms. Positions never reach an emitter: compiled output is byte-identical
with and without any of this.

`am.ik.rontolisp.SourceLocation` (`file`, 1-based `line`, 1-based `column`):
`at(file, offset, input)` counts `\n` (a lone `\r` stays on its line, right for CRLF),
`prefix()` renders it. It lives in the AST package, NOT `reader`, because
`compiler`/`codegen.*` may not import `reader`. **No file means no prefix**: `prefix()`
is `""` when `file` is null (runtime `read`/`read-from-string`, REPL), so runtime error
text stays byte-identical.

## Phase 1 -- reader errors (all backends, interpreter included)
`LispLexer` tokenizes to `List<LocatedToken>` (token + start offset); `LispReader` unpacks
offsets into a parallel `int[]` so token access is untouched and no `Integer` is boxed.
Line/column is computed lazily by `SourceLocation.at` only on error.
`LispReadException` carries the location and prefixes its own message.

- **Every reader gets its origin file**: `LoadInliner.spliceFile` (incl. ASDF /
  `ql:quickload` component files), `AsdfSystems`,
  `RontoLispCli.compileToFile`/`interpret`, and `LispEvaluator.loadFile`, which does NOT
  go through the inliner. Trap: omit that last one and a stray token names its file when
  compiled and nothing when interpreted.
- A construct that fails only once input runs out -- unterminated string, `#|` comment,
  `|...|` escape, raw skipped list, unclosed list -- reports where it OPENED, via
  `LispLexer.errAt` / `LispReader.errAtToken`. Every other error keeps the scan position.

Tests: `LispReaderTest#unterminatedConstructsPointAtTheirOpeningDelimiter`,
`#anUnclosedListPointsAtItsOpeningParen`,
`LispEvaluatorTest#readerErrorInARuntimeLoadNamesTheLoadedFile`,
`LoadInlinerTest#readerErrorIn*`.

## Phase 2 -- per-form provenance for the frontend passes (compile path only)
`am.ik.rontolisp.SourceProvenance` is an `IdentityHashMap<LispCons, (unit, offset)>` plus
the unit text, filled by `LispReader.readExpr`. Keyed by cons IDENTITY, never a field on
`LispVal` (sealed type, shared leaves). Only conses; an error about an atom reports
against the containing form.

Passes must not wrap the exception (a pass may catch its own types), so each recursive
pass does `catch (RuntimeException ex) { throw SourceProvenance.noteFailure(cons, ex); }`,
returning the SAME exception. First frame to note wins -- the innermost, since notes
happen while unwinding -- and a macro-generated cons (absent from the table) leaves the
slot to an enclosing one. A pass that only walks top-level forms calls
`enterTopLevelForm(form)` instead; that is the fallback when no frame had a hook. Hooked:
`UserMacroExpander.expandAll` + its top-level loop, `Jvm/WasmExprCompiler`'s cons
dispatch, `FreeVarAnalyzer.collectCapturedVars` AND `collectFreeVars` (it EXPANDS macros
whose raw shape it would misread -- `check-type`, `assert`, `do`, `loop` -- and is the
first pass to touch a TOP-LEVEL one).

`RontoLispCli.compileToFile` opens the recording scope and re-reports the failure as
`cli.LispCompileException`, prefix prepended, original as cause; a `LispReadException` or
a position-less failure is left alone. For a printed WARNING,
`SourceProvenance.prefix(form)` gives the same string
(`Jvm/WasmFunctionCallCompiler`'s undefined-function warning).

**A warning goes through `compiler.CompileWarnings.warn`, never `System.err`.**
`JvmLispCompiler` may compile a program TWICE (its runtime-helper gate is a prediction
checked against emitted bytecode), so a discarded attempt would print twice. An attempt
buffers (`startAttempt`), only the shipping one prints (`flushAttempt`), a retried one
drops its own (`discardAttempt`); with no attempt open (the WASM backends) `warn` prints
straight through. Pinned by
`RontoLispCliTest#anUndefinedFunctionWarnsExactlyOncePerCallSite`.

### Half 1: the cons-identity rule every AST pass must honour
**A frontend pass that changes nothing must return the object it was given.** A rebuilt
parent forces rebuilt children, so ONE gratuitous copy mid-pipeline drops the position of
the whole program below the top level. Use `LispCons.rebuilt(original, car, cdr)` or
`LispCons.rebuiltList(original, elements)`; each returns `original` when nothing changed,
and `rebuiltList` refuses a DOTTED original whose proper-list rebuild would drop the tail
(`LispConsTest`).

Made identity-preserving:
`CompileTimePathnameFolder.recurseCons`/`foldDefParam`/`foldWithOpenFile`,
`PackageResolver.resolveCons` (generic walk, `quote` branch, `resolveQuotedDatum`) and
its `resolveSymbol` (a name resolving to itself hands back the symbol as read -- no
intern table, so a copy is behaviorally identical but forces a rebuild),
`TlsPemInliner.rewrite`, `LambdaLists.desugar`, `CrossLambdaExitLowering` (`structural`,
`transformLambda`/`transformDefun`/`transformFunction`), `JsonLibrary`'s call-site walk,
`GrayStreamsLibrary.rewriteBindingForm`, `UserMacroExpander.rebuild`/`expandBindings` and
its `case`/non-symbol-head branches, `ShadowedBuiltins`, `WasmSocketsRewrite`,
`WasmArityBundler.rewriteElements`. `StructLiteralFolder` already did.

Traps: `UserMacroExpander` rebuilt the whole AST of any program with one `defmacro`,
hidden behind a `print()`-equality check that restored the ORIGINAL top-level form, so
top-level positions looked right while everything below was gone. `WasmArityBundler` /
`ShadowedBuiltins` / `WasmSocketsRewrite` run INSIDE `Jvm/WasmLispCompiler.compile`, so
probing the CLI's own pipeline shows nothing wrong.

**When you add or touch an AST pass, add the unchanged check** and test it the way
`aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice` does (one case per
pass): a malformed form deep inside a `defun` must report its own line. Legitimately
coarse: a form the frontend genuinely REWROTE.

### Half 2: a pass that legitimately REWRITES
Such a pass rebuilds every cons from the top-level form down to the rewrite, each a fresh
key. `SourceProvenance.inherit(original, rewritten)` gives the rewritten cons the
original's position (no-op when the original came back, the result is not a cons, or
nothing is recording). `PureBuiltinFolder` routes every rebuild through it -- the model
for the next rewriting pass. Pinned by
`PureBuiltinFolderTest.aFoldedFormKeepsTheSourcePositionItReplaced`.

**A pass can owe BOTH halves; `PackageResolver` owes them most**: a form where one symbol
resolves to a different name is genuinely rewritten, so it and every ancestor became
unseen conses -- every file that says `(in-package :foo)` and names anything qualified,
i.e. every quickloaded library. Every rebuild site of `resolveCons` -- generic walk,
`quote`/`defmacro`/`macrolet`/`case` branches, the `find-package` fold, the wasm/wit
directives, the introspection normalizer -- now inherits. Legitimately still coarse: a
CONSUMED top-level directive (`in-package`, `defpackage`, `export`), replaced by a
constant no later pass can fail inside. Pinned by
`RontoLispCliTest#aMalformedFormKeepsItsLineInsideAPackageQualifiedFile` (without it
there is no position, so the compile boundary does not wrap the failure and a raw
`ClassCastException` reaches the user).

## Phase 3 -- source position literals a PROGRAM can read
`rontolisp:current-file` / `rontolisp:current-line` (`LispNames.CURRENT_FILE` /
`CURRENT_LINE`) are substituted by `LispReader.sourceLiteral`, next to `pi`,
`most-positive-fixnum`, `array-dimension-limit`: a `LispString` of the origin file (`nil`
when the read has none) and a `LispInteger` of the 1-based line the SYMBOL stands on.
**In the reader, not at expansion time** -- the only place that knows each occurrence's
position, shared by the interpreter and all four backends, so no emitter sees anything
but a string and an integer.

- Inside a `defmacro` template they name the macro's DEFINITION site, so a logging macro
  takes them as ARGUMENTS at its call site.
- Only the qualified spellings (`rontolisp:`, `rontolisp::`, the `rl:` nickname) are
  recognized, since the reader runs before `in-package` is interpreted; a bare
  `current-file` must stay an ordinary symbol.
- Substitution is unconditional, quoted data included, like `#+`/`#-` and `#.`.
- A `load`ed / ASDF-spliced file names ITSELF.

Tests: `LispReaderTest#currentFileAndCurrentLineReadAsTheirOwnPosition` and its three
neighbours, `RontoLispCliTest#theSourcePositionLiteralsNameTheLoadedFileNotTheEntryFile`,
`ci-spec.yaml`'s `source-position-literals` case (all four backends; asserts TYPES and a
one-line delta, since the values are the driver's own temp file and offsets).

## The compile-path-only divergence
Recording is opt-in per thread; only `RontoLispCli.compileToFile` opts in, because (a)
the interpreter reaches the same expander at EVALUATION time and a prefix there would
land on runtime error text that `ci-spec.yaml` and the doc examples pin byte for byte;
(b) a served request may `load` at run time, so a process-wide table would grow unbounded
and race across request threads -- the state is a `ThreadLocal`.

**Trigger**: if the interpreter ever grows a separate frontend phase (expanding a whole
program before evaluating any of it), reason (a) stops holding and the divergence should
be retired. Phase 1 already covers the interpreter, so only post-read errors are affected.

Tests: `SourceProvenanceTest` (recording, innermost-wins, the macro-generated gap, the
top-level fallback, scope teardown), `LispConsTest` (the two rebuild helpers),
`RontoLispCliTest` (`aMacroThatSignalsWhileExpandingNamesItsCallSiteInTheLoadedFile`,
`aMalformedFormDeepInsideADefunNamesItsOwnLineOnBothCompileBackends`,
`aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice`,
`aMalformedFormKeepsItsLineInsideAPackageQualifiedFile`,
`aMalformedTopLevelCheckTypeNamesItsOwnLine`,
`aCallToAnUndefinedFunctionWarnsAtItsCallSite`,
`anUndefinedFunctionWarnsExactlyOncePerCallSite`,
`theRecordingScopeIsClosedEvenWhenTheCompileFails`,
`theInterpreterKeepsItsBareErrorText`).
