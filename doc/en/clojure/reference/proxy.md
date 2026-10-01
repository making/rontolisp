# proxy

`(proxy [Interface] decls... methods...)`

Builds a host object implementing one interface: each `(method [params...] body...)`
becomes a dispatch arm, and the method receives the Java arguments only -- there is no
`this`. No constructor arguments, no superclass, one interface, single-arity methods; the
wider shapes are refused by name, and field writes (`set!`) are refused too. Runs on the
interpreter and the JVM only -- the wasm backends reject `java:`.

```clojure
(println (.get (proxy [java.util.function.Supplier] [] (get [] "p")))) ; p
```
