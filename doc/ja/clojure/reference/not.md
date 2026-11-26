# not

`(not expr)`

`expr` が偽値なら `true`、真値なら `false` を返します。チェックは明示的な null-or-false テストなので、`nil` と `false` はどちらも偽値で、それ以外 -- `0`、空文字列、空コレクション -- はすべて真値です。

```clojure
(println (not nil))    ; true
(println (not false))  ; true
(println (not 1))      ; false
```
