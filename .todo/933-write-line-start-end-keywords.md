# `write-line` ignores or refuses `:start` / `:end` on every backend

Difficulty: Low (one lowering to `write-string` + `terpri`, plus the first-class wrapper)

Split out of `.todo/928` (2026-09-22), whose "Also left" named only the wrapper. Measured
there (the tree at `420169f1d`):

```lisp
(write-line "hello" *standard-output* :start 1 :end 3)   ; SBCL "el"
(funcall #'write-line "abcdef" *standard-output* :end 2) ; SBCL "ab"
```

- Interpreter: prints `hello` / `abcdef` -- the keywords are silently IGNORED.
- JVM and WASM: compile error `write-line expects 1 or 2 arguments, got 6`.
- `#'write-line`'s first-class wrapper (`BuiltinFunctionWrappers`,
  `unaryOptionalStream(WRITE_LINE)`) is `(string &optional stream)`, so the funcall is a
  `program-error` since `.todo/921`.
- `write-string` already takes `:start`/`:end` (and an explicit nil `:end`) in call
  position on all three, and its wrapper is `boundedSequenceIo` -- the natural lowering
  is `(let ((s ..) (st ..)) (write-string s st :start a :end b) (terpri st) s)`.
- ANSI: `WRITE-LINE.1`-`.12` and `.ERROR.2`-`.4` FAIL in the interpreter; check which of
  them this moves.

Coordinate with whoever holds `read-sequence`/`write-sequence` (`boundedSequenceIo` is
shared).
