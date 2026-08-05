# Reader source positions: line/column in errors, then per-form provenance

`LispLexer` counts no lines and `LispReader` tracks no positions: every
`LispReadException` is position-free, the sealed `LispVal` AST carries no
location, and no frontend pass can name a file or line in its errors. With
ASDF/Quicklisp loading splicing many multi-file libraries through
`LoadInliner`, a stray paren or a bad token in a 5000-line spliced program
currently produces an error with no indication of WHERE — this is the
single biggest debugging bottleneck when bringing up a new community
library.

## Phase 1 — positions in reader errors (small, standalone win) — DONE 2026-08-04

Add line/column counters to `LispLexer`; include position in every
`LispReadException` (and reader-level errors like unterminated strings /
unknown `#` dispatch). When the source came through `LoadInliner` / the
ASDF splice, prefix the origin file (the loader knows it via
`SourceLoader`). Zero backend or AST changes; improves every parse error
immediately.

Implemented via `reader.SourceLocation` (line/column computed lazily on
error, never stored on the AST) + `reader.LocatedToken`; `LispLexer`
tokenizes to `List<LocatedToken>`; `LispReader` unpacks offsets into a
parallel list so existing token access is untouched. The prefix is
`file:line:column: ` only when an origin file is known; a read without one
(a runtime `read`/`read-from-string` of a string, a REPL buffer) keeps its
bare message so runtime error text is byte-identical. `LoadInliner.spliceFile`
/ `AsdfSystems` / `RontoLispCli` pass the origin file through, and so does the
interpreter's own runtime `load` (`LispEvaluator.loadFile`), which does not go
through the inliner — otherwise the same error names its file when compiled and
nothing when interpreted. An error that is only detected at end of input (an
unterminated string / block comment / `|...|` escape, an unclosed list) reports
its OPENING delimiter, not EOF. Verified by `LispReaderTest` (lexer, later-line,
opening-delimiter and unclosed-list errors), `LispEvaluatorTest`
(`readerErrorInARuntimeLoadNamesTheLoadedFile`) and `LoadInlinerTest` (errors
inside a `load`ed file and a `ql:quickload`ed system name the origin file).
See `.kb/load-inliner.md`.

## Phase 2 — per-form provenance for frontend passes — DONE 2026-08-05

A side table `IdentityHashMap<LispCons, (unit, offset)>` populated by the
reader — no field on the sealed `LispVal`. Implemented as
`am.ik.rontolisp.SourceProvenance` (`SourceLocation` moved down to the same
package so `compiler`/`codegen.*` can use it without importing `reader`).
Recording is opt-in per thread and only `RontoLispCli.compileToFile` opts
in; the interpreter keeps its byte-identical runtime error text. A failure
picks up the nearest enclosing recorded cons via
`SourceProvenance.noteFailure(cons, ex)`, which returns the SAME exception
(a pass may catch its own types to fall back), and the compile boundary
re-reports it as `cli.LispCompileException` with the prefix. Warnings use
`SourceProvenance.prefix(form)`.

The unplanned half — and the reason this took a pipeline audit rather than
a reader change — is that the frontend REBUILT the AST: half a dozen passes
copied every cons whether or not they changed anything, which erased the
positions of everything below the top level. Made identity-preserving:
`CompileTimePathnameFolder`, `PackageResolver` (incl. `resolveSymbol`),
`TlsPemInliner`, `LambdaLists.desugar`, `CrossLambdaExitLowering`. That is
now a standing rule for any AST pass — see `.kb/source-positions.md`.

The three follow-ups it left behind are DONE 2026-08-05:

- The remaining unconditional rebuilds are gone, and the check now goes
  through two shared helpers (`LispCons.rebuilt` / `rebuiltList`) instead of
  being spelled out per pass. The real offenders were not the ones listed
  here: `UserMacroExpander` rebuilt the whole AST of any program containing a
  single `defmacro` (masked by a `print()`-equality check that restored the
  original TOP-LEVEL form), and three passes that run inside
  `Jvm/WasmLispCompiler.compile` -- `ShadowedBuiltins`, `WasmSocketsRewrite`,
  `WasmArityBundler` -- which a probe of the CLI pipeline alone cannot see.
  Also `JsonLibrary`, `GrayStreamsLibrary.rewriteBindingForm` and
  `CrossLambdaExitLowering`'s lambda/defun/function branches. Verified over 20
  trigger programs (every library splice, both compile backends, `--component`,
  `--dynamic`, `--no-prune`); the pinning test is
  `aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice`.
- The double undefined-function warning was the JVM backend's gate-retry loop
  re-running the whole compile after the first attempt had already printed.
  Warnings now go through `compiler.CompileWarnings`: an attempt buffers, only
  the attempt that ships prints. The WASM backends never retry and print
  straight through, so their output is unchanged.
- The `check-type`/`assert` expansion errors were already exact everywhere
  except at TOP LEVEL, where `FreeVarAnalyzer.collectFreeVars` -- which expands
  them itself -- reached them before any hooked pass. That walk now has the
  same hook its `collectCapturedVars` twin had.

## Phase 3 — the literals a program can read — DONE 2026-08-05

`rontolisp:current-file` / `rontolisp:current-line`, substituted by
`LispReader` beside `pi` and `*features*`. READ-time, not expansion-time as
this item first sketched: the reader is the only place that knows where each
occurrence stands AND is shared by the interpreter and all four backends, so
they agree by construction and no emitter is touched. Resolving during macro
expansion instead would have required the interpreter to record provenance —
the divergence phase 2 deliberately kept — and would still answer only
approximately for macro-generated forms.

The price is the `__FILE__` / `__LINE__` one: inside a `defmacro` template the
literals name the macro's own definition site, so a logging macro takes them as
arguments at its call site (documented, with the pattern). Only the qualified
spellings are recognized, since the reader runs before `in-package` is
interpreted. A `load`ed / ASDF-spliced file names itself, which is what makes
them worth having. Mechanics: `.kb/source-positions.md`.

## Verification

- Phase 1: tests asserting line/column on representative reader errors,
  including one inside a `load`ed file and one inside a `ql:quickload`ed
  system (origin file named).
- Phase 2: a macro-expansion error inside a spliced library names
  `file:line`; ci-spec output unchanged (frontend-only). Done —
  `SourceProvenanceTest`, `LispConsTest` and the seven `RontoLispCliTest`
  cases listed in `.kb/source-positions.md`.
- Phase 3: the two literals read the same on the interpreter and all four
  backends, and a `load`ed file names itself. Done — four `LispReaderTest`
  cases, `RontoLispCliTest#theSourcePositionLiteralsNameTheLoadedFileNotTheEntryFile`,
  and `ci-spec.yaml`'s `source-position-literals` case.

Every phase of this item is complete; the file can be dropped.
