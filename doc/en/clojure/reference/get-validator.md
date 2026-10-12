# get-validator

`(get-validator reference)`

The validator of an atom, a ref, an agent or a var (`set-validator!`, or the `:validator`
option of `atom`, `ref` and `agent`), `nil` for none. Works as a function value.

```clojure
(println (get-validator (atom 1)) (fn? (get-validator (atom 1 :validator pos?))))
```

```
nil true
```
