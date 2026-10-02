# proxy

`(proxy [Interface...] decls... methods...)`

Builds one host object implementing every interface in the vector: each
`(method [params...] body...)` becomes a dispatch arm chosen by the method's name, so a name
two interfaces declare runs the one body. A method receives the Java arguments only -- there
is no `this`. Calling an interface method the proxy leaves out raises
`no proxy method: <name>`. A superclass (a class in the vector), constructor arguments,
`toString`/`equals`/`hashCode` (the object keeps `Object`'s) and multi-arity methods are
refused by name, and field writes (`set!`) are refused too. Runs on the interpreter and the
JVM only -- the wasm backends reject `java:`.

```clojure
(println (.get (proxy [java.util.function.Supplier] [] (get [] "p")))) ; p
```

```clojure
(def p (proxy [java.util.function.Consumer java.util.function.IntConsumer] []
         (accept [x] (println :got x))))
(.forEach (java.util.List/of "s") p)                   ; :got s
(.forEach (java.util.stream.IntStream/range 3 4) p)    ; :got 3
```
