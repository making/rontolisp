# torch:training-p

`(torch:training-p module)`

Returns `T` when the module is in training mode, `NIL` in evaluation mode. A module starts in training mode; [`torch:train`](torch-train.md) and [`torch:eval`](torch-eval.md) switch it, recursively through submodules.

```lisp
(torch:training-p (torch:dropout 0.5))              ; => T
(torch:training-p (torch:eval (torch:dropout 0.5))) ; => NIL
```
