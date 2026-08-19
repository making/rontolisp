# torch:step-count

`(torch:step-count optimizer)`

Returns how many times [`torch:step`](torch-step.md) has run on this optimizer: `0` before the first step, and the `t` of Adam's bias correction during the step itself. The counter belongs to the optimizer, not to any parameter, so two optimizers over the same parameters keep separate schedules.

```lisp
(defparameter *opt* (torch:sgd (list (torch:parameter '(1.0))) :lr 0.1))
(torch:step-count *opt*) ; => 0
(torch:step *opt*)
(torch:step *opt*)
(torch:step-count *opt*) ; => 2
```
