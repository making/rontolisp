# torch:view

`(torch:view a shape)`

PyTorch's other reshape spelling. rontolisp arrays are always contiguous and every linalg result is a fresh copy, so `view` is exactly [`torch:reshape`](torch-reshape.md) here -- it does not alias storage.

```lisp
(torch:shape (torch:view (torch:tensor '(1.0 2.0 3.0 4.0 5.0 6.0)) '(2 3))) ; => (2 3)
```
