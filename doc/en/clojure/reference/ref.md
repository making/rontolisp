# ref

`(ref v)` / `(ref v :validator validate-fn)`

A ref is the same tagged cell as an `atom`, read with `deref`/`@`; only the
transaction verbs (`alter`/`commute`/`ref-set`) may write it, and only inside
`dosync`. A `:validator` must accept the initial value and, when a transaction that
wrote the ref ends, its final value; a refusal (`Invalid reference state`) undoes the
whole transaction. `:meta` is dropped, like every other metadata; any other option is
refused by name.

```clojure
(def r (ref 0 :validator (fn [n] (>= n 0))))
(dosync (alter r inc))
(println @r) ; 1
```
