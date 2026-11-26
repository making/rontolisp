# var-set

`(var-set x val)`

Refused: a var that is not thread-locally bound takes no root through `set`, like the
oracle's `IllegalStateException` (`Can't change/establish root binding of: short with
set`). Thread bindings are not reified here, so it always refuses. As a value two
arguments.

```clojure
(println (try (var-set (intern 'user 'doc-eg-vs) 1) (catch IllegalStateException e (.getMessage e)))) ; Can't change/establish root binding of: doc-eg-vs with set
```
