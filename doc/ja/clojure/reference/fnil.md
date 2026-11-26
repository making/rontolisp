# fnil

`(fnil f x)` / `(fnil f x y)` / `(fnil f x y z)`

`nil` の第1（第2、第3）引数を `x`（`y`、`z`）に置き換えてから `f` を呼ぶ関数を返します。
`false` は置き換えません。返す関数はオラクル同様、デフォルトの数以上の引数を必要とします。
値としては関数と1〜3個のデフォルトを取ります。

```clojure
(println ((fnil inc 0) nil)) ; 1
(println ((fnil + 1 2) nil nil 3)) ; 6
```
