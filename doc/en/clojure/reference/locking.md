# locking

`(locking x body...)`

Runs the body holding the lock of `x` and answers its last value. On the interpreter
and the JVM, where threads exist (a Ring handler runs one per request), the lock is a
reentrant mutex kept per value: by identity, a number by value, a keyword by its
spelling. Both wasm backends are single-threaded, so there the body runs as is. A `nil`
lock throws `NullPointerException` before the body. A `recur` in the body is refused,
like one inside a `try`.

Deviation: the lock table keeps every value it was handed for the program's lifetime,
and the `NullPointerException` message names the local `locklocal` where Clojure names
a generated one.

```clojure
(def hits (atom 0))
(println (locking hits (swap! hits inc))) ; 1
(println (locking :log :done))            ; :done
```
