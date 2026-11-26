# atom

`(atom v & options)`

Wraps `v` in a cell every state verb reads and writes; `(deref a)` and `@a` read it, the
reader form the same operation. The verb set is `deref`/`swap!`/`reset!`/`compare-and-set!`
plus `swap-vals!`/`reset-vals!`; every one works as a function value, so
`(map deref atoms)` runs. Misuse of a non-atom signals.

The options are read like Clojure's `(apply hash-map options)`: `:meta` sets the atom's
metadata, `:validator` a function every new value must pass (`set-validator!`), which the
initial value must pass too; any other key is ignored, a key without a value refused.
Watches are added with `add-watch`.

```clojure
(def a (atom 1 :validator pos? :meta {:doc "hits"}))
(println @a (meta a))
(println (swap! a + 10 20))
(println (try (reset! a -1) (catch IllegalStateException e (ex-message e))) @a)
(println (map deref [a (atom 2)]))
```

```
1 {:doc hits}
31
Invalid reference state 31
(31 2)
```
