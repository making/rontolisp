# Scheme `exit` runs the outstanding `dynamic-wind` after thunks

Difficulty: Medium

`.todo/836` added `exit` / `emergency-exit` over `%host-exit` (the `uiop:quit` primitive),
so on every backend the process ends where the call stands. R7RS 6.14 says `exit` "runs all
outstanding dynamic-wind after procedures"; only `emergency-exit` may skip them. Today both
skip them (stated deviation in `doc/*/guides/scheme.md` and `.kb/scheme-frontend.md`):

```scheme
(dynamic-wind (lambda () #t) (lambda () (exit 7)) (lambda () (display "after")))
```

prints nothing and exits 7; R7RS wants `after` then 7.

`%scheme-dynamic-wind` is an `unwind-protect`, and `%host-exit` is deliberately invisible
to it (`eval/LispExitSignal`, `.kb/uiop.md`). A way that stays in the lowering: `exit`
throws to a catch tag every lowered FILE wraps around its top-level forms (unwinding
through the `unwind-protect`s), and the catch calls `%scheme-exit`. Check the cost of that
wrapper on the compiled size numbers in `.kb/scheme-frontend.md` and that a REPL session
(no whole file to wrap) gets the same order. Pin it in
`SchemeSpecE2eTest.exitEndsTheProcessWithItsStatusOnEveryBackend`.
