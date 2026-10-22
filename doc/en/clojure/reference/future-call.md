# future-call

`(future-call f)`

`clojure.core/future-call`: the function `f` on its own thread, like `future` of calling it with no arguments (bindings conveyed the same way), answering the same future. As a value a one-argument function.

```clojure
(println @(future-call (fn [] 11))) ; 11
```
