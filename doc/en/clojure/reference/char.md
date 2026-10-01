# char

`(char x)`

The character itself, or the `code-char` of the truncated code point of a number;
anything else signals, like the oracle. `int` reads it back. As a value a
one-argument lambda.

```clojure
(println (char 97)) ; a
(println (int (char 97))) ; 97
```
