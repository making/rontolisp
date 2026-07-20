# return-from

`(return-from name [value])`

Returns `value` (default `nil`) from the enclosing block named `name`. A `defun` body is a block named after the function and a `defmethod` body one named after its generic, so a `return-from` exits the function even from inside a `do`/`loop` (whose implicit block is named nil); `(return-from nil v)` is `(return v)`. On the interpreter the exit is dynamic (a named signal), so it also crosses a closure called within the exit's dynamic extent. The compilers implement the exit lexically instead: the target must be a lexically enclosing block in the same function, so a `return-from` inside a lambda whose name matches no enclosing block exits that lambda, not the outer function.

```lisp
(defun classify (n)
  (when (= n 0)
    (return-from classify :zero))
  (* n 10))
(classify 0) ; => :ZERO
```

```lisp
(defun first-even (items)
  (dolist (x items)
    (when (evenp x)
      (return-from first-even x)))
  :none)
(first-even '(1 3 4 5)) ; => 4
```
