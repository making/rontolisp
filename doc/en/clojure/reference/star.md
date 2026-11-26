# *

`(* x...)`

Multiplies the arguments; `(*)` is `1`. Exactness follows the arguments, like `+`. While
`*unchecked-math*` is truthy (`true` or `:warn-on-boxed`), a product of 64-bit integers
wraps mod 2^64 instead of promoting; a boxed-math warning is never emitted. As a value
the plain verb is used on every flag.

```clojure
(println (* 2 3 4)) ; 24
(println (* 1/2 4)) ; 2
```
