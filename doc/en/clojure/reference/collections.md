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
| `update` | `(update {:a 1} :a inc)` | `{:a 2}` |
| `update-in` | `(update-in {:a {:b 1}} [:a :b] inc)` | `{:a {:b 2}}` |
| `assoc-in` | `(assoc-in {} [:a :b] 1)` | `{:a {:b 1}}` |
| `get-in` | `(get-in {:a {:b 1}} [:a :b])` | `1` |
| `select-keys` | `(select-keys {:a 1 :b 2} [:a])` | `{:a 1}` |
| `merge-with` | `(merge-with + {:a 1} {:a 2})` | `{:a 3}` |
| `into` | `(into [] [1 2])` | `[1 2]` |
| `frequencies` | `(frequencies [:a :a])` | `{:a 2}` |
| `defstruct` | `(do (defstruct s :a) (:a (struct s 1)))` | `1` |
| `struct` | `(do (defstruct s :a) (:a (struct s 1)))` | `1` |
| `struct-map` | `(do (defstruct s :a) (:a (struct-map s :a 1)))` | `1` |
