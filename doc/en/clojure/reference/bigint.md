# bigint

`(bigint x)`

Answers a number truncated toward zero to an integer, or a decimal string parsed. NaN, an infinity, `nil`, a character or a malformed string signal, like the oracle. There is no separate big-integer type: every integer is arbitrary precision, so the result prints without the `N` suffix. As a value a one-argument function.

```clojure
(println (bigint 1.5) (bigint 7/2) (bigint "123")) ; 1 3 123
```
