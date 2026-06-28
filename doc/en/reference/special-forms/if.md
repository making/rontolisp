# if

`(if test then [else])`

Evaluates `test`; if it is non-nil the `then` branch is evaluated and returned, otherwise the optional `else` branch is evaluated and returned. Only one of the two branches is ever evaluated. `nil` is the only false value -- every other value, including `0` and the empty string, counts as true. With no `else` branch and a false `test`, the result is `nil`.

```lisp
(if (> 3 2) 'yes 'no) ; => yes
```
