# some-fn

`(some-fn p q...)`

与えた述語と引数の組で最初に truthy な `(p x)` を返す関数を、オラクルと同じ順序で返します。
述語が1〜3個なら先頭3個の引数を述語ごとに試し、見つからないときは最後に試した `(p x)`
（`false` か `nil`）を返します。4個目以降の引数と、4個以上の述語では `nil` です。値としては
1個以上の述語を取ります。

```clojure
(println ((some-fn :a :b) {:b 2})) ; 2
(println ((some-fn odd? pos?) -2 -4)) ; false
(println ((some-fn :a :b) {:b 1} {:a 2})) ; 2
```
