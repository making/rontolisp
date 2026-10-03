# biginteger

`(biginteger x)`

The same as `bigint`: a number truncated toward zero, or a decimal string parsed, as an integer. As a value a one-argument function.

```clojure
(println (biginteger 1.5) (biginteger "12")) ; 1 12
```
