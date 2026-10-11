# re-matcher

`(re-matcher pattern s)`

Answers a matcher of `pattern` over `s`: each `re-find` of it advances past
the last match (an empty match advancing one character, like the oracle), and
`re-groups` reads the last match back, and `nth` reads one group of it. A pattern only -- a string signals,
like the oracle. Works as a function value too.

A matcher answers `java.util.regex.Matcher`'s methods on every backend: `.find` (of none or a
start index, which resets it first), `.lookingAt` (a match at the start) and `.matches` (one
spanning the whole input) record a match the next `.find` starts past; `.group`, `.start` and
`.end` (of the whole match or a group) read it back, `nil` and `-1` for a group that took no
part; `.groupCount`, `.reset` and `.pattern`. Without a match they throw the oracle's
`IllegalStateException` (`No match found`), past the pattern's groups its
`IndexOutOfBoundsException` (`No group n`). `.hitEnd` and the region methods are refused by
name.

```clojure
(let [m (re-matcher #"\w+" "the quick brown fox")]
  (loop [match (re-find m)]
    (when match
      (println match)
      (recur (re-find m)))))
```
