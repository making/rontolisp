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

### It is PRODUCTION code that catches, not only tests

The second sighting, and the sharper one, is
[cl-postgres-client](https://github.com/making/cl-postgres-client) -- a
JdbcClient-style layer over cl-postgres with a 186-assertion rove suite against
a dockerised PostgreSQL 17. Its `execute-prepared` recovers from SQLSTATE 26000
(the server has deallocated a statement the cache still remembers) with

```lisp
(handler-case (run (prepared-statement-name client sql))
  (cl-postgres:database-error (condition) ... (run (prepared-statement-name client sql))))
```

rove's outer `handler-bind` runs anyway and transfers control, so the retry
never completes and the assertion after it fails. Nothing on the library side
can avoid this, and it is not a test catching an error -- it is a library
catching its own. **This is the whole remaining gap between rontolisp and SBCL
on that suite**: as of 2026-08-16 all three rontolisp backends answer
183 passed / 2 failed against SBCL's 185 / 0, and both failures are the
`stale-prepared-statements` pair above, identical on every backend
(`.todo/408`, now closed, was the per-backend status record).

Re-measuring it is the cheapest end-to-end check that this item is gone. Needs
Docker; the JVM target also needs `java`, the wasm one `wasmtime` 47+:

```bash
git clone https://github.com/making/cl-postgres-client && cd cl-postgres-client
make RONTOLISP="java -jar /path/to/rontolisp/target/rontolisp-0.1.0-SNAPSHOT-exec.jar" \
     rontolisp-test       # then rontolisp-test-jvm, rontolisp-test-wasm
make test                 # SBCL reference, 185/0
```

Each target starts its own PostgreSQL container (port 55432) and fills
rontolisp's Quicklisp cache. Do NOT rewrite the library's retry as
`handler-bind` + a named block to make it pass -- that was measured to work and
deliberately rejected upstream, because it buys nothing on SBCL and would be
left behind as unexplained complexity the day this item lands.

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
`examples/cloudflare-workers/httpbin/check.lisp` when it lands. Then re-run the
cl-postgres-client suite above on all three backends and delete
[its own watch item](https://github.com/making/cl-postgres-client) `.todo/002`
there, which tracks the same numbers from the library's side.
