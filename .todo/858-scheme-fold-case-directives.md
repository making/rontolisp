# Scheme reader: `#!fold-case` / `#!no-fold-case` directives

Difficulty: Low

R7RS 2.1: `#!fold-case` makes the reader fold identifiers and character names with
`string-foldcase` until `#!no-fold-case` (or the end of the file) turns it off. Both are
comments otherwise. `SchemeReader` has no `#!` handling today, and `.todo/826`'s reader
row lists `#!fold-case`. This item replaces that entry.

Gauche 0.9.15 check (2026-09-18): `#!fold-case` then `(DISPLAY (CAR (list 1)))` prints
`1`.

- The directive applies per FILE and is independent of `--scheme-standard`
  (`.todo/857`): valid in every mode.
- Fold identifiers only (not strings), and `#\A`-style character NAMES
  (`#\NEWLINE` -> `#\newline`), not the character itself.
- The run-time `(scheme read)` reader in `scheme.lisp` must honor the directive in
  the datums it reads, the same way.
- Tests: `SchemeReaderTest` (on/off/on within one file, position of what follows),
  and one `scheme-spec.yaml` case (all four backends).
- Docs: `doc/{en,ja}/scheme/syntax.md`'s reader bullet (`.todo/859` split the old
  `guides/scheme.md` into a `scheme/` section of pages), `.kb/scheme-frontend.md`
  "Not here yet".
