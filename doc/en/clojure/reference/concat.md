# concat

`(concat coll...)`

Answers the concatenation of the seq views of every argument; each collection coerces,
so lists, vectors, strings, maps and sets all concatenate. `(concat)` is `nil`.

```clojure
(println (concat '(1 2) '(3 4)))    ; (1 2 3 4)
(println (concat '(1 2) [3 4]))     ; (1 2 3 4)
(println (concat '(1) '(2) #{3}))   ; (1 2 3)
(println (concat))                  ; nil
```
