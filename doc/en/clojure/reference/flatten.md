# flatten

`(flatten x)`

Answers the members of the nested sequential collections of `x` (lists, vectors and
seqs) that are no sequential collection, depth first, as a lazy seq -- the oracle's
`(filter (complement sequential?) (rest (tree-seq sequential? seq x)))`. A map or set
stays one member; `x` that is no sequential collection answers `()`. Also names a
function value.

```clojure
(println (flatten [1 [2 [3 '(4)]] 5])) ; (1 2 3 4 5)
(println (flatten [{:a 1} #{2}])) ; ({:a 1} #{2})
(println (take 3 (flatten (repeat [1 [2]])))) ; (1 2 1)
```
