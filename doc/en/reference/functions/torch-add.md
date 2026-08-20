# torch:add

`(torch:add a b)`

Differentiable elementwise `a + b` with numpy-style broadcasting (`linalg:add`); either operand may be a tensor, a number, an array or a list. The backward pass sums the gradient over every broadcast axis, so a `(d)` bias added to a `(b s d)` activation gets the `(d)` gradient it should.

```lisp
(torch:data (torch:add (torch:tensor '((1.0 2.0) (3.0 4.0))) (torch:tensor '(10.0 20.0))))
; => #f((11.0 22.0) (13.0 24.0))
(torch:data (torch:add (torch:tensor '(1.0 2.0)) 0.5)) ; => #f(1.5 2.5)
```
