# read-line

`(read-line)`

Reads the next line of `*in*` without its line terminator, answering `nil` past the
end, like the oracle. As a value, a function of no arguments.

```clojure
(println (with-in-str "p\nq" (doall (repeatedly 3 read-line)))) ; (p q nil)
```
