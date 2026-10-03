# c44. Clojure: `conj` onto a hash map refuses a sorted map and a seq of entries, the oracle takes them

Difficulty: Low

Measured 2026-10-03 against `clj` 1.12.6 on the interpreter: each of these answers `{1 2}` in the oracle and signals
`conj needs a map entry` here.

```clojure
(conj {} (sorted-map 1 2))
(conj {} (seq {1 2}))
(conj {} (map identity {1 2}))   ; a seq of real map entries
(into {} (seq {1 2}))            ; into is a fold of conj over the members, the entries are vectors: this one works
```

A seq (or lazy seq) of map entries is a valid direct item of a map `conj` (`APersistentMap.cons` walks any seq whose
members are `Map.Entry`), and so is a sorted map (a `Map`). `ClojureCollectionLowering.entryPlist` accepts a hash
map, a two-vector, a set and nil only; the sorted map is a cons wrapper and a seq is a list of two-vectors.

A map entry is a plain two-vector here, so a seq of vectors cannot be told from a seq of entries
(`(conj {} (seq [[1 2]]))` is a `ClassCastException` in the oracle): decide, as for a set of vectors, and record the
choice in `.kb/clojure-frontend.md`. A sorted-map item needs no such choice.

The `(k v)` list arm that used to take a two-member list also took `(seq {1 2 3 4})` as the single pair
`[1 2] -> [3 4]`; that wrong answer is gone, the refusal above is what remains.
