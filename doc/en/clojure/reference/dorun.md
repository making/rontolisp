# dorun

`(dorun coll)` / `(dorun n coll)`

Realizes a collection for effect and answers `nil`: a lazy seq is walked to its end, a
strict collection is already realized. With a count it stops like the oracle's, after
`n + 1` members have realized. Also names a function value.

```clojure
(println (dorun [1 2 3])) ; nil
```
