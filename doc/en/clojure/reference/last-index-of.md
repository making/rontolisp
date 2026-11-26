# clojure.string/last-index-of

`(clojure.string/last-index-of s sub)`
`(clojure.string/last-index-of s sub from)`

Answers the index of the last occurrence of the literal string `sub` in `s`, `-1` when missing.
Works as a function value too.

```clojure
(println (clojure.string/last-index-of "hihi" "i")) ; 3
```
