# `(scheme char)` for the Scheme front end

Difficulty: Medium

Split off from `.todo/826` (the `(scheme char)/(scheme cxr)/...` row; cxr, inexact and
lazy are done). Oracle: Gauche 0.9.15, `(module-exports (find-module 'scheme.char))` --
22 names, none in `SchemeBuiltins` today:

`char-alphabetic?` `char-numeric?` `char-whitespace?` `char-upper-case?`
`char-lower-case?` `digit-value` `char-upcase` `char-downcase` `char-foldcase`
`char-ci=?` `char-ci<?` `char-ci>?` `char-ci<=?` `char-ci>=?` `string-ci=?`
`string-ci<?` `string-ci>?` `string-ci<=?` `string-ci>=?` `string-upcase`
`string-downcase` `string-foldcase`

## Scope

- Table rows in `SchemeBuiltins` tagged `char`; `char` joins `IMPORTABLE_LIBRARIES`
  (visible through `(import (scheme char))` under `--scheme-standard r7rs`, merged into
  the no-import default otherwise). Lowered to core forms / `%scheme-` helpers, no
  backend change.
- Unicode per R7RS 6.6/6.7 where the backends allow it uniformly: `char-foldcase` is
  simple case folding, `string-upcase`/`string-downcase`/`string-foldcase` the FULL
  mappings (`"STRASSE"`, final sigma), the `-ci` comparisons compare folded values.
  Measure each Common Lisp classifier on all four backends first; where one diverges
  (`alpha-char-p` on wasm, `.todo/269`), do not inherit the divergence silently.
- `#!fold-case` (both readers) folds as `string-foldcase` does.
- `eval`, first-class values, multi-argument comparisons.
- Output size of a program that does not use the library: unchanged.

## Test plan

- Failing first: `SchemeLoweringTest` (import visibility), `scheme-spec.yaml` cases on
  all four backends (ASCII + Unicode edges checked against `gosh -r7`).
- Docs: `doc/{en,ja}/scheme/` library list, a reference page per name
  (`SchemeReferenceTest`), `.kb/scheme-frontend.md`.
