# print-dup

`(print-dup x w)`

`x` printed on the writer `w` as `pr` prints it, answering `nil` -- except an array map
(eight or fewer entries) and the sorted collections, which print as the oracle's readable
`#=` constructions. A program `print-method` never applies (the oracle's `print-dup` is its
own multimethod); a record, a queue and every other value print as `pr` prints them, where
the oracle prints their `toString`. A writer that is no stream is a `ClassCastException`.
As a value two arguments.

```clojure
(println (with-out-str (print-dup {:a 1} *out*))) ; #=(clojure.lang.PersistentArrayMap/create {:a 1})
```
