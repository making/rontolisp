# clojure.string/upper-case

`(clojure.string/upper-case s)`

`String.toUpperCase` と同じに大文字にした `s` を返すため、1 文字が複数の文字になることがあります。`alias/var` や referred な裸の `upper-case` としても到達し、関数値としても動くため、`map` を渡れます。

```clojure
(println (clojure.string/upper-case "hi")) ; HI
(println (map clojure.string/upper-case ["a" "b"])) ; (A B)
(println (clojure.string/upper-case "straße")) ; STRASSE
```
