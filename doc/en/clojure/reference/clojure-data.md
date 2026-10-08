# clojure.data

Recursive comparison of two values. Require `clojure.data` to use it; it is Clojure source
written for rontolisp from the documented behavior of Clojure's namespace, and runs the same
on every backend.

| Var | Behavior |
|---|---|
| `diff` | `(diff a b)`: `[only-in-a only-in-b in-both]`, each part `nil` when empty; equal values answer `[nil nil a]` |
| `equality-partition` | `(equality-partition x)`: how `diff` compares `x`: `:map`, `:set`, `:sequential` or `:atom` |
| `diff-similar` | `(diff-similar a b)`: the diff of two values of one partition |
| `EqualityPartition`, `Diff` | The protocols behind the two functions above, which a program extends for its own types |

Two maps compare key by key and two sequential collections (vectors, lists, seqs) index by
index, each shared position compared recursively; the parts of a sequential diff are vectors
holding `nil` where the other side has the value, and a map diff answers its three parts as a
seq. Two sets compare member by member. Anything else, strings included, compares as a whole,
as do two values of different partitions. A record compares as a map.

```clojure
(require '[clojure.data :as data])
(data/diff {:name "ann" :age 30} {:name "ann" :age 31})
; => ({:age 30} {:age 31} {:name "ann"})
(data/diff [1 2 3] [1 5 3 4])
; => [[nil 2] [nil 5 nil 4] [1 nil 3]]
(data/diff #{:a :b} #{:b :c})
; => [#{:a} #{:c} #{:b}]
(data/diff {:user {:name "ann" :roles [:admin]}} {:user {:name "ann" :roles [:dev]}})
; => ({:user {:roles [:admin]}} {:user {:roles [:dev]}} {:user {:name "ann"}})
(data/diff "abc" "abd")
; => ["abc" "abd" nil]
```
