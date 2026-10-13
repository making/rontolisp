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
value writes its `str` (nil writes `"null"`), answering the builder, and `.append` of a range
(`(.append w s start end)`) writes that part of the string (nil spells `"null"`), answering
the stream too. `.toString` and `str`
answer the text so far, and `.length`, `.charAt` and `.setLength` read and resize it
(`.setLength` answers nil). A one-argument construction starts the stream with the text
(`(StringBuilder. "ab")`, `(new StringBuilder "ab")`, `(StringBuffer. "ab")`), an integer
argument being a capacity (ignored, the stream starts empty); anything else is refused as
`No matching ctor found for class ...`. A one-argument `java.io.StringWriter`
(`(java.io.StringWriter. 16)`, `(new java.io.StringWriter 16)`) is an empty string output
stream instead: a number is a capacity (ignored, the stream starts empty), and anything
else is the oracle's `ClassCastException` to `Number` -- a `StringWriter` has no `String`
constructor -- nil being its `NullPointerException`. A `^[...]`-tagged construction names the host
overload instead and keeps the host construction, on the interpreter and the JVM only. `java.util.Locale/US` (and `ROOT`, `ENGLISH`) is the locale's
`toString` text (`"en_US"`, `""`, `"en"`), which `.toUpperCase` and `.toLowerCase` of one
argument take on every backend, mapping as the bare call (the mapping ignores the locale).
`.intern` of a string is the string, on every backend.

A zero-argument `(java.util.HashMap.)` (or `(new java.util.HashMap)`) is the empty map,
a one-argument construction of a map the map itself, and a two-argument construction of
two numbers (`(java.util.HashMap. 1024 0.25)`) the empty map, where the host is not (the
wasm backends): `.get`, `.size`, `.containsKey` and the other lookups read it like any
map, while `.put` on it throws the oracle's `UnsupportedOperationException` with no
message, as on the interpreter and the JVM, where the value crosses to Java as a
read-only map. `.putAll` of a map stores its entries into the map and answers nil; on a
record or a sorted map it throws the same `UnsupportedOperationException`.
nil builds to the oracle's `NullPointerException`. Where the host is (the interpreter
and the JVM) the construction stays the mutable `java.util.HashMap`, and any other
argument shape or a `^[...]`-tagged construction keeps the host construction everywhere.

A zero-argument `(Object.)` is a fresh uninterned symbol where the host is not (the wasm
backends), so a load-time lock (dynaload's `dynalock`) builds; two constructions are
never `=` or `identical?`, like two host objects. Where the host is it stays the host
object.

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
(println (str (StringBuilder. "ab") (StringBuffer. 16))) ; ab
(println (let [w (java.io.StringWriter. 16)] (.append w "ab") (str w))) ; ab
(println (let [w (java.io.StringWriter. 16)] (.append w "hello" 1 3) (str w))) ; el
(println (.toUpperCase "straße" java.util.Locale/US) (.intern "hi")) ; STRASSE hi
(let [h (doto (java.util.HashMap. 8 0.5) (.putAll {:a 1}))] (println (.get h :a) (.size h))) ; 1 1
(println (identical? (Object.) (Object.))) ; false
```
