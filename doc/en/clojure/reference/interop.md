# Java interop

Interop lowers to the `java:` surface and runs on the interpreter and the JVM only -- the wasm backends reject `java:`. Class names resolve dotted as written, through `:import`, or through `java.lang` (the oracle's default-import list: the common throwables such as `IllegalStateException` are there, `AutoCloseable` is not). A string receiver answers the mapped core operation (a Lisp string is no host object), and `.toString` of any value that is no host object its `str` spelling, on every backend. A collection, keyword, symbol, ratio or atom answers its common methods (`.count`, `.get`, `.contains`, `.getName`, `.numerator`, `.deref`, ...) through the core functions on every backend, and refuses any other method by name ([`.name`](dot-name.md)). A [clojure.java.io](clojure-java-io.md) File, URL, URI or stream answers its class's methods on every backend and crosses into a Java member as the host object it stands for (the interpreter and the JVM); a host `java.io.File`, URL or URI a member answers is one to `slurp`, `spit`, the namespace's functions and a protocol extended to its class. `str` of a host object answers its `toString`. Printed readably (`prn`, `pr-str`, inside a collection `str` spells), a Java `List` is a vector when it is `RandomAccess` (`ArrayList`) and a list otherwise (`LinkedList`), a `Map` a map and a `Set` a set, in the collection's own order, like the oracle; any other host object, and every one under `println`/`print`, shows `#<java C>` (the oracle's `#object[...]` without the hash and `toString`), except a class object, which prints its name. A construction of a throwable class that carries only a message and a cause is an exception, and `.getMessage`/`.getLocalizedMessage`/`.getCause` of an exception (a caught runtime error included) answer from it, on every backend. An exception a member throws is the host's own, which a catch takes by its class ([try](try.md)); an exception the program built, passed to a member, is a host exception of its class ([Deviations](../deviations.md)). A fn passed where a Java interface is expected implements its abstract method, called with the method's arguments; the interface's default methods keep their bodies ([Deviations](../deviations.md)).

| Name | Example | Result |
|---|---|---|
| `.` | `(. "hi" length)` | `2` |
| `..` | `(.. "hi" (toUpperCase) (length))` | `2` |
| `.name / .-name` | `(.toUpperCase "hi")` | `HI` |
| `Class/member` | `(Integer/parseInt "42")` | `42` |
| `Class/member` (value) | `(every? Character/isWhitespace " ")` | `true` |
| `Class/.method` | `(map String/.length ["ab" "abcd"])` | `(2 4)` |
| `Class/new` | `(String/new "q")` | `q` |
| `^[types]` | `(map ^[double] Math/abs [-1 2])` | `(1.0 2.0)` |
| `new` | `(.length (new String "hi"))` | `2` |
| `memfn` | `((memfn toUpperCase) "hi")` | `HI` |
| `proxy` | `(.get (proxy [java.util.function.Supplier] [] (get [] "p")))` | `p` |
| fn as an interface | `(let [l (java.util.ArrayList. [3 1 2])] (.sort l (fn [a b] (compare b a))) (vec l))` | `[3 2 1]` |
