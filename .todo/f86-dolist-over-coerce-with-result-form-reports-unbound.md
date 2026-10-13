# f86. dolist over coerce with a result form reports its binding unbound

Difficulty: Medium

Found closing `.todo/f75` (2026-10-13): on the interpreter, a `dolist` whose
list form is `(coerce s 'list)` and whose result form names a `let` binding
fails with `The variable FOUND is unbound` when the enclosing `defun` takes
two or more parameters:

```lisp
(defun f (s k)
  (let ((found nil))
    (dolist (e (coerce s 'list) found)
      (if (equal e k) (setq found t))))
  found)
```

One parameter, no `coerce`, or no result form each evaluate fine, and the same
shape with `dolist` over a plain list parameter works, so it smells like a
closure-conversion or `dolist`-expansion capture bug rather than a scoping
rule. The f75 `%clojure-accessor` works around it by dropping the result form
(a trailing `found` after the `dolist`).

## Plan

1. A minimal Common Lisp repro of the failing shape (no Clojure front end
   needed: `RontoLispCli` runs `.lisp` directly), then the fix where the
   expansion or conversion drops the binding.
2. A unit test pinning the fixed shape; re-check `%clojure-accessor` still
   answers (or adopt the result form there).
