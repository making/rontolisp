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
/ `AsdfSystems` / `RontoLispCli` pass the origin file through. Verified by
`LispReaderTest` (lexer + later-line errors) and `LoadInlinerTest` (errors
inside a `load`ed file and a `ql:quickload`ed system name the origin file).
See `.kb/load-inliner.md`.

## Phase 2 — per-form provenance for frontend passes (follow-up)

A side table `IdentityHashMap<LispCons, SourceLocation>` populated by the
reader — do NOT add a field to the sealed `LispVal` (leaf values are
shared/interned; cons identity survives because backquote is read-time but
the cells it builds are fresh per read). `LoadInliner` and the ASDF splice
stamp each spliced top-level form with its origin file. Frontend passes
that today throw bare messages (macro-expansion errors, the
`check-type`/`assert` expansions in `LispMacroExpander`, unknown-symbol
compile errors in `Jvm/WasmExprCompiler`) can then look up the nearest
enclosing located cons and prefix `file:line`. Positions stay
frontend-only: compiled output is byte-identical.

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
  `file:line`; ci-spec output unchanged (frontend-only).
