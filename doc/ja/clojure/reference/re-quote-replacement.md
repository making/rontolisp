# clojure.string/re-quote-replacement

`(clojure.string/re-quote-replacement s)`

`s` の中のすべての `\` と `$` を quote して返します。答えはパターン置換の中で文字通りに置換されます（`\` は quote、`$n` はグループ読みになります）。

```clojure
(println (clojure.string/re-quote-replacement "a$b")) ; a\$b
(println (clojure.string/re-quote-replacement "$1")) ; \$1
(println (clojure.string/replace "abc" #"(b)" (clojure.string/re-quote-replacement "$1"))) ; a$1c
```
