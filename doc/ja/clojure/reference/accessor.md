# accessor

`(accessor struct key)`

構造体マップから `key` を読む関数。oracle と同じ。構造体定義以外には `ClassCastException`、定義にないキーには `IllegalArgumentException`（`Not a key of struct`）。値としては 2 引数を取る。

```clojure
(defstruct account :id :balance)
(println ((accessor account :balance) (struct account 9 100))) ; 100
```
