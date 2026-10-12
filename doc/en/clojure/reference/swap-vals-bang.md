# swap-vals!

`(swap-vals! atom f x...)`

`swap!`, answering `[old new]`: the value `f` was applied to and the value stored. The
same retry loop, validator and watches. Works as a function value.

```clojure
(def a (atom 1))
(println (swap-vals! a + 10))
(println @a)
```

```
[1 11]
11
```
