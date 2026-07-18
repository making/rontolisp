# return-from

`(return-from name [value])`

Returns `value` (default `nil`) from the enclosing block named `name`. On the interpreter this is a REAL named non-local exit: a `defun` body is a block named after the function and a `defmethod` body one named after its generic, so a `return-from` exits the function even from inside a `do`/`loop` (whose implicit block is named nil) or from a closure called within the exit's dynamic extent; `(return-from nil v)` is `(return v)`. Compile-path lite deviation: the compilers drop the name and rewrite to `(return value)`, so there a `return-from` nested inside a `do`/`loop` exits that loop (the nearest block) instead — equivalent only when the loop is the function's final form.

```lisp
(defun classify (n)
  (when (= n 0)
    (return-from classify :zero))
  (* n 10))
(classify 0) ; => :zero
```

```lisp
(defun first-even (items)
  (dolist (x items)
    (when (evenp x)
      (return-from first-even x)))
  :none)
(first-even '(1 3 4 5)) ; => 4
```
