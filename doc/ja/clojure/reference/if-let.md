# if-let

`(if-let [p e] then)` / `(if-let [p e] then else)`

`when-let` 同様に束縛し、then か else の分岐で答えます。else なしでは `nil` です。

```clojure
(println (if-let [x 2] (+ x 1) :none)) ; 3
(println (if-let [x nil] :some :none)) ; :none
```
