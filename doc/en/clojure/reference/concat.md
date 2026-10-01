# concat

`(concat coll...)`

Answers the concatenation of every argument; each collection coerces through the seq
view, so lists, vectors, strings, maps and sets all concatenate. `(concat)` is `nil`.
When any argument is lazy the answer is a lazy seq, realized element by element;
otherwise it is the strict appended list.

```clojure
(println (concat '(1 2) '(3 4)))    ; (1 2 3 4)
(println (concat '(1 2) [3 4]))     ; (1 2 3 4)
(println (concat '(1) '(2) #{3}))   ; (1 2 3)
(println (concat))                  ; nil
(println (take 4 (concat [0] (iterate inc 1)))) ; (0 1 2 3)
```
