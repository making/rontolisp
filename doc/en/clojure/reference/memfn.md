# memfn

`(memfn name arg...)`

A lambda over the instance call: `(memfn m a...)` takes a target plus the named arguments
and calls `(m target a...)` on it, so `map`/`apply` take it where a method name alone
cannot travel. A string target takes the mapped core operation, like any instance call.
Runs on the interpreter and the JVM; a mapped string operation runs on all four backends.

```clojure
(println ((memfn toUpperCase) "hi")) ; HI
(println ((memfn substring s e) "hello" 1 2)) ; e
(println (map (memfn length) ["a" "bb"])) ; (1 2)
```
