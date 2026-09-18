# Extra values leak past a single-value context on the interpreter

Difficulty: Medium

Measured 2026-09-18 on the interpreter (`java -jar ...-exec.jar`):

- The REPL echoes values a form did not produce. Scheme: `(+ 1 (values 5 6))` echoes `6`
  then `6`; `(car (list (values 5 6)))` echoes `5` then `6`. `LispEvaluator.evalValues`
  reads the `%mv-spill` channel after the form, and an INNER call that spilled (a
  function whose tail was `values`, or one ending in a CL operator with a second value)
  leaves it set even though its values were consumed as one argument. The Common Lisp
  REPL shares the mechanism; check `(list (floor-user-fn))` shapes there too.
- File mode, Scheme: `(call-with-values (lambda () (values)) list)` answers `(())`;
  R7RS and gosh answer `()`. Check whether this is the same channel and whether the JVM
  and WASM backends agree.

The `string->symbol` instance was fixed at its source (`%scheme-string->symbol` answers
`(values (intern ...))`, `.kb/scheme-frontend.md` Traps); the general leak is this item.
A fix must not add a map write per argument evaluation to the interpreter's hot path --
measure it.
