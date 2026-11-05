# h85. `format`'s `~{` over a non-list argument whose body consumes no argument

Difficulty: Low

```lisp
(format t "[~v{ ~}]" 2 0)                  ; SBCL: "[  ]"
(format t "┌~{~v{─~}~^┬~:*~}┐" '(3 4 0))   ; SBCL: "┌───┬────┐"
```

rontolisp (measured again 2026-11-05 on the interpreter and JVM): `LENGTH: The value 0 is not of type SEQUENCE`. SBCL never inspects the
argument when the body takes none, so `~v{X~}` with a dummy argument is a common "repeat X n
times" idiom. trivial-benchmark's `print-table` (`toolkit.lisp`) uses both forms, so
`(org.shirakumo.trivial-benchmark:with-timing (10) ...)` cannot print its report. Inside the
library the failure surfaces as `error: stack overflow` rather than this error -- find out why
(signalling while reporting?) as part of the fix.

Both `format` renderings (`.kb/format.md`), all four backends.
