# ctypecase

`(ctypecase x (integer body...) (string body...))`

The exhaustive variant of `typecase`: it dispatches on the type of `x` over the same set of type specifiers, but has no default clause. If `x` matches none of the clauses, `ctypecase` signals an `error` instead of returning nil, making it the right choice when every expected type should be handled explicitly. In full Common Lisp `ctypecase` is *correctable* -- it offers a `store-value` restart to supply a new value -- but rontolisp's `ctypecase` establishes no `store-value` restart, so it behaves identically to `etypecase` and is provided mainly for source compatibility.

```lisp
(let ((x 42)) (ctypecase x (integer 'int) (string 'str))) ; => INT
```
