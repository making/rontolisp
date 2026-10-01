# clojure.string/starts-with?

`(clojure.string/starts-with? s sub)`

Answers whether `s` begins with the literal string `sub`. Works as a function value too.

```clojure
(println (clojure.string/starts-with? "hi" "h")) ; true
(println (clojure.string/starts-with? "hi" "i")) ; false
```
