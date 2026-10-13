# *'

`(*' x...)`

オーバーフローチェック付きの積。整数はここでは多倍長整数なので `*` と同じで、オーバーフローせず、`(*')` は `1`。数値以外は oracle と同じクラスで拒否される（`nil` は `NullPointerException`、それ以外は `ClassCastException`）。値としては任意個の引数を取る。

```clojure
(println (*' 2 3 4)) ; 24
(println (reduce *' [2 3 4])) ; 24
```
