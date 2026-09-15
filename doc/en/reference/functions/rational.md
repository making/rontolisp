# rational

`(rational number)`

Returns the exact rational number the value is. Integers and ratios are already exact, so they are returned unchanged; a float answers its exact binary value. A complex or any other non-real value signals an error, as does a NaN or an infinity, which have no exact rational.

```lisp
(rational 1.5) ; => 3/2
```

```lisp
(rational 5) ; => 5
```
