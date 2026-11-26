# torch:fields

`(torch:fields module)`

The whole fields plist of a module or an optimizer, as a FRESH list: the field
names in registration order, each followed by its value.
[`torch:field`](torch-field.md) reads ONE field by name; this is what makes a
module tree WALKABLE from outside the package.

`nn.Module.apply` and `nn.Module.named_parameters` have no counterpart here
because a walk is written over this plist plus
[`torch:module-kind`](torch-module-kind.md), which says what each layer IS --
more precise than PyTorch's dotted parameter names, where selecting the
LayerNorm parameters by testing for `'ln'` in the name also selects a layer
someone called `blend`.

The spine is fresh, so consing onto the result cannot corrupt the module; the
VALUES are the live parameters and submodules, and the way to replace one is
still [`torch:set-field`](torch-set-field.md).

```lisp
(defparameter *layer* (torch:linear 3 2))
(do ((p (torch:fields *layer*) (cddr p)) (acc nil (cons (car p) acc)))
    ((null p) (reverse acc)))                                   ; => (:WEIGHT :BIAS)
(eq (nth 1 (torch:fields *layer*)) (torch:field *layer* :weight)) ; => T
```
