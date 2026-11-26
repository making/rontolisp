# char

`(char x)`

The character itself, or the `code-char` of a number in the char range
`0..65535` -- a double refused past its edges, any other number truncated
first; a number outside it, nil, and anything else signal, like the oracle.
`int` reads it back. As a value a one-argument lambda.

```clojure
(println (char 97)) ; a
(println (int (char 97))) ; 97
```
