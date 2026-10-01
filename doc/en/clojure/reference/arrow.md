# ->

`(-> expr step...)`

Threads `expr` through each step: a list step gets the value inserted as its second
element, a bare name or keyword is called/read with the value, and the last step's
value is the answer. Lowers to the threaded call rewritten as datums. A step over a
collection literal signals (collections are not functions here).

```clojure
(println (-> 5 inc inc))               ; 7
(println (-> {:a 1} :a))               ; 1
(println (-> [1 2] (conj 3) (conj 4))) ; [1 2 3 4]
```
