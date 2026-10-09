# f02. Clojure: a record's seq walks its entries backwards

Difficulty: Low

`%clojure-strict-seq` conses a record's entries off `maphash`, so they come out last field
first, where the oracle walks the declared fields in order, then the extension map. Measured
2026-10-09 (clj 1.12.6 / interpreter), `(defrecord R [a b])`:

| program | oracle | here |
|---|---|---|
| `(seq (->R 1 2))` | `([:a 1] [:b 2])` | `([:b 2] [:a 1])` |
| `(keys (->R 1 2))` / `(vals ...)` | `(:a :b)` / `(1 2)` | `(:b :a)` / `(2 1)` |
| `(into [] (->R 1 2))` | `[[:a 1] [:b 2]]` | `[[:b 2] [:a 1]]` |
| `(hash-ordered-coll (->R 1 2))` | `-1407726839` | `2007769254` |

The printer already writes the oracle's order (`#user.R{:a 1, :b 2}`).

## Plan

1. Measure the oracle's order for a record with an extension map (`assoc` of a new key,
   `map->R` with an extra key) and after `dissoc` of an extension key.
2. Walk the declared fields in order, then the rest, wherever a record is seqed (`seq`,
   `keys`, `vals`, `reduce`, `reduce-kv`, `into`), on all four backends.
