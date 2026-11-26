# intern

`(intern ns name)` / `(intern ns name val)`

The var `name` (a symbol) in `ns` (a symbol or a namespace) -- an existing one kept, given
`val` rebound like `bindRoot`, else made with `val` (unbound without one), like the oracle's.
Of a quoted name in the current namespace (`*ns*` or its quoted name) the var is defined
where the call stands, like `(do (def name val) (var name))` -- `(declare name)` without
`val` -- so the forms below name it, as the oracle's form-by-form compile finds the var, and
`val` is the root a `def`'s reads see. Any other is made at run time over a cell of its own,
which answers `var-get`, `deref`, `alter-var-root` and `with-redefs-fn`; a `def` or `defn` of
the name keeps its own cell, so a direct call never sees that root, like the oracle's static
link. A name carrying metadata (`(with-meta 'x {:a 1})`) gives the var that metadata with
`:name` and `:ns`, like the oracle's `setMeta`. A non-symbol name or namespace is refused. As
a value two or three arguments.

```clojure
(println (intern 'user 'doc-eg-v 7)) ; #'user/doc-eg-v
(println @(intern 'user 'doc-eg-v)) ; 7
```
