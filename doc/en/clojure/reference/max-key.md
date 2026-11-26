# max-key

`(max-key k x y...)`

Answers the value whose `(k value)` is the greatest; of equal keys, the last wins,
like the oracle. `k` runs once per value, and not at all for a lone value. The keys
must be numbers. As a value it takes `k` and one or more values.

```clojure
(prn (max-key count "abc" "d" "ef")) ; "abc"
(prn (apply max-key count ["a" "bbb" "cc"])) ; "bbb"
```
