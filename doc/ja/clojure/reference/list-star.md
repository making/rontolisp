# list*

`(list* x... coll)` / `(list* coll)`

先頭の引数の並びに最後の引数の seq ビューを続けたリストを返します。引数 1 つならその seq です（コレクション以外はシグナル）。seq ビューへの `cons` の右畳み込みです。

```clojure
(println (list* 1 2 [3 4])) ; (1 2 3 4)
(println (list* '(1 2)))    ; (1 2)
```
