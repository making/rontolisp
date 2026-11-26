# condp

`(condp pred expr clause... default?)`

Evaluates `pred` and `expr` once, then tries each clause in order by calling
`(pred test expr)` with the clause's test, and answers the result of the first clause
whose call is truthy. A clause is `test result`, or `test :>> f`, which answers `f`
called with the predicate's answer. A lone trailing form is the default. Without one,
no truthy clause throws `IllegalArgumentException` `No matching clause: <expr>`.

```clojure
(defn size [n] (condp < n 100 :big 10 :medium :small))
(println (map size [500 50 5])) ; (:big :medium :small)
(println (condp some [1 2 3] #{0 6} :>> inc #{2 4} :>> dec)) ; 1
```
