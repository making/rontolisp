# block

`(block name body...)`

Establishes a named block around `body` and returns the value of the last form, or the value thrown by a matching `(return-from name value)` fired during the body's execution. Names are matched on every backend: an inner `return-from` targeting an outer block passes through intervening blocks and loops, and `(block nil ...)` additionally catches plain `(return ...)` like the loop macros' implicit nil block. On the interpreter the match is dynamic (it crosses closure calls within the block's extent); the compilers match lexically within the same function.

```lisp
(block scan
  (dotimes (i 10)
    (when (= i 4) (return-from scan (* i 100))))
  :fell-through) ; => 400
```

```lisp
(block nil (return 7) 9) ; => 7
```
