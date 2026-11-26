# etypecase

`(etypecase x (integer body...) (string body...))`

The exhaustive variant of `typecase`: it dispatches on the type of `x` over the same set of type specifiers, but has no default clause. If `x` matches none of the clauses, `etypecase` signals an `error` instead of returning nil, making it the right choice when every expected type should be handled explicitly.

```lisp
(let ((x 42)) (etypecase x (integer 'int) (string 'str))) ; => INT
```
