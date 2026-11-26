# ->>

`(->> expr step...)`

Threads `expr` through each step, inserting the value as the LAST element of every
list step; a bare name or keyword is called with the value, and the last step's value
is the answer. Lowers to the threaded call rewritten as datums. A step over a
collection literal signals (collections are not functions here).

```clojure
(println (->> 5 inc inc))            ; 7
(println (->> [1 2] (map inc)))      ; (2 3)
(println (->> [3 1] (apply max)))    ; 3
```
