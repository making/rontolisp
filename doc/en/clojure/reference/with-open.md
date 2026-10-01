# with-open

`(with-open [name init ...] body...)`

Binds each value around the body and closes each in reverse order on every
exit, through `unwind-protect`. A stream value (such as a
`clojure.java.io/reader`) closes through `close` directly; anything else closes
through the `close` method, so a Java closeable works where host objects exist
(the interpreter and the JVM -- wasm rejects `java:`, like all interop).

```clojure
(println (with-open [] :ok)) ; :ok
```
