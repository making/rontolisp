# every-pred

`(every-pred p q...)`

与えた全ての述語が全ての引数で成り立つとき `true`（引数なしなら `true`）、そうでなければ
`false` を返す述語を返します。値としては1個以上の述語を取ります。

```clojure
(println ((every-pred odd? pos?) 1 3 5)) ; true
(println ((every-pred odd? pos?) 1 -3)) ; false
```
