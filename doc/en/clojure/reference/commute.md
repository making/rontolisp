# commute

`(commute r f args...)`

Like `alter`, but `f` applies at commit onto the latest value (running twice
uncontested, like the oracle's) and never makes the transaction retry. An
`alter` or a `ref-set` of the same ref later in the same transaction is the
oracle's "Can't set after commute" refusal. Requires
`dosync`, like `alter`.

```clojure
(def r (ref 1))
(dosync (commute r * 6))
(println @r) ; 6
```
