# return-from

`(return-from name [value])`

Returns `value` (default `nil`) from the enclosing block named `name`. A `defun` body is a block named after the function and a `defmethod` body one named after its generic, so a `return-from` exits the function even from inside a `do`/`loop` (whose implicit block is named nil); `(return-from nil v)` is `(return v)`. A same-function `return-from` compiles to a direct jump. A `return-from` inside a lambda that names an enclosing block — for example inside a lambda passed to `mapcar`/`mapl` — exits that block as a non-local exit on every backend, matching Common Lisp. (Such a cross-lambda exit compiles in exception-handling mode on the WASM backends. A `return-from` that would have to cross an `flet`/`labels` local function is not yet supported on the compilers.)

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
