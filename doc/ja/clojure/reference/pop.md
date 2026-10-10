# pop

`(pop coll)`

ベクターからは最後の要素を、リストと[キュー](persistent-queue.md)からは先頭を除いたものを返します。
`nil` には `nil`、空のキューにはそのキュー自身です。空ベクターはオラクル同様 `Can't pop empty vector` をシグナルし、文字列・マップ・
セット・lazy seq もシグナルします。1要素のリストの pop は `nil` です（オラクルは `()`）。
値としては1引数の関数です。

```clojure
(println (pop [1 2 3])) ; [1 2]
(println (pop '(1 2 3))) ; (2 3)
```
