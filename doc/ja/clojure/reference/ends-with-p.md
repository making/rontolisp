# clojure.string/ends-with?

`(clojure.string/ends-with? s sub)`

`s` がリテラル文字列 `sub` で終わるかどうかを返します。関数値としても動きます。

```clojure
(println (clojure.string/ends-with? "hi" "i")) ; true
(println (clojure.string/ends-with? "hi" "h")) ; false
```
