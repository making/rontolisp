# clojure.string/includes?

`(clojure.string/includes? s sub)`

Answers whether the literal string `sub` occurs anywhere in `s`. Works as a function value too.

```clojure
(println (clojure.string/includes? "hi" "i")) ; true
(println (clojure.string/includes? "hi" "z")) ; false
```
