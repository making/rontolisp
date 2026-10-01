# clojure.string/starts-with?

`(clojure.string/starts-with? s sub)`

`s` がリテラル文字列 `sub` で始まるかどうかを返します。関数値としても動きます。

```clojure
(println (clojure.string/starts-with? "hi" "h")) ; true
(println (clojure.string/starts-with? "hi" "i")) ; false
```
