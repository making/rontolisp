# inc'

`(inc' x)`

オーバーフローチェック付きの +1。整数はここでは多倍長整数なので `inc` と同じで、オーバーフローしない。数値以外は oracle と同じクラスで拒否される（`nil` は `NullPointerException`、それ以外は `ClassCastException`）。値としては 1 引数を取る。

```clojure
(println (inc' 5)) ; 6
(println (map inc' '(1 2))) ; (2 3)
```
