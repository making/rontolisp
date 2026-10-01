# clojure.string/index-of

`(clojure.string/index-of s sub)`
`(clojure.string/index-of s sub from)`

Answers the index of the first occurrence of the literal string `sub` in `s` at or after `from`
(default 0), `-1` when missing. Works as a function value too.

Deviation: a missing `sub` answers `-1`, where the oracle's `clojure.string/index-of` answers
`nil`.

```clojure
(println (clojure.string/index-of "hihi" "i")) ; 1
(println (clojure.string/index-of "hihi" "i" 2)) ; 3
(println (clojure.string/index-of "hi" "z")) ; -1
```
