# interpose

`(interpose sep coll)`

Answers the members of `coll`'s seq view with `sep` between every two, strictly.
A one-member collection never shows the separator. As a value a two-argument
lambda.

```clojure
(println (interpose 0 [1 2 3])) ; (1 0 2 0 3)
```
