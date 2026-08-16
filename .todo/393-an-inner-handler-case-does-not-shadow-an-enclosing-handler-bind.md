# 393. An inner handler-case does not shadow an enclosing handler-bind

Difficulty: High

Found while converting examples to rove (`.todo/392`). CLHS 9.1.4.1: handlers
run MOST RECENT FIRST, and `handler-case` transfers control -- so a
`handler-case` established inside a `handler-bind`'s extent handles the
condition and the outer handler-bind handler never runs. Here the outer one
runs anyway, on EVERY backend:

```lisp
(handler-bind ((error (lambda (e) (format t "outer saw: ~a~%" e))))
  (format t "inner: ~a~%" (handler-case (error "boom") (error () :caught))))
;; rontolisp: outer saw: boom / inner: CAUGHT      SBCL: inner: CAUGHT
```

The inner `handler-case` still wins the control transfer, so a program that
only READS the value is unaffected. What breaks is an outer handler that
transfers control itself -- and that is exactly what every test framework's
recorder is.

## Why it matters

rove wraps each test body in
`(handler-bind ((error (lambda (e) (record ...) (return-from block ...))))`.
So a test over code that catches its own error -- a parse with a fallback, an
optional-feature probe, `ignore-errors` -- is reported as
`Raise an error while testing.` and ENDS on the case it was checking, even
though the code under test behaved correctly. The first real sighting:
`examples/cloudflare-workers/httpbin/check.lisp`'s unparseable-body probe,
where `body-json`'s `(handler-case (json-parse body) (error () 'null))` is the
whole point of the case. The workaround in the tree today is to drive such code
BEFORE the test and assert the value (documented in
`doc/{en,ja}/guides/testing.md` "Limitations"); the example carries a comment
saying so, without naming this item.

The interpreter records the failure three times and the JVM once -- a
cross-backend divergence in the fallout, not just in the trigger.

## Where it is

`.kb/error-handling.md` "Phase 4" deviation (3) already writes half of this
down: "an intervening `handler-case` never suppresses the signal-point hook
(its clauses are not clusters -- pre-existing Phase 4 shape)". `handler-bind`
pushes a cluster onto the handler stack; `handler-case` does not, it compiles
to its own catch/throw. So the signal walk cannot see that a nearer handler
exists. The RAW-error half of the same deviation notes the compiled backends
DO suppress the handlers (their pad sits outside the handler-case) while the
interpreter does not -- CL agrees with the compiled backends there. For a
SIGNALED condition every backend is wrong.

## Sketch

Make `handler-case` push a cluster of its own -- one whose handler performs the
transfer -- so the ordinary most-recent-first walk finds it before any
enclosing `handler-bind` cluster. Then deviation (3) disappears in both halves
and the raw-error asymmetry with it.

Watch:
- the cluster must be POPPED for the duration of its own clause body (a
  `handler-case` clause must not catch what it itself signals);
- `usesRestartSystem` gates the whole cluster machinery today, and a
  `handler-case` with no restart usage anywhere must stay byte-identical
  (`.kb/emitted-output-determinism.md`) -- so the cluster push probably has to
  be gated on "some `handler-bind` exists in the program", the same shape the
  signal hook already uses;
- `ignore-errors` expands to `handler-case` and inherits the fix;
- all four backends, plus the wasm raw-TRAP spectrum which stays uncatchable.

Pin with the two-line repro above in `LispEvaluatorTest` + the `compileAndRun`
twins + the wasm EH twin, and restore the in-test probe in
`examples/cloudflare-workers/httpbin/check.lisp` when it lands.
