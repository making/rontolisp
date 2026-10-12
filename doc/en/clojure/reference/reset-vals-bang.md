# reset-vals!

`(reset-vals! atom v)`

`reset!`, answering `[old new]`: the value `v` replaced and `v`. The validator checks `v`
first and the watches run after the store. Works as a function value.

```clojure
(def a (atom 1))
(println (reset-vals! a 5))
(println @a)
```

```
[1 5]
5
```
