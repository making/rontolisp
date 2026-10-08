# clojure.walk

Generic traversal of nested data. `clojure.walk` is loaded before the program, as in Clojure,
so `clojure.walk/postwalk` works without a `require`; `(require '[clojure.walk :as walk])`
gives it an alias. It is Clojure source written for rontolisp from the documented behavior
of Clojure's namespace, and runs the same on every backend.

| Var | Behavior |
|---|---|
| `walk` | `(walk inner outer form)`: `inner` on each member of `form`, a value of `form`'s kind rebuilt from the answers, and `outer` on that |
| `postwalk` | `(postwalk f form)`: depth first, `f` on each value after its members were replaced |
| `prewalk` | `(prewalk f form)`: depth first, `f` on each value before descending into what `f` answered |
| `postwalk-replace`, `prewalk-replace` | `(postwalk-replace smap form)`: each value that is a key of `smap` replaced by its value |
| `keywordize-keys`, `stringify-keys` | String keys made keywords, keyword keys made strings (the name), in every map at any depth |
| `postwalk-demo`, `prewalk-demo` | Print each value visited as `Walked: x`, answering the form |
| `macroexpand-all` | Every seq in a form macroexpanded, outermost first |

```clojure
(clojure.walk/postwalk #(if (number? %) (inc %) %) [1 '(2 [3]) #{4}])
; => [2 (3 [4]) #{5}]
(require '[clojure.walk :as walk])
(walk/keywordize-keys {"user" {"name" "ann"}})
; => {:user {:name "ann"}}
(walk/postwalk-replace '{x 1} '(+ x (* x 2)))
; => (+ 1 (* 1 2))
(walk/prewalk #(if (vector? %) (vec (reverse %)) %) [[1 2] [3 4]])
; => [[4 3] [2 1]]
```

A rebuilt collection keeps its kind: a list stays a list and a lazy seq is realized, a
record keeps its type and the keys beyond its fields, a sorted collection its comparator,
and every value its metadata. A map's members are its `[key value]` entries.
`keywordize-keys` and `stringify-keys` answer every map as a hash map.

## Differences

- `macroexpand-all` expands the program's own macros. The core forms (`when`, `->`, ...)
  are no macros here, so they stay as written, where Clojure answers their expansion
  ([macroexpand](macroexpand.md)).
- A map entry is a plain two-element vector ([Deviations](../deviations.md)), so a
  function testing `map-entry?` during a walk takes every pair vector for an entry.
