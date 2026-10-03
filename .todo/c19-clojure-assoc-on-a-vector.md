# c19. `assoc` (and friends) on a vector

Difficulty: Medium

`assoc` treats its target as a hash table, so any vector fails (2026-10-03, jar at `64e014c6b`, interpreter):

```
clojure> (def a (vec (range 10)))
#'user/a
clojure> (def b (assoc a 0 :changed))
Error: MAPHASH: The value #(0 1 2 3 4 5 6 7 8 9) is not of type HASH-TABLE
```

`(assoc [0 1 2] 0 :y)`, `(assoc (vec (range 3)) 0 :z)` and a `let`-bound vector fail the same way.

Oracle (`clj` 1.12.6):

- `(assoc [0 1 2] 0 :y)` -> `[:y 1 2]`; the original vector is unchanged.
- index = count appends: `(assoc [0 1] 2 :x)` -> `[0 1 :x]`.
- index out of range or not an integer -> `IndexOutOfBoundsException` / `IllegalArgumentException` ("Key must be integer").
- multiple pairs: `(assoc [0 1 2] 0 :a 2 :c)` -> `[:a 1 :c]`.

Check the same family on vectors against the oracle and fix what fails:
`update`, `update-in`, `assoc-in`, `get-in` (nested vectors such as `(assoc-in [[0 0] [0 0]] [1 0] :x)`), `replace`, and `assoc!` if transients exist.
Also check that `assoc` on a vector stored as a map value or record field works, and that `(assoc nil 0 :x)` still builds a map.

The dispatch is chosen at run time when the target is not a literal, so do not slow down the map path.
Measure the wasm size and speed of a map-only `assoc` before and after; it should not change.
Pin the cases in `clojure-spec.yaml` on all four backends, and update the `assoc` reference pages in `doc/en` and `doc/ja`.
