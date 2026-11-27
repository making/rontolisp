# bases

`(bases c)`

Answers the immediate superclass and the direct interfaces of the class `c`, superclass first,
as a seq of class values; nil when there are none (`Object`, an interface extending none) and
for nil. Any other argument is the oracle's `ClassCastException`. As a value a one-argument
lambda.

Every backend answers: a host class (interpreter, a JVM program using Java interop) by the
host's reflection, elsewhere from tables read off the oracle -- the `clojure.lang` classes'
and the JDK classes' a value or a supertype of one may have. A function's class has
`clojure.lang.AFunction`; a record's `Object`, the interfaces its body names (protocols'
included), then `IRecord`, `IHashEq`, `IObj`, `ILookup`, `IKeywordLookup`, `IPersistentMap`,
`java.util.Map` and `Serializable`; a deftype's `Object`, its body's interfaces, then `IType`.

A `reify`'s class answers its body's interfaces and `clojure.lang.IObj`, and a
`definterface`'s name nil, where the host is; where it is not (the wasm backends) those two
are refused.

Deviations: the answer is a list (`PersistentList`; the oracle's a `Cons`). A record's or
deftype's body interfaces come in the order the body names them, where the oracle orders
several by a hash set. A JDK class no value here has (a class literal of `java.util.HashMap`
on wasm) is refused.

```clojure
(println (bases Long)) ; (java.lang.Number java.lang.Comparable java.lang.constant.Constable java.lang.constant.ConstantDesc)
(println (bases (class []))) ; (clojure.lang.APersistentVector clojure.lang.IObj clojure.lang.IEditableCollection clojure.lang.IReduce clojure.lang.IKVReduce clojure.lang.IDrop)
(println (bases Object) (bases nil)) ; nil nil
```
