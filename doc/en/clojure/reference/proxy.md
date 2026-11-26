# proxy

`(proxy [ClassOrInterface...] [args...] methods...)`

Builds one host object: of only interfaces, implementing every one of them; with
a class first, extending that superclass (and any interfaces after it), made with
the constructor the argument vector chooses.

Each `(method [params...] body...)` becomes a dispatch arm chosen by the method's
name, so a name two interfaces declare runs the one body. An interface-only method
receives the Java arguments only -- there is no `this`. Calling an interface
method the proxy leaves out raises `no proxy method: <name>`.

With a superclass, each body also binds `this` to the proxy object, and
`(proxy-super method args...)` calls the superclass implementation. A named
method runs its body -- `toString`/`equals`/`hashCode` included -- while a method
left out is inherited when the class implements it, and refused with the method's
name when it is called and nothing implements it. A second class in the vector, a
duplicate method, a final superclass and a non-vector argument vector are refused
by name, and field writes (`set!`) are refused too. Runs on the interpreter and
the JVM only -- the wasm backends reject `java:`.

A `byte[]` Java hands a method is a [byte array](byte-array.md) over it, and what the body
stores into it Java reads when the method returns. A method answering a reference hands Java
the value's own object: a byte array its `byte[]`, a vector a `List`, a map a `Map`, a keyword
an object Java hands back as the keyword.

```clojure
(println (.get (proxy [java.util.function.Supplier] [] (get [] "p")))) ; p
```

```clojure
(def p (proxy [java.util.function.Consumer java.util.function.IntConsumer] []
         (accept [x] (println :got x))))
(.forEach (java.util.List/of "s") p)                   ; :got s
(.forEach (java.util.stream.IntStream/range 3 4) p)    ; :got 3
```

```clojure
(println (.lastModified (proxy [java.io.File] ["recent"] (lastModified [] 42)))) ; 42
(println (.getName (proxy [java.io.File] ["recent"] (lastModified [] 42))))      ; recent
```

```clojure
(def f (proxy [java.io.File] ["x"]
         (toString [] (str "super-was:" (proxy-super toString)))))
(println (.toString f)) ; super-was:x
```

```clojure
(def in (proxy [java.io.InputStream] []
          (read [buf off len] (aset buf off (byte 7)) 1)))
(def b (byte-array 2))
(println (.read in b 0 2) (vec b)) ; 1 [7 0]
```
