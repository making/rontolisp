# dec

`(dec x)`

One less. Works as a function value, like `inc`. A direct call wraps mod 2^64 while
`*unchecked-math*` is truthy; the function value keeps the plain verb on every flag.

```clojure
(println (dec 5)) ; 4
```
