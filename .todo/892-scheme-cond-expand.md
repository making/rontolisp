# `cond-expand` for the Scheme front end

Difficulty: Medium

Split off from `.todo/826` (the `cond-expand` row). Today `SchemeLowering` refuses
`cond-expand` by name, in a program body and as a `define-library` declaration.

## Scope

- R7RS 4.2.1 / 5.6.1: `cond-expand` at the top level, in a body (as a `begin` of the chosen
  clause, so definitions splice), and as a library declaration (the chosen clause's
  declarations splice into the library).
- Requirements: a feature identifier, `(library (a b))`, `(and ...)`, `(or ...)`,
  `(not ...)`, `else`. No clause taken and no `else`: nothing (R7RS leaves it unspecified).
- Our feature list is ours, not Gauche's: document it (`r7rs`, `exact-closed`,
  `ratios`, `full-unicode`?, `rontolisp`, ... -- only what is true on all four backends).
  `(library X)` is true for an importable standard library and for a library the program
  can find (a leading `define-library` or a file).
- Lower to core forms only; no backend change; a program without the feature compiles to
  byte-identical output (measure).

## Test plan

- Failing first: `SchemeLoweringTest` / `SchemeLibrariesTest`, a `scheme-spec.yaml` case on
  all four backends against `gosh -r7`.
- `.kb/scheme-frontend.md`; a reference page (`SchemeReferenceTest`), doc/en + doc/ja; the
  deviations page and `define-library.md` lose the refusal.
