# re-matcher

`(re-matcher pattern s)`

Answers a matcher of `pattern` over `s`: each `re-find` of it advances past
the last match (an empty match advancing one character, like the oracle), and
`re-groups` reads the last match back. A pattern only -- a string signals,
like the oracle. Works as a function value too.

```clojure
(let [m (re-matcher #"\w+" "the quick brown fox")]
  (loop [match (re-find m)]
    (when match
      (println match)
      (recur (re-find m)))))
```
