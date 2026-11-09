# print-method

`(defmethod print-method Type [x writer] body...)` / `(print-method x writer)`

`clojure.core/print-method` is the printer's multimethod: a method a program defines for one
of its records or deftypes is how `pr`, `prn`, `print`, `println`, `pr-str`, `prn-str`,
`print-str`, `println-str` and the REPL write a value of the type, also inside a collection
and as part of `str` of one. The writer is the stream being written: `.write` a string to it,
or `binding [*out* writer]` and print. A `pr` inside the method under `print` writes as
`print` does (`*print-readably*` is nil there, like the oracle's `print`). Called,
`print-method` writes `x` through its type's method, or as `pr` writes it without one.
`remove-method` brings the default back, `get-method` answers the method (without one,
`print-method` itself).

A method for a `:type` keyword prints every value carrying it in its metadata
(`(:type (meta x))`), ahead of the record-or-deftype tag's own -- the oracle dispatches
`print-method` on the `:type` entry first.

A method for a host class (`java.util.Date`) writes a host object of the class or of a
subclass, on the interpreter and the JVM: tools.reader's `#inst` reader answers a host
`Date`, which its own method prints.

A dispatch value the `defmethod` computes -- a function's parameter, a var -- is read when
the `defmethod` runs, so a function handed a record's class (rewrite-clj's
`make-printable!`) or a `:type` keyword stores the method under it then.

A method for a protocol's interface (its dotted name, `user.Shape`) writes every record,
deftype and `reify` whose body implements the protocol (an extension does not). A record's
class also matches the printer's own `clojure.lang.IRecord`, `java.util.Map` and
`clojure.lang.IPersistentMap` methods, so, like the oracle's, the interface's method writes
a record only once `prefer-method` prefers it over all three -- as a call of
`print-method`'s name or through its value (schema's `(doseq [m [print-method ...]]
(prefer-method m ...))`); two matching methods neither of which is preferred are the
oracle's `IllegalArgumentException` (`Multiple methods in multimethod 'print-method'
...`).

Deviations: a program's own records, deftypes, protocol interfaces and `:type` keywords
take a method, and a host class no core value belongs to but an instant or a UUID; an
instant or a UUID made here keeps its own printing (`#inst`/`#uuid`, what the oracle's and
every library's methods write), and a host `Timestamp`, `Calendar` or `UUID` no method of
its own class reaches prints as a host object. `Object`, a class a core value belongs to
(`String`, `java.util.Map`), a `clojure.lang` class and nil are refused by name (a computed
one, or a host class, when the `defmethod` runs, as an `UnsupportedOperationException`), and
so is `methods` of `print-method`; `str` of the value itself answers what the method writes
where the oracle answers its `toString`. A deftype or `reify` matches no printer's own method
beside an interface's, so one implementing a collection interface too takes the
interface's method where the oracle asks for a preference; where the preferences settle
only some of the rows, the oracle's answer follows its method table's hash order, and here
the rows are taken in a fixed order (`IRecord`, `java.util.Map`, `IPersistentMap`, then the
interfaces in the order their methods were first stored).

```clojure
(defrecord Point [x y])
(defmethod print-method Point [p w] (.write w (str "<" (:x p) "," (:y p) ">")))
(prn (->Point 1 2) [(->Point 3 4)]) ; <1,2> [<3,4>]
(println (pr-str (->Point 5 6))) ; <5,6>

(defprotocol Shape (describe [s]))
(deftype Circle [r] Shape (describe [_] (list 'circle r)))
(defn printable! [iface] (defmethod print-method iface [x w] (print-method (describe x) w)))
(printable! user.Shape)
(prn (Circle. 2) [(Circle. 3)]) ; (circle 2) [(circle 3)]
```
