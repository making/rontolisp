# printf

`(printf fmt & args)`

The `format` of `fmt` and `args` printed to `*out*`, answering `nil`, like the oracle's. As
a value one or more arguments.

```clojure
(println (with-out-str (printf "%d %s" 42 "hi"))) ; 42 hi
```
