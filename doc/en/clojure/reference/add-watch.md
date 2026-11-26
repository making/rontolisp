# add-watch

`(add-watch reference key f)`

Calls `(f key reference old new)` after every change of an atom, a ref, an agent or a var,
on the thread that made it, and answers the reference. A watch of an atom runs after each
`swap!`, `reset!` (the same value too), successful `compare-and-set!` and `-vals` verb; of a
ref once per committed transaction that wrote it, `old` being its value before the
transaction; of an agent after each action (an `await` marker too, with `old` equal to
`new`); of a var after an `alter-var-root`. A second watch of the same key replaces the
first, and several run in the order of Clojure's watch map. A watch that throws throws
from the verb, the value already stored. `remove-watch` takes one off. Works as a function
value.

```clojure
(def a (atom 0))
(add-watch a :log (fn [k r old new] (println k old "->" new)))
(swap! a inc)
(reset! a 10)
(remove-watch a :log)
(swap! a inc)
(println @a)
```

```
:log 0 -> 1
:log 1 -> 10
11
```

A var's watches also run when a later `def`, `defn`, `defmacro`, `deftest` or `with-redefs`
replaces its root, like the oracle's `bindRoot` ([Deviations](../deviations.md)).
