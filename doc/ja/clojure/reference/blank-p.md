# clojure.string/blank?

`(clojure.string/blank? s)`

`s` が空、`nil`、空白のみのいずれかかどうかを返します。`nil` は oracle と同じく `true` を返します。関数値としても動きます。

```clojure
(println (clojure.string/blank? "  ")) ; true
(println (clojure.string/blank? nil)) ; true
(println (clojure.string/blank? "x")) ; false
```
