# values

`(values form...)`

Returns multiple values. In an ordinary (single-value) context every argument is evaluated and the first is the result, like `prog1`; `(values)` reads as nil. The extra values are received by the consumers [`multiple-value-bind`](../macros/multiple-value-bind.md), [`multiple-value-list`](../macros/multiple-value-list.md), [`multiple-value-call`](../macros/multiple-value-call.md) and [`nth-value`](../macros/nth-value.md) -- either syntactically (a literal `(values ...)` producer) or, for a `values` in result position of a user function, through an internal channel that carries them across the call boundary to the consumer. A function that returns normally without calling `values` supplies a single value (extra variables read nil). Calling a `values`-returning function through `funcall #'values` or from compiled first-class contexts yields the primary value only.

```lisp
(multiple-value-list (values 1 2 3)) ; => (1 2 3)
```

```lisp
(values 1 2 3) ; => 1
```
