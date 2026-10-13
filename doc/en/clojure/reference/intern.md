# intern

`(intern ns name)` / `(intern ns name val)`

The var `name` (a symbol) in `ns` (a symbol or a namespace) -- an existing one kept, given
`val` rebound like `bindRoot`, else made with `val` (unbound without one) over a cell of its
own, like the oracle's. The cell answers `var-get`, `deref`, `alter-var-root` and
`with-redefs-fn`; a `def` or `defn` of the name keeps its own cell, so a direct call never
sees the interned root, like the oracle's static link. A non-symbol name or namespace is
refused. As a value two or three arguments.

```clojure
(println (intern 'user 'doc-eg-v 7)) ; #'user/doc-eg-v
(println @(intern 'user 'doc-eg-v)) ; 7
```
