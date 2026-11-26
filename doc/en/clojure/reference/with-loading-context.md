# with-loading-context

`(with-loading-context body...)`

Runs the body as-is and answers its last value. Loading has no separate context to
thread through here: the oracle rebinds its compiler loader around the body, which is
unobservable past the load.

```clojure
(println (with-loading-context (+ 1 2))) ; 3
```
