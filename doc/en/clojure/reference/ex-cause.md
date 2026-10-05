# ex-cause

`(ex-cause ex)`

Answers an exception's cause: the third argument of `ex-info`, or the cause a throwable
construction took (`(Exception. "m" cause)`, `(Exception. cause)`), a host exception's
its own on the interpreter and the JVM; `nil` when it has none,
for a runtime error and for a value that is no exception. `.getCause` answers the same.
Works as a function value too.

```clojure
(println (ex-message (ex-cause (ex-info "outer" {} (Exception. "inner"))))) ; inner
(println (map ex-cause [(Exception. "x") "s"])) ; (nil nil)
```
