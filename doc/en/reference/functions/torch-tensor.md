# torch:tensor

`(torch:tensor x &key requires-grad element-type)`

Returns a fresh leaf tensor -- the differentiable value of the `torch` package -- from a number (a rank-0 scalar tensor), a list (flat, or a list of equal-length rows), an array, a linalg array, or another tensor (whose data is copied). `:requires-grad t` marks it as a parameter whose gradient [`torch:backward`](torch-backward.md) should fill in; `:element-type 'single-float` builds packed single-float (`#f`) data.

A tensor prints as its raw record, so examples read it back with [`torch:data`](torch-data.md) (the tape closures it may carry have no portable printed form).

```lisp
(torch:data (torch:tensor '(1 2 3)))                              ; => #d(1.0 2.0 3.0)
(torch:data (torch:tensor 2.5))                                   ; => 2.5
(torch:data (torch:tensor #(1 2) :element-type 'single-float))    ; => #f(1.0 2.0)
(torch:requires-grad-p (torch:tensor '(1.0) :requires-grad t))    ; => T
```
