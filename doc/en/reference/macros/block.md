# block

`(block name body...)`

Establishes a named block around `body` and returns the value of the last form, or the value thrown by a matching `(return-from name value)` fired during the body's execution. On the interpreter names are REAL: an inner `return-from` targeting an outer block passes through intervening blocks and loops, and `(block nil ...)` additionally catches plain `(return ...)` like the loop macros' implicit nil block. Compile-path lite deviation: the compilers drop the name (the body compiles as the internal `%block`), so a `return-from` there targets the nearest enclosing block.

```lisp
(block scan
  (dotimes (i 10)
    (when (= i 4) (return-from scan (* i 100))))
  :fell-through) ; => 400
```

```lisp
(block nil (return 7) 9) ; => 7
```
