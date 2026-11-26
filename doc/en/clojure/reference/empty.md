# empty

`(empty coll)`

`clojure.core/empty`: an empty collection of the same kind as `coll`. A vector, map or set
answers a fresh empty one; a sorted map or set answers an empty one under the same
comparator; each carries `coll`'s metadata. A string, `nil` and any value that is no
collection answer `nil`; a record signals (`Can't create empty: ...`). As a value a
one-argument function.

Deviation: a list, a lazy seq and a seq answer `nil`, where the oracle answers `()` (the
empty-as-`nil` position of `rest` and `next`), so they carry no metadata; a map entry
answers `[]` where the oracle answers `nil`.

```clojure
(prn (empty [1 2]) (empty {:a 1}) (empty "ab")) ; [] {} nil
(prn (conj (empty (sorted-set-by > 1 2)) 1 3 2)) ; #{3 2 1}
```
