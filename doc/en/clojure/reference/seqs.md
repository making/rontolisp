# Seqs

A seq is a strict list view over any collection: lists pass through untouched, vectors and strings coerce, maps contribute one two-vector per entry and sets one member per element (in the table's walk order, unspecified). There is no laziness; the empty result of every verb is `nil`.

| Name | Example | Result |
|---|---|---|
| `seq` | `(seq [1 2 3])` | `(1 2 3)` |
| `first` | `(first {:a 1})` | `[:a 1]` |
| `rest` | `(rest '(1 2 3))` | `(2 3)` |
| `next` | `(next [1])` | `nil` |
| `cons` | `(cons 1 [2 3])` | `(1 2 3)` |
| `list*` | `(list* 1 2 [3 4])` | `(1 2 3 4)` |
| `count` | `(count {:a 1 :b 2})` | `2` |
| `empty?` | `(empty? "")` | `true` |
| `map` | `(map inc [1 2 3])` | `(2 3 4)` |
| `filter` | `(filter odd? [1 2 3 4])` | `(1 3)` |
| `reduce` | `(reduce + 0 [1 2 3])` | `6` |
| `apply` | `(apply max 1 [2 3])` | `3` |
| `concat` | `(concat [1] '(2) #{3})` | `(1 2 3)` |
| `take` | `(take 2 [1 2 3])` | `(1 2)` |
| `drop` | `(drop 2 [1 2 3])` | `(3)` |
| `range` | `(range 0 6 2)` | `(0 2 4)` |
| `nth` | `(nth [1 2 3] 5 :none)` | `:none` |
