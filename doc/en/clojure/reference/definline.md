# definline

`(definline name docstring? [params...] body...)`
`(definline name docstring? ([params...] body...)+`

Defines a function, exactly like [`defn`](defn.md): the same arities, the same
direct calls, the same first-class values, the same forward uses. The oracle
additionally records the body as an inline expander for call sites; here that
recording is a documented no-op -- nothing inlines call sites -- so the
definition is the function itself. As a value it is unknown
(`(def x definline)` refuses).

```clojure
(definline di-add [x y] (+ x y))
(println (di-add 2 3)) ; 5
```
