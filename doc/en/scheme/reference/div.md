# /

`(/ z)` `(/ z1 z2 ...)`

With one argument, returns its reciprocal; with more, divides the first by the rest, left to right. Dividing exact integers gives an exact ratio when the division is not even. An exact division by zero signals an error; a division involving a flonum follows IEEE 754 (`(/ 1 0.0)` is `+inf.0`).

```scheme
(/ 1 3) ; => 1/3
(/ 6 3) ; => 2
(/ 7.0 2) ; => 3.5
```
