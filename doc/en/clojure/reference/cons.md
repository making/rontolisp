# cons

`(cons x coll)`

Answers a seq with `x` prepended to `coll`; any collection coerces through the seq view,
and `cons` onto `nil` builds a list. When `coll` is lazy the answer stays a lazy seq,
so no strict cons ever holds a lazy tail.

```clojure
(println (cons 1 [2 3])) ; (1 2 3)
(println (cons 0 '(1 2))) ; (0 1 2)
(println (cons :a nil))  ; (:a)
(println (take 3 (cons 99 (iterate inc 0)))) ; (99 0 1)
```
