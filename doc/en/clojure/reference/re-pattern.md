# re-pattern

`(re-pattern s)`

Answers the pattern for `s`: a pattern answers itself, a string compiles
(parsed eagerly, like the oracle). Works as a function value too.

```clojure
(println (re-pattern "a+")) ; #"a+"
(println (str (re-pattern "a+"))) ; a+
```
