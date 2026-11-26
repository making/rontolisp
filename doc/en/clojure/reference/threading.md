# Threading

The threading macros, rewritten as datums around one temporary. A step over a collection literal signals (collections are not functions here).

| Name | Example | Result |
|---|---|---|
| `->` | `(-> 5 inc (- 1))` | `5` |
| `->>` | `(->> [1 2 3] (map inc) (reduce + 0))` | `9` |
| `as->` | `(as-> 4 x (+ x 1) (* x 2))` | `10` |
| `doto` | `(doto 1 inc inc)` | `1` |
| `cond->` | `(cond-> 1 true inc false (* 100))` | `2` |
| `cond->>` | `(cond->> [1 2] true (map inc))` | `(2 3)` |
| `some->` | `(some-> nil inc)` | `nil` |
| `some->>` | `(some->> [1 2] (map inc) (reduce + 0))` | `5` |
