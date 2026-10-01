# boolean

`(boolean x)`

真の値には `true` で答えます。偽になるのは `nil` と `false` だけです（`0`・空文字列・
空コレクションは真です）。値としては1引数ラムダです。

```clojure
(println (boolean 1)) ; true
(println (boolean nil)) ; false
```
