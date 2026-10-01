# name

`(name x)`

A string answers itself; a keyword's or symbol's spelling answers the part past
the first `/` (the whole spelling when none). Anything else signals, like the
oracle. As a value a one-argument lambda.

```clojure
(println (name :foo/bar)) ; bar
(println (name "hello")) ; hello
```
