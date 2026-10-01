# gensym

`(gensym)` / `(gensym prefix)`

Answers a fresh uninterned symbol (`#:`-spelled), a new one per evaluation -- per
expansion inside a macro body (what `x#` in a syntax-quote binds), per call at run
time. A string names the prefix, an integer suffix spells itself (`(gensym 5)` is
`#:G5`). Also names a function value.

```clojure
(println (= (gensym "g") (gensym "g"))) ; false
```
