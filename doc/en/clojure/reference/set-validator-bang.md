# set-validator!

`(set-validator! reference f)`

Makes `f` the validator of an atom, a ref, an agent or a var, answering `nil`: every value
the reference would take must pass `f` (any function value), else the store refuses with
`IllegalStateException` `Invalid reference state` and the value stays. The current value
must pass `f` first; `nil` removes the validator. An atom checks it on every store, a ref
when its transaction commits, an agent on each action's answer, a var on `alter-var-root`
and on every `def`, `defn`, `defmacro`, `deftest` or `with-redefs` replacing its root.
Of a macro var it checks the macro function, whether the var is spelled literally or
computed. `get-validator` answers it. Works as a function value.

```clojure
(def a (atom 2))
(set-validator! a even?)
(println (try (swap! a inc) (catch IllegalStateException e (ex-message e))) @a)
(println (fn? (get-validator a)))
(set-validator! a nil)
(println (swap! a inc) (get-validator a))
```

```
Invalid reference state 2
true
3 nil
```
