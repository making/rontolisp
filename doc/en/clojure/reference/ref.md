# ref

`(ref v)` / `(ref v :validator validate-fn)`

A ref is the same tagged cell as an `atom`, read with `deref`/`@`; only the
transaction verbs (`alter`/`commute`/`ref-set`) may write it, and only inside
`dosync`. With a `:validator`, every write runs it first and a failed one
signals (`Invalid reference state`), leaving the old value behind -- validators
run, retries do not (there is a single thread, so nothing ever conflicts).
`:meta` is dropped, like every other metadata; any other option is refused by
name.

```clojure
(def r (ref 0 :validator (fn [n] (>= n 0))))
(dosync (alter r inc))
(println @r) ; 1
```
