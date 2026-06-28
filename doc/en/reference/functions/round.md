# round

`(round number)`

Rounds `number` to the nearest integer, using banker's rounding: a value exactly halfway between two integers rounds to the even one. In rontolisp it takes a single argument and returns a single integer value (no optional divisor and no second remainder value as in full Common Lisp).

```lisp
(round 3.5) ; => 4
```

```lisp
(round 2.5) ; => 2
```
