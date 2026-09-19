# `|...|` identifiers and `+inf.0` / `-inf.0` / `+nan.0` for the Scheme front end

Difficulty: Medium

Split off from `.todo/826` (the `|...|` / `+inf.0` row). Today `SchemeReader` refuses both
by name ("|...| identifiers are not supported", "infinities and NaN are not supported"),
and so does the run-time reader behind `read` in `scheme.lisp`; `string->number` answers
`#f` for `"+inf.0"`. The printer already writes `+inf.0` `-inf.0` `+nan.0`.

## Scope

- Reader (`SchemeReader` and `read`): `|...|` with the R7RS escapes (`\|`, `\xHH;`, the
  mnemonic escapes), not case-folded by `#!fold-case`; `+inf.0` `-inf.0` `+nan.0` `-nan.0`,
  case-insensitive, in any radix. `string->number` the same.
- `write` of a symbol that would not read back as itself: vertical lines, as Gauche
  (`|foo bar|`, `||`, `|1|`, `|a\x0a;b|`); `display` unchanged. The printer is in nearly
  every program: gate the arm so a program that can make no such symbol keeps its bytes.
- Every backend must PRINT and COMPARE the infinities and NaN alike (`=`, `eqv?`, `nan?`,
  arithmetic on them) -- check the literal constant on the JVM and both WASM backends.
- Lower to core forms only; no backend change; a program without the feature compiles to
  byte-identical output (measure).

## Test plan

- Failing first: `SchemeReaderTest`, `SchemeLoweringTest`, a `scheme-spec.yaml` case on all
  four backends against `gosh -r7`.
- `.kb/scheme-frontend.md`; the reference pages that mention the refusal (doc/en + doc/ja).
