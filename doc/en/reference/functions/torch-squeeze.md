# torch:squeeze

`(torch:squeeze a &key axis)`

Differentiable extent-1 axis removal (`linalg:squeeze`): all of them with no `:axis`, else only the named axis (or list of axes). Squeezing every axis away yields the scalar tensor.

```lisp
(torch:shape (torch:squeeze (torch:tensor '((1.0 2.0 3.0))))) ; => (3)
(torch:data (torch:squeeze (torch:tensor '((7.0)))))          ; => 7.0
```
