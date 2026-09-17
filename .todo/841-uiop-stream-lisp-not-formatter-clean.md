# `uiop-stream.lisp` is not formatter-clean on develop

Difficulty: Low

Seen while closing `.todo/835` (2026-09-17): the "After Task Completion" format command
(`java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar format ... src/main/resources/`)
rewrites `src/main/resources/am/ik/rontolisp/eval/uiop-stream.lisp`, which that item did
not touch.

## To do

1. Find the commit that left it unformatted (`git log -- <file>`). Decide whether the
   file or the formatter is wrong: if the formatter's output is worse (or not idempotent --
   run it twice), fix the formatter with a failing test in the `format` package;
   otherwise commit the formatted file.
2. If a test can guard that shipped `.lisp` resources stay formatter-clean cheaply,
   consider adding it.
