# ref-min-history

`(ref-min-history ref)` / `(ref-min-history ref n)`

`ref`'s min history (`0` unless made or set otherwise), or `n` (the oracle's
`intCast`, so `1.5` is `1`) stored as it, answering the ref. Anything but a
ref is the oracle's cast failure (`ClassCastException`, `NullPointerException`
for `nil`). As a value one or two arguments.

```clojure
(def account (ref 0))
(println (ref-min-history account)) ; 0
(ref-min-history account 3)
(println (ref-min-history account)) ; 3
```
