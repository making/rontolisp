# disj

`(disj s k ...)`

Answers a fresh set minus the given members; absent members are ignored. `disj` of `nil`
is `nil`.

As a value a set plus a rest list of members.

Deviation: `disj` of a map signals (the Common Lisp type error, not the oracle's
message); transients (`disj!`) are refused by name.

```clojure
(println (count (disj #{1 2 3} 2)))    ; 2
(println (contains? (disj #{1 2 3} 2) 2)) ; false
(println (count (disj #{1 2} 9)))      ; 2
(println (disj nil 1))                 ; nil
(println (map disj [#{1} #{2}] [1 2])) ; (#{} #{})
```
