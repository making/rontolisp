# -

`(- number &rest numbers)`

With one argument, returns its negation. With several, subtracts the rest from the first, left to right. The result type follows the usual numeric contagion: integers stay integers, but any float argument makes the result a float, and ratios are kept exact. On the interpreter and JVM, integer results promote to big integers on overflow; the WASM backend uses 31-bit integers.

```lisp
(- 10 3) ; => 7
```

```lisp
(- 5) ; => -5
```
