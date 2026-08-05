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

Follow-ups this left behind (small, independent):

- The passes a given program actually triggers (library splices, the defun
  pruner, the `--component` rewrites) still rebuild unconditionally, so a
  position inside such a program degrades to the enclosing recorded form.
  Add the unchanged check as each is touched.
- `Jvm/WasmFunctionCallCompiler` prints the undefined-function warning
  TWICE for one call site (two compiler passes reach it). Pre-existing,
  more visible now that the line carries a position.
- `LispMacroExpander`'s own `check-type`/`assert` expansion errors are
  covered only through the enclosing pass hooks; a direct hook would make
  them exact.

## Phase 3 — optional, only if a use case appears

Expansion-time literals `rontolisp:current-file` / `rontolisp:current-line`
resolved in the shared frontend (like `#+`/`#-` against `reader.Features`),
usable in user error messages and logging macros. Avoid a `#`-dispatch
syntax: `#f` already means single-float arrays. Namespaced symbols satisfy
the no-new-CL-surface rule.

## Verification

- Phase 1: tests asserting line/column on representative reader errors,
  including one inside a `load`ed file and one inside a `ql:quickload`ed
  system (origin file named).
- Phase 2: a macro-expansion error inside a spliced library names
  `file:line`; ci-spec output unchanged (frontend-only). Done —
  `SourceProvenanceTest` + the five `RontoLispCliTest` cases listed in
  `.kb/source-positions.md`.
