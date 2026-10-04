# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

The sugar for `.`: a dotted name calls the method on the receiver, a `.-`-dotted one reads
the instance field. The receiver decides the path as everywhere in interop, so a string
receiver takes the mapped core operation, and another `String` method calls the string as a
`String`. `.toString` of any value that is no host object
answers its `str` spelling, on every backend. A collection, keyword, symbol, ratio or atom has
no host object either: its common `clojure.lang`/`java.util` methods (`.count`, `.size`,
`.isEmpty`, `.get`, `.nth`, `.valAt`, `.contains`, `.containsKey`, `.indexOf`, `.getName`,
`.getNamespace`, `.numerator`, `.deref`, ...) answer through the matching core function on
every backend, a method its class lacks is refused in the oracle's words, and any other method is
refused by name. Anything else runs on the interpreter and the JVM only -- the wasm backends
reject `java:`.

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.compareTo "a" "b")) ; -1
(println (.toString [1 "a"])) ; [1 "a"]
(println (.count [1 2 3]) (.get {:a 1} :a) (.getName :k)) ; 3 1 k
```
