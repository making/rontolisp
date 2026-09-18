# Scheme: `guard`, `raise`, `with-exception-handler` and error objects

Difficulty: High

Split off `.todo/826`'s `guard` row. Today a Scheme `error` can only end the program.

## Scope

- `(scheme base)`: `guard` (syntax, `=>` and `else` clauses), `raise`, `raise-continuable`,
  `with-exception-handler`, `error` with irritants raising an error object,
  `error-object?`, `error-object-message`, `error-object-irritants`, `read-error?`,
  `file-error?`.
- Invariant of `.todo/826`: lower to core forms (`handler-case`, `handler-bind`, a
  condition class in `scheme.lisp`), change no backend.
- A built-in error (`(car 1)`, `(vector-ref v 10)`) is catchable by `guard` and reaches a
  `with-exception-handler` handler, as in Gauche.
- An uncaught `error` keeps its current one-line report; an uncaught `raise` of a
  non-condition reports the object.
- Stated deviations (the body is unwound before a `guard` clause runs; re-entry does not
  exist) go on the doc pages.

## Test plan

- Failing tests first: `SchemeLoweringTest` (the emitted shape), `scheme-spec.yaml`
  cases on all four backends with Gauche 0.9.15 (`gosh -r7`) as the oracle, the uncaught
  report in `RontoLispCliTest`.
- `SchemeReferenceTest`: one reference page per new name in both trees.
- Size: a program without these names compiles byte-identical (`hello.scm`).
