# disj

`(disj s k ...)`

Answers a fresh set minus the given members; absent members are ignored. `disj` of `nil`
is `nil`.

Deviation: `disj` of a map signals (the Common Lisp type error, not the oracle's
message); transients (`disj!`) are refused by name.

```clojure
(println (count (disj #{1 2 3} 2)))    ; 2
(println (contains? (disj #{1 2 3} 2) 2)) ; false
(println (count (disj #{1 2} 9)))      ; 2
(println (disj nil 1))                 ; nil
```
