# flet

`(flet ((name lambda-list body...)...) body...)`

Binds local functions for the body. Lisp-2: each name lives in the function namespace only, so it is reached from call position (`(name args...)`) and via `#'name`, while a bare `name` remains a variable reference. The definitions are NOT visible to each other or to themselves -- a definition body sees the outer function of the same name (use [`labels`](labels.md) for recursion). Lambda lists support the same extensions as `defun` (`&optional`/`&rest`/`&key`/`&aux`).

Expands into `let`-bound lambdas plus a body rewrite, so a local function is an ordinary closure: it can capture surrounding variables and be passed to `mapcar`/`funcall`/`apply` via `#'name`.

```lisp
(flet ((sq (x) (* x x))
       (dbl (x) (* 2 x)))
  (list (sq (dbl 3)) (mapcar #'sq '(1 2 3)))) ; => (36 (1 4 9))
```
