# truncate

`(truncate number)`

Rounds `number` toward zero to an integer, discarding any fractional part. In rontolisp it takes a single argument and returns a single integer value (no optional divisor and no second remainder value as in full Common Lisp).

```lisp
(truncate 3.7) ; => 3
```

```lisp
(truncate -3.7) ; => -3
```
