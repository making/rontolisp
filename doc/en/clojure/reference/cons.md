# cons

`(cons x coll)`

Answers a list with `x` prepended to the seq view of `coll`; the view is materialised,
so any collection works. `cons` onto `nil` builds a list.

```clojure
(println (cons 1 [2 3])) ; (1 2 3)
(println (cons 0 '(1 2))) ; (0 1 2)
(println (cons :a nil))  ; (:a)
```
