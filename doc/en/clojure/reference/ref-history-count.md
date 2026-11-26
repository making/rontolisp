# ref-history-count

`(ref-history-count ref)`

The committed transactions that wrote `ref`, capped by its min history (the
oracle's retained chain: a fresh ref counts `0`). Anything but a ref is the
oracle's cast failure (`ClassCastException`, `NullPointerException` for `nil`).
As a value one argument.

```clojure
(def account (ref 0 :min-history 2))
(dosync (alter account inc))
(println (ref-history-count account)) ; 1
```
