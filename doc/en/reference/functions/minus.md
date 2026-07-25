# -

`(- number &rest numbers)`

With one argument, returns its negation. With several, subtracts the rest from the first, left to right. The result type follows the usual numeric contagion: integers stay integers, but any float argument makes the result a float, and ratios are kept exact. Integer results promote to big integers on overflow, on every backend.

```lisp
(- 10 3) ; => 7
```

```lisp
(- 5) ; => -5
```
