# torch:tensor

`(torch:tensor x &key requires-grad element-type)`

Returns a fresh leaf tensor -- the differentiable value of the `torch` package -- from a number (a rank-0 scalar tensor), a list (flat, or a list of equal-length rows), an array, a linalg array, or another tensor (whose data is copied). `:requires-grad t` marks it as a parameter whose gradient [`torch:backward`](torch-backward.md) should fill in; `:element-type 'single-float` builds packed single-float (`#f`) data.

A tensor prints as `#<TENSOR data>`, with ` :REQUIRES-GRAD T` appended for a parameter -- the same text on every backend, since only the data is shown and never the backward closure it may carry. Read the values themselves back with [`torch:data`](torch-data.md), [`torch:item`](torch-item.md) and [`torch:grad`](torch-grad.md).

```lisp
(print (torch:tensor '(1 2 3)))
(print (torch:tensor '(1.0) :requires-grad t))
```

```
#<TENSOR #d(1.0 2.0 3.0)>
#<TENSOR #d(1.0) :REQUIRES-GRAD T>
```

```lisp
(torch:data (torch:tensor '(1 2 3)))                              ; => #d(1.0 2.0 3.0)
(torch:data (torch:tensor 2.5))                                   ; => 2.5
(torch:data (torch:tensor #(1 2) :element-type 'single-float))    ; => #f(1.0 2.0)
(torch:requires-grad-p (torch:tensor '(1.0) :requires-grad t))    ; => T
```
