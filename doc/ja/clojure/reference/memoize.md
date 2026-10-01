# memoize

`(memoize f)`

`equal` テーブルでキャッシュした `f` を返します。同じ引数は一度だけ実行されます
（引数リストが構造的にキーになります）。テーブルはクロージャ内にあります。値としては同じ
キャッシュの1引数ラムダです。

```clojure
(def b15-memo-c (atom 0))
(def b15-memo-f (memoize (fn [x] (swap! b15-memo-c inc) (* x 2))))
(println [(b15-memo-f 21) (b15-memo-f 21) @b15-memo-c]) ; [42 42 1]
```
