# -'

`(-' x...)`

オーバーフローチェック付きの差分。整数はここでは多倍長整数なので `-` と同じで、オーバーフローしない。1 引数では符号反転、0 引数は oracle の引数個数エラー。数値以外は oracle と同じクラスで拒否される（`nil` は `NullPointerException`、それ以外は `ClassCastException`）。値としては 1 個以上の引数を取る。

```clojure
(println (-' 10 1 2)) ; 7
(println (-' 5)) ; -5
```
