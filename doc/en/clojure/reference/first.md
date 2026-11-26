# first

`(first coll)`

Answers the first element of `coll`'s seq view, or `nil` when the view is empty. A map
contributes one two-vector per entry, so `first` of a map is an entry vector. As a value
a lambda over the seq view, so it travels through `map`/`filter` bare.

```clojure
(println (first [1 2]))   ; 1
(println (first {:a 1}))  ; [:a 1]
(println (first "ab"))    ; a
(println (first nil))     ; nil
```
