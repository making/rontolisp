# list*

`(list* x... coll)` / `(list* coll)`

Answers a list of the leading arguments followed by the seq view of the last; with one
argument, just its seq (signalling for a non-collection). A right fold of `cons` over
the seq view.

```clojure
(println (list* 1 2 [3 4])) ; (1 2 3 4)
(println (list* '(1 2)))    ; (1 2)
```
