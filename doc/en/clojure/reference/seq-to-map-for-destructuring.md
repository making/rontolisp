# seq-to-map-for-destructuring

`(seq-to-map-for-destructuring s)`

The map the keyword arguments `s` stand for, like the oracle's: two members or more are
key-value pairs, the last of a key winning, and an odd last member is conj'd onto them
like onto a map (a map or a `[k v]` vector merges in, anything else is an
`IllegalArgumentException`); one member is that member itself; none the empty map.

A map pattern reads every seq (`seq?`) through it, so `& {:keys [a b]}` takes `:a 1 :b 2`,
`{:a 1 :b 2}` or `:a 1 {:b 2}`; a vector is read as itself.

```clojure
(prn (seq-to-map-for-destructuring '(:a 1 :b 2 :a 3))) ; {:a 3, :b 2}
(prn (seq-to-map-for-destructuring (list {:a 1})))    ; {:a 1}
(defn opts [x & {:keys [a b] :or {b 9}}] [x a b])
(prn (opts 1 :a 2) (opts 1 {:a 3}) (opts 1 :a 2 {:b 5})) ; [1 2 9] [1 3 9] [1 2 5]
```
