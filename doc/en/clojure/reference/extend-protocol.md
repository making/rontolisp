# extend-protocol

`(extend-protocol Protocol Type (method [target & args] body...) ...)`

Adds rows to a protocol's table without redefining the type: one stored lambda per
method per type, like `defmethod` rows (a repeated type's later rows win, like the
oracle). Targets are the kinds `class` answers (`String`, `Number`, `Boolean`,
`Keyword`, `Symbol`, `Character`, `Map`, `Vector`, `Set`, `List`/`Seq`, plus `nil`
and `Object` as the miss default; package-qualified spellings such as `java.lang.String` or
`clojure.lang.IPersistentMap` too), known record/deftype names, and any other class a value
may be an instance of: a throwable (`Throwable`, `Exception`, `ExceptionInfo`, ...), an
interface such as `clojure.lang.IRef` or `clojure.lang.IDeref`, `java.util.Date` (which a
`java.sql.Timestamp` reaches too), and on the interpreter and the JVM a host class. A value
with no row of its own class tries those the protocol was extended to, like the oracle: the
superclasses first, then the interfaces, each ahead of its own supertypes, then `Object`. An
extension to an interface reaches every value implementing it: a record's map interfaces
(`clojure.lang.IPersistentMap`, `java.util.Map` ...), the interfaces a `reify`, `deftype` or
`defrecord` body names ([reify](reify.md#host-interfaces)), `clojure.lang.Sequential` of a
vector, `clojure.lang.IFn` of a keyword. A name no class has is refused, like `instance?`. A method of several arities spells them as
the clauses of a `fn`: `(method ([target] ...) ([target x] ...))`.

```clojure
(defprotocol P (m [x]))
(extend-protocol P
  nil (m [_] :nil)
  String (m [s] :str)
  Object (m [_] :other))
(println (m nil))  ; :nil
(println (m "s"))  ; :str
(println (m 1.5))  ; :other

(defprotocol Q (q [x] [x y]))
(extend-protocol Q Long (q ([n] n) ([n k] (* n k))))
(println (q 7) (q 7 6)) ; 7 42

(defprotocol R (r [x]))
(extend-protocol R
  Throwable (r [_] :throwable)
  Exception (r [_] :exception)
  clojure.lang.IRef (r [_] :ref))
(println (r (ex-info "m" {})) (r (Error. "e")) (r (atom 1))) ; :exception :throwable :ref

(defprotocol S (s [x]))
(extend-protocol S
  clojure.lang.Sequential (s [_] :sequential)
  clojure.lang.IPersistentMap (s [_] :map)
  Object (s [_] :other))
(defrecord Point [x y])
(deftype Line [] clojure.lang.Sequential)
(println (s [1]) (s '(1)) (s (Line.)) (s (->Point 1 2)) (s :k)) ; :sequential :sequential :sequential :map :other
```
