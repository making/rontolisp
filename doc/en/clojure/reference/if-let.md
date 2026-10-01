# if-let

`(if-let [p e] then)` / `(if-let [p e] then else)`

Binds like `when-let`, answering the then or the else branch. Without an else,
`nil`.

```clojure
(println (if-let [x 2] (+ x 1) :none)) ; 3
(println (if-let [x nil] :some :none)) ; :none
```
