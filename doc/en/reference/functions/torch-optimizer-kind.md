# torch:optimizer-kind

`(torch:optimizer-kind optimizer)`

Returns the optimizer's kind keyword, as given to [`torch:optimizer`](torch-optimizer.md) -- `:sgd` for [`torch:sgd`](torch-sgd.md), `:adam` for [`torch:adam`](torch-adam.md). Signals unless the argument is an optimizer.

```lisp
(torch:optimizer-kind (torch:sgd nil))  ; => :SGD
(torch:optimizer-kind (torch:adam nil)) ; => :ADAM
```
