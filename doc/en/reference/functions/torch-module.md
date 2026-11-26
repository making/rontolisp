# torch:module

`(torch:module kind fields forward-fn)`

Returns a new module -- the parameter-owning, composable object of the `torch` package. `kind` is a keyword naming the layer, `fields` a plist of **keyword**/value pairs holding every parameter, buffer, submodule and hyper-parameter, and `forward-fn` is what [`torch:forward`](torch-forward.md) applies, as `(funcall forward-fn module args...)`.

The fields plist is the module's parameter registration: [`torch:parameters`](torch-parameters.md) walks it, so a layer's forward must read its parameters back with [`torch:field`](torch-field.md) rather than from a closed-over variable. A field holding a tensor without `requires-grad` is a buffer and is skipped by the walk. The built-in layers ([`torch:linear`](torch-linear.md) and friends) are ordinary callers of this function.

```lisp
(defparameter *scale*
  (torch:module :scale (list :gain (torch:parameter '(2.0 3.0)))
                (lambda (self x) (torch:mul x (torch:field self :gain)))))
(torch:data (torch:forward *scale* (torch:tensor '(1.0 10.0)))) ; => #f(2.0 30.0)
(length (torch:parameters *scale*))                             ; => 1
```
