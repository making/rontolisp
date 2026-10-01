# prefer-method

`(prefer-method multifn val1 val2)`

Records that `val1` wins over `val2` when the dispatch search finds both as candidate ancestors
and neither is strictly more specific. Answers the multimethod. Without it, such a tie signals
`Multiple methods ...`.

```clojure
(defmulti p :k)
(defmethod p :x [m] 1)
(defmethod p :y [m] 2)
(derive :x :y)
(prefer-method p :x :y)
(println (p {:k :x})) ; 1
```
