# instance?

`(instance? Class x)`

`true` when `x` is an instance of the class, like the oracle's `isInstance`. A class or
interface answers for every kind of value whose oracle class is or implements it:
`java.util.List` for a vector, a list or a lazy seq, `java.util.Map` for a map or a record,
`clojure.lang.IFn` for a function, keyword, symbol, map, set, vector or var, `Comparable` for a
string, number, keyword or vector, `java.io.Writer` for `*out*`. `Object` is every value but
`nil`. A known record/deftype name tests the dispatch tag. A protocol's interface (`user.P`,
the namespace and name munged like the oracle's: `my_app.core.my_p`) is `true` of a record,
deftype or `reify` whose body names the protocol, with methods or none; an `extend-type` or
`extend-protocol` target is not (`satisfies?` is). A `clojure.lang` interface a body
implements ([reify](reify.md#host-interfaces): `Counted`, `IFn` ...) is `true` of the value,
and so is each of its supers (`Counted` of an `Indexed`). A throwable class (`Exception`,
`IllegalArgumentException`, `clojure.lang.ExceptionInfo`, a dotted or imported one) tests an
exception or a runtime error by its class -- the class `class` answers or a subclass of it.
On the interpreter and the JVM a host object answers by its host class, so
`(instance? java.io.File (java.io.File. "x"))` and
`(instance? Number (java.math.BigDecimal. "1"))` are `true`; a class no value here has
(`java.io.File` of a Clojure value, `Integer`) answers `false`, and a name no class has is
`unknown name`.

```clojure
(println (instance? String "a") (instance? String 1)) ; true false
(println (instance? java.util.List [1]) (instance? clojure.lang.IFn :k) (instance? java.util.Map [1])) ; true true false
(println (instance? RuntimeException (IllegalArgumentException. "x")) (instance? RuntimeException (Exception. "x"))) ; true false
(defprotocol P (m [x]))
(defrecord R [] P (m [_] 1))
(defrecord S [])
(extend-type S P (m [_] 2))
(println (instance? user.P (->R)) (instance? user.P (->S)) (satisfies? P (->S))) ; true false true
```
