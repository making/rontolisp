# subs

`(subs s start)`
`(subs s start end)`

Answers the substring of `s` from `start` to `end` (default the length). The core two-/three-
argument form, listed with the string library; as a value it is the two- or three-argument
lambda.

```clojure
(println (subs "hello" 1 3)) ; el
(println (subs "hello" 1)) ; ello
```
