# torch:transpose

`(torch:transpose a &optional axes)`

Differentiable transpose: with no `axes` the matrix transpose (a vector passes through, like `linalg:transpose`); with an axes list the rank-n permutation (`out-dims[k] = dims[axes[k]]`, a negative axis counting from the end). The backward pass applies the inverse permutation to the gradient. The matrix transpose and an axes list that exchanges exactly the last two axes (`'(0 2 1)` on a stack) return a **view**: no copy is made, `torch:matmul` reads the source in place and sends the gradient straight to it, and any other reader materializes the transpose once.

```lisp
(torch:data (torch:transpose (torch:tensor '((1.0 2.0) (3.0 4.0))))) ; => #f((1.0 3.0) (2.0 4.0))
```
