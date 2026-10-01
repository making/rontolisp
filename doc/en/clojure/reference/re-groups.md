# re-groups

`(re-groups m)`

Answers the matcher `m`'s last match as a vector (the whole first), like
`re-find` with groups. Past no match it signals `No match found`, like the
oracle. Works as a function value too.

```clojure
(let [m (re-matcher #"(\\w+)@(\\w+)" "user@host")]
  (println (re-find m))
  (println (re-groups m)))
```
