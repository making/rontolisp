# clojure.string/index-of

`(clojure.string/index-of s sub)`
`(clojure.string/index-of s sub from)`

Answers the index of the first occurrence of the literal string `sub` in `s` at or after `from`
(default 0), `nil` when missing. Works as a function value too. `from` is read as Java's
`indexOf` reads it: a negative one is 0, one past the end finds nothing (an empty `sub` is
found at the end), a fractional one truncates.

```clojure
(println (clojure.string/index-of "hihi" "i")) ; 1
(println (clojure.string/index-of "hihi" "i" 2)) ; 3
(println (clojure.string/index-of "hi" "z")) ; nil
(println (clojure.string/index-of "hihi" "i" -1)) ; 1
```
