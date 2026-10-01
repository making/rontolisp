# re-matches

`(re-matches pattern s)`

Answers the match when `pattern` matches all of `s`, else `nil`: the `re-find`
shape (a vector with groups). Works as a function value too.

```clojure
(println (re-matches #"a+" "aaa")) ; aaa
(println (re-matches #"a+" "aaab")) ; nil
```
