# .name / .-name

`(.method receiver args...)` `(.-field receiver)`

The sugar for `.`: a dotted name calls the method on the receiver, a `.-`-dotted one reads
the instance field. The receiver decides the path as everywhere in interop, so a string
receiver takes the mapped core operation (`.indexOf` of an int searches its character, the
`indexOf(int)` overload), and another `String` method calls the string as a `String`. `.toString` of any value that is no host object
answers its `str` spelling, on every backend. A collection, keyword, symbol, ratio or atom has
no host object either: its common `clojure.lang`/`java.util` methods (`.count`, `.size`,
`.isEmpty`, `.get`, `.nth`, `.valAt`, `.contains`, `.containsKey`, `.indexOf`, `.getName`,
`.getNamespace`, `.numerator`, `.deref`, ...) answer through the matching core function on
every backend. `.equiv` is `=`, and `.equals` is the oracle's Java `equals`: a record equals a
map, sorted map or other record of its entries, and doubles inside a collection compare by
`Double.equals` (`(.equals [0.0] [-0.0])` false, `(.equals [##NaN] [##NaN])` true). Another
method of a JDK interface the oracle's class implements (`.toArray`,
`.containsAll`, `.entrySet`, `.stream`, `.sort`) is called on the read-only Java object the value
crosses to Java as, on the interpreter and the JVM, and refused by name on the wasm backends.
A fn answers `.invoke`, `.applyTo`, `.call`, `.run` and `.compare` (a boolean answer read as
Clojure's `AFunction.compare`) on every backend; `Comparator`'s other methods (`.reversed`,
`.thenComparing`) are called on the object the fn is, the same way. A
method its class lacks is refused in the oracle's words (`No matching field found: toArray for
class clojure.lang.Keyword`), and one the oracle's class may have that nothing here answers
(`.reduce`, `.meta`) by name. A record, deftype or reify answers what its class has, on every
backend: a protocol method its body implements calls it (`(.m r)`), and a zero-argument name
that is a declared field reads it (`(.a r)`); an `extend-type` method, an undeclared name or a
mutable field is refused in the oracle's words (`No matching field found: q for class
user.R`). A name that is no protocol method and no field of a record or deftype the program
defined is treated as on a collection (a record is a `java.util.Map`). Anything else runs on the
interpreter and the JVM only -- the wasm backends reject `java:`.

A zero-argument `(StringBuilder.)` (or `(new StringBuilder)`, `(StringBuffer.)`) is a
string output stream on every backend, like `(new java.io.StringWriter)`: `.append` of one
value writes its `str` (nil writes `"null"`), answering the builder, `.toString` and `str`
answer the text so far, and `.length`, `.charAt` and `.setLength` read and resize it
(`.setLength` answers nil). An initial text or capacity keeps the host construction, on the
interpreter and the JVM only. `java.util.Locale/US` (and `ROOT`, `ENGLISH`) is the locale's
`toString` text (`"en_US"`, `""`, `"en"`), which `.toUpperCase` and `.toLowerCase` of one
argument take on every backend, mapping as the bare call (the mapping ignores the locale).
`.intern` of a string is the string, on every backend.

```clojure
(println (.toUpperCase "hi")) ; HI
(println (.length "hi")) ; 2
(println (.compareTo "a" "b")) ; -1
(println (.toString [1 "a"])) ; [1 "a"]
(println (.count [1 2 3]) (.get {:a 1} :a) (.getName :k)) ; 3 1 k
(defprotocol P (m [this]))
(defrecord R [a] P (m [this] (str "m" a)))
(println (.m (->R 1)) (.a (->R 1))) ; m1 1
(let [sb (StringBuilder.)] (.append sb "a") (.append sb 1) (println (str sb) (.length sb))) ; a1 2
(println (.toUpperCase "straße" java.util.Locale/US) (.intern "hi")) ; STRASSE hi
```
