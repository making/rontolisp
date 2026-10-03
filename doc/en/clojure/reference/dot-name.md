# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

The sugar for `.`: a dotted name calls the method on the receiver, a `.-`-dotted one reads
the instance field. The receiver decides the path as everywhere in interop, so a string
receiver takes the mapped core operation. `.toString` of any value that is no host object
answers its `str` spelling, on every backend. Anything else runs on the interpreter and the JVM
only -- the wasm backends reject `java:`.

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.toString [1 "a"])) ; [1 "a"]
```
