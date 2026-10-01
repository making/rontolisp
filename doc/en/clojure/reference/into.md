# into

`(into to from)`

Answers `from` conjoined onto `to` one member at a time: lists grow at the
front, vectors at the end, maps take entries, sets members. A three-argument
call names a transducer, which stays refused. As a value a two-argument lambda.

```clojure
(println (into [] [1 2])) ; [1 2]
(println (into {} [[:a 1]])) ; {:a 1}
(println (into '() [1 2])) ; (2 1)
```
