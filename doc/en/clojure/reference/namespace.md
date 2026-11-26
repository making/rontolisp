# namespace

`(namespace x)`

The part of a keyword's or symbol's spelling before the first `/`, `nil` when
absent; strings and anything else signal, like the oracle. As a value a
one-argument lambda.

```clojure
(println (namespace :foo/bar)) ; foo
(println (namespace :foo)) ; nil
```
