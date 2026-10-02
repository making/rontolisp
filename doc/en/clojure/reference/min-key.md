# min-key

`(min-key k x y...)`

Answers the value whose `(k value)` is the least; of equal keys, the last wins, like
the oracle. `k` runs once per value, and not at all for a lone value. The keys must be
numbers. As a value it takes `k` and one or more values.

```clojure
(prn (min-key count "abc" "d" "ef")) ; "d"
(prn (min-key count "ab" "cd")) ; "cd"
```
