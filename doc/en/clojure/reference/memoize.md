# memoize

`(memoize f)`

Answers `f` cached behind an `equal` table, so repeated arguments run once --
the argument list keys by `=`. The table lives in the closure. As a value
a one-argument lambda over the same cache.

```clojure
(def memo-c (atom 0))
(def memo-f (memoize (fn [x] (swap! memo-c inc) (* x 2))))
(println [(memo-f 21) (memo-f 21) @memo-c]) ; [42 42 1]
```
