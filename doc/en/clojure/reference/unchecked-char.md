# unchecked-char

`(unchecked-char x)`

Answers a character itself, or the character whose code is the low 16 bits of a number (a double truncates, saturating at the 64-bit range). `nil` and other non-numbers signal. As a value a one-argument function.

```clojure
(println (unchecked-char 97) (unchecked-char 97.5) (unchecked-char 65633)) ; a a a
```
