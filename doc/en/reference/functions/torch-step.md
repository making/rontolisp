# torch:step

`(torch:step optimizer)`

Applies the optimizer's rule to every parameter and returns the optimizer (PyTorch's `optimizer.step()`). The step counter is incremented **first**, so a bias correction reading [`torch:step-count`](torch-step-count.md) sees `1` during the first step.

The update writes each parameter's data in place with no torch operation, so it records nothing on the tape: unlike a hand-written update built from [`torch:set-data`](torch-set-data.md), it needs no [`torch:no-grad`](../macros/torch-no-grad.md) around it. A parameter whose gradient is still `NIL` is skipped.

```lisp
(defparameter *p* (torch:parameter '(4.0)))
(defparameter *opt* (torch:sgd (list *p*) :lr 0.5))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)         ; => #f(0.0)
(torch:step-count *opt*) ; => 1
```
