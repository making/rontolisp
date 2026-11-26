# volatile?

`(volatile? x)`

`clojure.core/volatile?`: `true` for a volatile (`volatile!`); an atom, ref and agent are `false`. As a value a one-argument function.

```clojure
(println (volatile? (volatile! 1)) (volatile? (atom 1)))  ; true false
```
