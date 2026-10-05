# subs

`(subs s start)`
`(subs s start end)`

Answers the substring of `s` from `start` to `end` (default the length). The core two-/three-
argument form, listed with the string library; as a value it is the two- or three-argument
lambda.

A double or ratio bound is truncated toward zero, like the oracle's (`(subs "hello" 1.9)` is `"ello"`); `.substring` and `.charAt` do the same. A bound outside the string throws `StringIndexOutOfBoundsException`.

```clojure
(println (subs "hello" 1 3)) ; el
(println (subs "hello" 1)) ; ello
(println (subs "hello" 1.9)) ; ello
```
