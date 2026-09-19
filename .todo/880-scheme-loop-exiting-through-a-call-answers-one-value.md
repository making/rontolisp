# Scheme: a loop that exits through a call answers that call's first value only

Difficulty: Medium

Since the `%mv-spill` channel is exact (`.kb/multiple-values.md`, 2026-09-19), a
`(setq R value)` keeps one value on every backend, so the destination-driven loop
lowering (`SchemeLowering.pureLoop`/`selfLoop`, `.kb/scheme-frontend.md`
"Destination-driven lowering") carries every value of a leaf only when some leaf is a
literal `(values ...)` (`ValueCount`). A loop whose exit is a CALL answers one value:

```scheme
(define (two) (values 5 6))
(define (first-even l)
  (let loop ((l l))
    (cond ((null? l) (two)) ((even? (car l)) (car l)) (else (loop (cdr l))))))
(call-with-values (lambda () (first-even '(1 3))) list) ; gosh: (5 6); rontolisp: (5), all four backends
```

Documented in `doc/*/scheme/deviations.md`. The exact answer costs every loop exit a
`multiple-value-list` (a spill round-trip and a cons) plus a `values-list` at the loop's
end, and pulls the spill global -- and the tail clears in every function -- into every
Scheme program with a loop that exits through a call, which is most of them. Options:
a `return-from` exit for call leaves when the loop is not on a hot path (an exception
per exit on the interpreter, too slow inside an outer loop); the list carrier only when
the callee is a user procedure whose lowered body mentions `values`; or leaving the
deviation. Measure before choosing.
