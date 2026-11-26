# ref-min-history

`(ref-min-history ref)` / `(ref-min-history ref n)`

`ref` の min history（作成・設定がなければ `0`）。2 引数では `n`（oracle の `intCast` なので `1.5` は `1`）を格納して `ref` を返す。ref 以外は oracle のキャスト失敗（`ClassCastException`、`nil` は `NullPointerException`）。値としては 1 引数または 2 引数を取る。

```clojure
(def account (ref 0))
(println (ref-min-history account)) ; 0
(ref-min-history account 3)
(println (ref-min-history account)) ; 3
```
