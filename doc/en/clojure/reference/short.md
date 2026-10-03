# short

`(short x)`

Answers the truncation toward zero of a number or a character's code, when it lies in
-32768..32767; otherwise `Value out of range for short: 70000`, like the oracle. A double is
compared before it is truncated. A non-number signals. As a value a one-argument function.

```clojure
(println (short 1.9) (short -32768)) ; 1 -32768
```
