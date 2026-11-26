# print-simple

`(print-simple x w)`

`x` printed on the writer `w` as `print` prints it, answering `nil`, like the oracle's. A
program `print-method` applies; a writer that is no stream is a `ClassCastException`. As a
value two arguments.

```clojure
(println (with-out-str (print-simple :a *out*))) ; :a
```
