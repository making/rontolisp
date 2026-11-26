# cons

`(cons x coll)`

`coll` の先頭に `x` を付けた seq を返します。任意のコレクションは seq ビューを経由して強制されます。`nil` への `cons` はリストを作ります。`coll` が lazy の場合、答えは lazy seq のままになります。strict な cons が lazy な tail を持つことはありません。

```clojure
(println (cons 1 [2 3])) ; (1 2 3)
(println (cons 0 '(1 2))) ; (0 1 2)
(println (cons :a nil))  ; (:a)
(println (take 3 (cons 99 (iterate inc 0)))) ; (99 0 1)
```
