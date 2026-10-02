# some-fn

`(some-fn p q...)`

与えた述語と引数の組で最初に truthy な `(p x)` を返す関数を返します。見つからない
ときはオラクルと同じものを返します。述語が1〜2個で引数が3個以下なら最後に試した `(p x)`
（`false` か `nil`）、それ以外は `nil` です。値としては1個以上の述語を取ります。

```clojure
(println ((some-fn :a :b) {:b 2})) ; 2
(println ((some-fn odd? pos?) -2 -4)) ; false
```
