# ref-max-history

`(ref-max-history ref)` / `(ref-max-history ref n)`

`ref` の max history（作成・設定がなければ `10`）。2 引数では `n`（oracle の `intCast` なので `1.5` は `1`）を格納して `ref` を返す。ref 以外は oracle のキャスト失敗（`ClassCastException`、`nil` は `NullPointerException`）。値としては 1 引数または 2 引数を取る。

```clojure
(def account (ref 0))
(println (ref-max-history account)) ; 10
(ref-max-history account 3)
(println (ref-max-history account)) ; 3
```
