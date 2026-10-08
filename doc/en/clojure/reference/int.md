# int

`(int x)`

The oracle's `int` cast: a number truncated toward zero, a character's code. A value
outside the int range signals as the oracle's does (`integer overflow`, or `Value out of
range for int: 1.0E10` for a double literal), NaN is `0`, and a non-number signals. As a
value a one-argument function.

```clojure
(println (int 2.7)) ; 2
```
