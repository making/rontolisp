# torch:tensorp

`(torch:tensorp x)`

Returns whether `x` is a torch tensor (the value [`torch:tensor`](torch-tensor.md) and every torch operation build); anything else -- a linalg array included -- answers `nil`.

```lisp
(torch:tensorp (torch:tensor '(1 2)))  ; => T
(torch:tensorp #(1 2))                 ; => NIL
```
