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
every backend. Another method of a JDK interface the oracle's class implements (`.toArray`,
`.containsAll`, `.entrySet`, `.stream`, `.sort`) is called on the read-only Java object the value
crosses to Java as, on the interpreter and the JVM, and refused by name on the wasm backends. A
method its class lacks is refused in the oracle's words (`No matching field found: toArray for
class clojure.lang.Keyword`), and one the oracle's class may have that nothing here answers
(`.reduce`, `.meta`) by name. A record, deftype or reify answers what its class has, on every
backend: a protocol method its body implements calls it (`(.m r)`), and a zero-argument name
that is a declared field reads it (`(.a r)`); an `extend-type` method, an undeclared name or a
mutable field is refused in the oracle's words (`No matching field found: q for class
user.R`). A name that is no protocol method and no field of a record or deftype the program
defined is treated as on a collection (a record is a `java.util.Map`). Anything else runs on the
interpreter and the JVM only -- the wasm backends reject `java:`.

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.compareTo "a" "b")) ; -1
(println (.toString [1 "a"])) ; [1 "a"]
(println (.count [1 2 3]) (.get {:a 1} :a) (.getName :k)) ; 3 1 k
(defprotocol P (m [this]))
(defrecord R [a] P (m [this] (str "m" a)))
(println (.m (->R 1)) (.a (->R 1))) ; m1 1
```
