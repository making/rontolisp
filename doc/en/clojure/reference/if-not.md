# if-not

`(if-not test then)` / `(if-not test then else)`

Answers `then` unless the test is truthy, else the else branch (`nil` without
one).

```clojure
(println (if-not nil :t :e)) ; :t
(println (if-not 1 :t :e)) ; :e
```
