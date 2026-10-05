# empty?

`(empty? coll)`

`coll` が空かどうかを返します。表・ベクター・文字列を認識します。`nil`、空のマップ・セット・ベクター・文字列が空で、lazy seq は何も realize されないとき空です（1要素だけ realize します）。コレクションでないもの（キーワード、`false`、数）は `seq` と同じくシグナルします。オラクルと同じ `IllegalArgumentException` です。`T` か false を返します。

```clojure
(println (empty? '()))    ; true
(println (empty? ""))     ; true
(println (empty? {}))     ; true
(println (empty? #{}))    ; true
(println (empty? [1]))    ; false
```
