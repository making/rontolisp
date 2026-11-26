# memoize

`(memoize f)`

`equal` テーブルでキャッシュした `f` を返します。同じ引数は一度だけ実行されます
（引数リストが `=` でキーになります）。テーブルはクロージャ内にあります。値としては同じ
キャッシュの1引数ラムダです。

```clojure
(def memo-c (atom 0))
(def memo-f (memoize (fn [x] (swap! memo-c inc) (* x 2))))
(println [(memo-f 21) (memo-f 21) @memo-c]) ; [42 42 1]
```
