# peek

`(peek coll)`

ベクターなら最後の要素を、リストと[キュー](persistent-queue.md)なら先頭を返します。`nil`・空ベクター・
空のキューには `nil` です。
文字列・マップ・セット・lazy seq はオラクル同様にシグナルします（ここでは strict な seq
はリストなので先頭を見ます）。値としては1引数の関数です。

```clojure
(println (peek [1 2 3])) ; 3
(println (peek '(1 2 3))) ; 1
```
