# torch:div

`(torch:div a b)`

Differentiable elementwise `a / b` with numpy-style broadcasting (`linalg:div`): the numerator's gradient is `g / b`, the denominator's `-g * a / b^2`. An array divided by a plain number (or an untracked scalar tensor) is returned as a **view**: nothing is computed until something reads the data, and a [`torch:softmax`](torch-softmax.md) over it folds the division into its own pass.

```lisp
(torch:data (torch:div (torch:tensor '(6.0 9.0)) (torch:tensor '(2.0 3.0)))) ; => #f(3.0 3.0)
```
