# return-from

`(return-from name [value])`

Returns `value` (default `nil`) from the enclosing function, ending its execution early. The block `name` is ignored — there are no named blocks — so inside a `defun` or `lambda` body every `return-from` exits the function itself. Deviation from Common Lisp: a `return-from` nested inside a `do`/`loop` exits that loop (the nearest block) instead of the function, which is only equivalent when the loop is the function's final form; `block` itself is not supported.

```lisp
(defun classify (n)
  (when (= n 0)
    (return-from classify :zero))
  (* n 10))
(classify 0) ; => :zero
```
