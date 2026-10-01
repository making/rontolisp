# rest

`(rest coll)`

`coll` の seq ビューから最初の要素を除いたものを返します。空への `rest` は `nil` で、決してシグナルを上げません。oracle の全域的な `rest` と一致します。

Deviation: 空コレクションの `rest` は `nil` を返します。oracle は `()` を表示します。

```clojure
(println (rest '(1 2 3))) ; (2 3)
(println (rest [1 2 3]))  ; (2 3)
(println (rest []))       ; nil
```
