# if-some

`(if-some [p e] then)` / `(if-some [p e] then else)`

Like `if-let`, but only `nil` fails the test: `false` binds and takes the then branch.
The pattern destructures only in the then branch; the else branch sees the names as
they were outside. Without an else, `nil`.

```clojure
(println (if-some [x false] [:then x] :else)) ; [:then false]
(println (if-some [x nil] [:then x] :else))   ; :else
```
