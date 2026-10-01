# re-find

`(re-find m)`
`(re-find pattern s)`

Answers the first match: of the matcher `m` (advancing it, like the oracle),
or of `pattern` in `s`. Without groups the match string; with groups a vector
of the whole plus every group (`nil` past a group that never matched); no match
is `nil`. Works as a function value too.

```clojure
(println (re-find #"a+" "aaab")) ; aaa
(println (re-find #"a+" "c")) ; nil
(println (re-find #"(\\w+)@(\\w+)" "user@host")) ; [user@host user host]
```
