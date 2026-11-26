# not-empty

`(not-empty coll)`

Answers `coll` itself when its seq has a member, else `nil`. A value with no seq
(a number, say) signals, like the oracle. As a value a one-argument function.

```clojure
(prn (not-empty [1])) ; [1]
(prn (not-empty "")) ; nil
```
