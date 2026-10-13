# ref-max-history

`(ref-max-history ref)` / `(ref-max-history ref n)`

`ref`'s max history (`10` unless made or set otherwise), or `n` (the oracle's
`intCast`, so `1.5` is `1`) stored as it, answering the ref. Anything but a
ref is the oracle's cast failure (`ClassCastException`, `NullPointerException`
for `nil`). As a value one or two arguments.

```clojure
(def account (ref 0))
(println (ref-max-history account)) ; 10
(ref-max-history account 3)
(println (ref-max-history account)) ; 3
```
