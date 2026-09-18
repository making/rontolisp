# values

`(values obj ...)`

Returns its arguments as multiple values, to be received by `call-with-values`, `let-values` or `define-values`; `(values)` returns none. Where only one value is expected, the first is used. Written as a call, `(values 1 2)` returns every value on every backend; `values` used as a first-class procedure -- `(apply values '(1 2))`, `values` passed through a variable, or inside `eval` -- returns only its first value on the compiled backends, while the interpreter returns them all.

```scheme
(values 1 2) ; => 1, 2
(values 'a) ; => a
```
