# c45. Clojure: `merge` refuses an entry vector and a seq of entries after the first map, the oracle conjoins them

Difficulty: Low

Measured 2026-10-03 against `clj` 1.12.6 and the interpreter (`merge` is `conj` folded over the maps):

```clojure
(merge {} (seq {1 2}))          ; oracle {1 2}, here refused
(merge {} [1 2])                ; oracle {1 2}, here refused
(merge (sorted-map) (seq {1 2})) ; oracle {1 2}, here refused
(merge {} (sorted-map 1 2))     ; {1 2} on both
```

`ClojureCollectionLowering.mergeOf`/`mergeValue` read each later item through `entriesPlist` (a sorted map's pairs or
a table's), so only maps and records join. `conjOf`'s `entryPlist` now takes nil, a map, a sorted map, a `[k v]`
vector, a set of vectors and a seq of entries (`%clojure-seq-entry-plist`); route `merge`'s later items through the same
arms (a record keeps contributing its entries), on the interpreter's sorted path too (`merge` over a sorted first map).
Pin with clojure-spec on all four backends, record the change in `.kb/clojure-frontend.md` and
`doc/{en,ja}/clojure/reference/merge.md`.
