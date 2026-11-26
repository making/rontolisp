# torch:view

`(torch:view a shape)`

PyTorch のもう 1 つの reshape の綴りです。rontolisp の配列は常に連続で、linalg の結果はすべて新しいコピーなので、ここでの `view` は [`torch:reshape`](torch-reshape.md) そのものです -- ストレージを共有しません。

```lisp
(torch:shape (torch:view (torch:tensor '(1.0 2.0 3.0 4.0 5.0 6.0)) '(2 3))) ; => (2 3)
```
