# byte

`(byte x)`

Answers the truncation toward zero of a number or a character's code, when it lies in -128..127;
otherwise `Value out of range for byte: 200`, like the oracle. A double is compared before it is
truncated, so `(byte 127.9)` is out of range. A non-number signals. As a value a one-argument
function.

```clojure
(println (byte 1.9) (byte \a)) ; 1 97
```
