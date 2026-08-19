# torch:optimizerp

`(torch:optimizerp x)`

Returns `T` when `x` is a torch optimizer -- the fixed-layout record [`torch:optimizer`](torch-optimizer.md) builds -- and `NIL` for anything else, including a tensor or a module.

```lisp
(torch:optimizerp (torch:sgd nil))       ; => T
(torch:optimizerp (torch:tensor '(1.0))) ; => NIL
(torch:optimizerp 42)                    ; => NIL
```
