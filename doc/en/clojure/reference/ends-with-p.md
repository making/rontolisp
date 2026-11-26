# clojure.string/ends-with?

`(clojure.string/ends-with? s sub)`

Answers whether `s` ends with the literal string `sub`. Works as a function value too.

```clojure
(println (clojure.string/ends-with? "hi" "i")) ; true
(println (clojure.string/ends-with? "hi" "h")) ; false
```
