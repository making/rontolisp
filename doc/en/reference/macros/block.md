# block

`(block name body...)`

Establishes a named block around `body` and returns the value of the last form, or the value thrown by a matching `(return-from name value)` fired during the body's execution. Names are matched on every backend: an inner `return-from` targeting an outer block passes through intervening blocks and loops, and `(block nil ...)` additionally catches plain `(return ...)` like the loop macros' implicit nil block. The match is LEXICAL on every backend — a `return-from` (or a plain `return`) inside a closure exits the block that encloses it in the SOURCE, so a `handler-bind` handler written inside `(block nil ...)` exits that block, not whichever same-named block is running where the condition was signalled. Exiting a block whose activation has already returned is an error.

```lisp
(block scan
  (dotimes (i 10)
    (when (= i 4) (return-from scan (* i 100))))
  :fell-through) ; => 400
```

```lisp
(block nil (return 7) 9) ; => 7
```
