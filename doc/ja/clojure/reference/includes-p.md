# clojure.string/includes?

`(clojure.string/includes? s sub)`

リテラル文字列 `sub` が `s` のどこかに現れるかどうかを返します。関数値としても動きます。

```clojure
(println (clojure.string/includes? "hi" "i")) ; true
(println (clojure.string/includes? "hi" "z")) ; false
```
