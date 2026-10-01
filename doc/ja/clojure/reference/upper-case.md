# clojure.string/upper-case

`(clojure.string/upper-case s)`

すべての文字を大文字にした `s` を返します。`alias/var` や referred な裸の `upper-case` としても到達し、関数値としても動くため、`map` を渡れます。

```clojure
(println (clojure.string/upper-case "hi")) ; HI
(println (map clojure.string/upper-case ["a" "b"])) ; (A B)
```
