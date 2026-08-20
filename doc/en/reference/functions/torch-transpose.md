# torch:transpose

`(torch:transpose a &optional axes)`

Differentiable transpose: with no `axes` the matrix transpose (a vector passes through, like `linalg:transpose`); with an axes list the rank-n permutation (`out-dims[k] = dims[axes[k]]`, a negative axis counting from the end). The backward pass applies the inverse permutation to the gradient.

```lisp
(torch:data (torch:transpose (torch:tensor '((1.0 2.0) (3.0 4.0))))) ; => #f((1.0 3.0) (2.0 4.0))
```
