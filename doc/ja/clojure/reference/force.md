# force

`(force x)`

[delay](delay.md) `x` の値です。初回は本体を実行します。それ以外の値はそのまま返します。
値としては1引数関数です。

```clojure
(println (force (delay (+ 1 2))) (force 5) (map force [(delay :a) :b])) ; 3 5 (:a :b)
```
