# sort

`(sort coll)` / `(sort comp coll)`

seq ビューをコピーしてソートしたリストを返します。比較関数なしでは数値は `<`、文字列は
`string<`、文字は `char<`、キーワードは綴りで比べます。それ以外（混在を含む）は誤った答えの
代わりにシグナルします。比較関数ありではその答えをオラクルと同じく読みます。数値は整数部が
負なら第1引数を先にし（`compare`、`(- a b)`）、それ以外の値は真なら第1引数を先にします
（`<`、`>`）。値としては1引数か2引数のラムダです。

比較関数に `java.util.Comparator` を渡すと、その `compare` の符号で比べます。
`(java.util.Comparator/naturalOrder)` のようなホストの `Comparator` でも、それを
実装した `reify` や `deftype` でも構いません。`sort-by` と `sorted-map-by` /
`sorted-set-by` も同じ比較関数を取ります。ホストの `Comparator` を関数のように
呼び出すと、オラクル同様シグナルします。ホストの `Comparator` はインタプリタと
JVM で使え、`reify` や `deftype` のものはすべてのバックエンドで使えます。

```clojure
(println (sort [3 1 2])) ; (1 2 3)
(println (sort > [1 3 2])) ; (3 2 1)
(println (sort compare ["b" "a"])) ; (a b)
(println (sort (java.util.Comparator/naturalOrder) [3 1 2])) ; (1 2 3)
(println (sort (reify java.util.Comparator (compare [_ a b] (compare b a))) [1 3 2])) ; (3 2 1)
```
