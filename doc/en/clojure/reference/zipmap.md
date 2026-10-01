# zipmap

`(zipmap keys vals)`

Answers a fresh map pairing each key with its value, stopping at the shorter
side, like the oracle. Later keys win. As a value a two-argument lambda.

```clojure
(println (zipmap [:a] [1 2])) ; {:a 1}
```
