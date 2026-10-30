# h36. A run-time error inside a `load`ed / `load-system`ed file reports the CALLER's position

Difficulty: Medium

Found 2026-10-30. On the interpreter, an error raised while a top-level form of a
loaded file RUNS is located at the `load`/`load-system`/`ql:quickload` form of the
outer file:

```
;; a.lisp:  (defun ok () 1) / blank / (no-such-fn 1)
;; main.lisp line 2: (load "a.lisp")
Unhandled condition: The function NO-SUCH-FN is undefined
  at main.lisp:2                      ; expected a.lisp:3
```

The same holds through `asdf:load-system` and a function defined in the loaded file
(`(defun f () (no-such-fn 1))` called there). An error raised during macro EXPANSION
is located correctly (`at a.lisp:3 in M`). Probing libraries depends on this message:
during this probe every quickload failure read `at <probe>.lisp:1`, and each culprit
file had to be found by grep. Fix in the interpreter's position tracking for loaded
files (`.kb/source-positions.md`, `LocatedCons`). Add the failing test first.
