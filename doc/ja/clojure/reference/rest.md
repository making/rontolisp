# rest

`(rest coll)`

`coll` の seq ビューから最初の要素を除いたものを返します。空への `rest` は `nil` で、決してシグナルを上げません。オラクルの全域的な `rest` と一致します。

仕様との差異: 空コレクションの `rest` は `nil` を返します。オラクルは `()` を表示します。

```clojure
(println (rest '(1 2 3))) ; (2 3)
(println (rest [1 2 3]))  ; (2 3)
(println (rest []))       ; nil
```
