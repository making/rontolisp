# not=

`(not= x...)`

同じ隣接連鎖への `=` の否定です。隣接するペアの一部が異なるとき `true` になります。

```clojure
(println (not= 1 2)) ; true
(println (not= 1 2 1)) ; true
(println (not= 1 1)) ; false
```
