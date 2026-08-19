# torch:sub

`(torch:sub a b)`

Differentiable elementwise `a - b` with numpy-style broadcasting (`linalg:sub`); the second operand's gradient is negated (and unbroadcast, like [`torch:add`](torch-add.md)).

```lisp
(torch:data (torch:sub (torch:tensor '(5.0 7.0)) (torch:tensor '(1.0 2.0)))) ; => #d(4.0 5.0)
```
