# +

`(+ x...)`

Sums the arguments, left to right; `(+)` is `0`. Exactness follows the arguments: integers
and ratios stay exact, a double makes the answer a double. While `*unchecked-math*` is
truthy (`true` or `:warn-on-boxed`), a sum of 64-bit integers wraps mod 2^64 instead of
promoting; a boxed-math warning is never emitted. As a value the plain verb is used on
every flag.

```clojure
(println (+ 1 2 3)) ; 6
(println (+ 1/2 1/3)) ; 5/6
(println (+)) ; 0
```
