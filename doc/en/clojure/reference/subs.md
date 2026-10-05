# subs

`(subs s start)`
`(subs s start end)`

Answers the substring of `s` from `start` to `end` (default the length). The core two-/three-
argument form, listed with the string library; as a value it is the two- or three-argument
lambda.

A double or ratio bound is truncated toward zero, like the oracle's (`(subs "hello" 1.9)` is `"ello"`); `.substring` and `.charAt` do the same. A bound outside the string throws `StringIndexOutOfBoundsException`; a bound that is not a number throws `NullPointerException` (`nil`) or `ClassCastException`, and `.substring` and `.charAt` of a receiver the compiler cannot type throw `IllegalArgumentException` for it, like the oracle's reflective call (a type hint is not read, so a hinted receiver is that too, where the oracle throws `ClassCastException`).

```clojure
(println (subs "hello" 1 3)) ; el
(println (subs "hello" 1)) ; ello
(println (subs "hello" 1.9)) ; ello
```
