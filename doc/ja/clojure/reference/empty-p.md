# empty?

`(empty? coll)`

`coll` が空かどうかを返します。表・ベクター・文字列を認識します。`nil`、false オブジェクト、空のマップ・セット・ベクター・文字列が空です。`T` か false を返します。

仕様との差異: `(empty? false)` はここでは `true` を返します。オラクルはシグナルを上げます。

```clojure
(println (empty? '()))    ; true
(println (empty? ""))     ; true
(println (empty? {}))     ; true
(println (empty? #{}))    ; true
(println (empty? [1]))    ; false
```
