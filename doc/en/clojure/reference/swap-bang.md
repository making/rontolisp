# swap!

`(swap! atom f x...)`

Applies `f` to the atom's value and the extra arguments and stores the answer, answering
the new value. Works as a function value too, so it can travel through `map`/`reduce`.

```clojure
(def a (atom 1))
(println (swap! a + 10 20)) ; 31
(println @a)                ; 31
```

Misuse of a non-atom signals. There is no `add-watch`/`remove-watch`: watches are
refused by name.
