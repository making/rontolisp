# memoize

`(memoize f)`

Answers `f` cached behind an `equal` table, so repeated arguments run once --
the argument list keys by `=`. The table lives in the closure. As a value
a one-argument lambda over the same cache.

```clojure
(def b15-memo-c (atom 0))
(def b15-memo-f (memoize (fn [x] (swap! b15-memo-c inc) (* x 2))))
(println [(b15-memo-f 21) (b15-memo-f 21) @b15-memo-c]) ; [42 42 1]
```
