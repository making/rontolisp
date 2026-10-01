# atom

`(atom v)`

Wraps `v` in a cell every state verb reads and writes; `(deref a)` and `@a` read it, the
reader form the same operation. The verb set is `deref`/`swap!`/`reset!`/`compare-and-set!`;
every one answers the new value and works as a function value, so `(map deref atoms)` runs.
Misuse of a non-atom signals. There is no `add-watch`/`remove-watch`: watches are refused
by name.

```clojure
(def a (atom 1))
(println @a) ; 1
(println (swap! a + 10 20)) ; 31
(println (map deref [a (atom 2)])) ; (31 2)
```
