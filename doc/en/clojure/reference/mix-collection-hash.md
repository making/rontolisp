# mix-collection-hash

`(mix-collection-hash hash-basis count)`

`clojure.core/mix-collection-hash`: Murmur3's final mix of a collection's combined hash
`hash-basis` over its member `count`, the last step of
[hash-ordered-coll](hash-ordered-coll.md) and [hash-unordered-coll](hash-unordered-coll.md),
for a collection type computing its own `hasheq`. Each argument is cast like a `^long`
parameter (a double or a ratio truncates; a character, a string or `nil` is refused) and then
to an int, past whose range it is the oracle's `ArithmeticException`.

```clojure
(prn (mix-collection-hash 1 0))   ; -2017569654
(prn (= (hash [1 2])
        (mix-collection-hash (reduce #(unchecked-add-int (unchecked-multiply-int 31 %1) (hash %2)) 1 [1 2])
                             2)))   ; true
```
