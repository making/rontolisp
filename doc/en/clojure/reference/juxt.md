# juxt

`(juxt f g...)`

Answers a function returning the vector of every given function applied to its
arguments. Keywords and collections work as functions, like everywhere else. As a
value it takes one or more functions.

```clojure
(println ((juxt inc dec) 5)) ; [6 4]
(println ((juxt :a :b) {:a 1 :b 2})) ; [1 2]
```
