# -

`(- z)` `(- z1 z2 ...)`

With one argument, the negation; with more, subtracts the rest from the first, left to
right. At least one argument is required. While `*unchecked-math*` is truthy (`true` or
`:warn-on-boxed`), a difference of 64-bit integers wraps mod 2^64 instead of promoting;
a boxed-math warning is never emitted. As a value the plain verb is used on every flag.

```clojure
(println (- 10 4)) ; 6
(println (- 5)) ; -5
```
