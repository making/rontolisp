# take-nth

`(take-nth n coll)` / `(take-nth n)`

Answers every `n`th member of `coll`'s seq from the first: a lazy input answers a lazy
seq, a strict one a strict list. `(take-nth n)` is its [transducer](transducers.md). As
a value one or two arguments.

Deviation: a zero `n` signals and the seq arity steps by the magnitude of a negative one,
where the oracle's seq arity repeats the first member forever.

```clojure
(println (take-nth 2 [1 2 3 4 5])) ; (1 3 5)
(println (into [] (take-nth 3) (range 10))) ; [0 3 6 9]
```
