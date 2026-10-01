# volatile!

`(volatile! v)`

The same cell an atom is, without the compare-and-set; `deref`/`vswap!`/`vreset!` are its
verbs, each answering the new value and working as a function value. Misuse of a non-volatile
signals, as does pointing an atom verb at a volatile or the reverse.

```clojure
(println @(volatile! 5)) ; 5
```
