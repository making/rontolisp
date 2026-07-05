# values

`(values form...)`

Returns multiple values. rontolisp has no runtime multiple-value representation: all values of a literal `(values ...)` call are received only by the syntactic consumers [`multiple-value-bind`](../macros/multiple-value-bind.md), [`multiple-value-list`](../macros/multiple-value-list.md), [`multiple-value-call`](../macros/multiple-value-call.md) and [`nth-value`](../macros/nth-value.md). In any other (single-value) context every argument is evaluated and the first is the result, like `prog1`; `(values)` reads as nil. Consequently a `(values ...)` at the end of a function collapses to its primary value at the call boundary -- a caller's `multiple-value-bind` over that call binds the extra variables to nil.

```lisp
(multiple-value-list (values 1 2 3)) ; => (1 2 3)
```

```lisp
(values 1 2 3) ; => 1
```
