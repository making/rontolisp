# re-seq

`(re-seq pattern s)`

Answers every match of `pattern` in `s` as a seq (the oracle answers lazy,
which prints the same): each the `re-find` shape, so groups widen to vectors.
No match is `nil`. Works as a function value too.

```clojure
(println (re-seq #"a+" "aaabbaa")) ; (aaa aa)
(println (re-seq #"z" "abc")) ; nil
```
