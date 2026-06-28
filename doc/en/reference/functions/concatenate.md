# concatenate

`(concatenate result-type &rest strings)`

Joins its string arguments into a single new string. Only the `'string` result type is supported, and in the compiled backends `result-type` must be written as the literal `'string`. With no strings given it returns the empty string `""`.

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
```
