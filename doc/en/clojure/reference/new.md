# new

`(new Class args...)`

Constructs a host object; the `(Class. args)` suffix spelling is the same operation. The
instance answers to every other interop verb. Runs on the interpreter and the JVM only --
the wasm backends reject `java:`.

```clojure
(println (.length (new String "hi"))) ; 2
```
