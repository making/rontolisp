# as->

`(as-> expr name step...)`

Binds `name` to the value of each step in turn, the first against `expr`, and answers
the last step's value. Lowers to nested `let`s, so shadowing matches the oracle; the
name is an ordinary lexical binding inside every step, not an insertion point.

```clojure
(println (as-> 5 x (inc x) (* x 2))) ; 12
(println (as-> 5 x x))               ; 5
```
