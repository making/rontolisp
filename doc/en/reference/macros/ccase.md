# ccase

`(ccase key (k1 body...) ...)`

Like `ecase`, `ccase` dispatches on `key` with `eql` and signals an `error` when no clause matches. In full Common Lisp `ccase` is *correctable* -- it offers a restart to supply a new value -- but rontolisp's `ccase` establishes no `store-value` restart, so it behaves identically to `ecase` and is provided mainly for source compatibility.

```lisp
(let ((x 1)) (ccase x (1 'one) (2 'two))) ; => ONE
```
