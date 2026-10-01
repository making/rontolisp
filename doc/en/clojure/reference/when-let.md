# when-let

`(when-let [p e] body...)`

Binds the pattern to the init like `let` (destructuring included) and runs the
body only when the init is truthy. The init runs once.

```clojure
(println (when-let [x 1] (+ x 10))) ; 11
(println (when-let [x nil] :body)) ; nil
```
