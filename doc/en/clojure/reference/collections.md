# Maps, sets and vectors

A map or set is an `equal` hash table, never mutated in place: every verb builds a fresh one, so persistence holds observably. Map/set iteration order is the table's walk order, unspecified. Vector and table keys compare by identity.

| Name | Example | Result |
|---|---|---|
| `vector` | `(vector 1 2)` | `[1 2]` |
| `vector?` | `(vector? [1])` | `true` |
| `hash-map` | `(hash-map :a 1)` | `{:a 1}` |
| `array-map` | `(array-map :a 1)` | `{:a 1}` |
| `assoc` | `(assoc {:a 1} :b 2)` | `{:a 1, :b 2}` |
| `dissoc` | `(dissoc {:a 1} :a)` | `{}` |
| `get` | `(get {:a 1} :b :none)` | `:none` |
| `contains?` | `(contains? {:a 1} :a)` | `true` |
| `keys` | `(keys (hash-map :a 1))` | `(:a)` |
| `vals` | `(vals (hash-map :a 1))` | `(1)` |
| `merge` | `(merge {:a 1} {:b 2})` | `{:a 1, :b 2}` |
| `conj` | `(conj [1 2] 3)` | `[1 2 3]` |
| `disj` | `(disj #{1 2} 1)` | `#{2}` |
| `set` | `(set [1 2])` | `#{1 2}` |
