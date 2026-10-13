# ref-history-count

`(ref-history-count ref)`

`ref` に書き込んだコミット済みトランザクションの数。min history が上限（oracle が保持する履歴。作りたては `0`）。ref 以外は oracle のキャスト失敗（`ClassCastException`、`nil` は `NullPointerException`）。値としては 1 引数を取る。

```clojure
(def account (ref 0 :min-history 2))
(dosync (alter account inc))
(println (ref-history-count account)) ; 1
```
