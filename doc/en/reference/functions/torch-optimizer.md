# torch:optimizer

`(torch:optimizer kind params fields step-fn)`

Returns a fresh optimizer: `kind` is a keyword naming the rule, `params` a module (whose [`torch:parameters`](torch-parameters.md) are walked) or a plain list of parameter tensors, `fields` a plist of `KEYWORD`/value hyper-parameters and state buffers, and `step-fn` a function called as `(funcall step-fn optimizer)` by [`torch:step`](torch-step.md). The step counter starts at `0`.

This is how a user-written rule is spelled -- [`torch:sgd`](torch-sgd.md) and [`torch:adam`](torch-adam.md) are ordinary callers of it. The step function reads its hyper-parameters back with [`torch:field`](torch-field.md) and its parameters with [`torch:optimizer-params`](torch-optimizer-params.md), so everything the rule needs lives in the record rather than in a closure.

```lisp
(defun scaled-step (self)
  (dolist (p (torch:optimizer-params self))
    (unless (null (torch:grad p))
      (torch:set-data p (linalg:sub (torch:data p)
                                    (linalg:mul (torch:field self :lr) (torch:grad p)))))))
(defparameter *p* (torch:parameter '(1.0 2.0)))
(defparameter *opt* (torch:optimizer :my-sgd (list *p*) (list :lr 0.5) (function scaled-step)))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)             ; => #f(0.0 0.0)
(torch:optimizer-kind *opt*) ; => :MY-SGD
```
