# Scheme: `parameterize` and `make-parameter`

Difficulty: Medium

Split off `.todo/826`'s `parameterize` row. Today `parameterize` is refused by name and
`make-parameter` is unknown.

## Scope

- `(scheme base)`: `make-parameter` (with and without a converter) and `parameterize`.
- Invariant of `.todo/826`: lower to core forms, change no backend. The binding rides the
  special-`let` restore (`.kb/dynamic-special-variables.md`) inside a `scheme.lisp`
  helper, so every exit channel restores it -- `guard`/`raise`, `call/cc` escapes,
  `dynamic-wind`, `exit`.
- A parameter object is a procedure (`procedure?` true); the converter runs on the
  initial value and on each `parameterize` value, not on restore (R7RS 4.2.6).
- Interoperates with `define-syntax` (`SchemeExpander` scoping) and
  `--scheme-standard r7rs` (both names tagged `base`).

## Test plan

- Failing tests first: `SchemeLoweringTest` (the emitted shape), `scheme-spec.yaml` cases
  on all four backends with Gauche 0.9.15 (`gosh -r7`) as the oracle, including a raise
  out of a `parameterize` caught by `guard` and a `call/cc` escape.
- `SchemeReferenceTest`: one reference page per new name in both trees.
- Size: a program without these names compiles byte-identical (`hello.scm`, a `display`
  of a list).
