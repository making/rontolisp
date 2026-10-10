# defrecord

`(defrecord Name [fields...] Protocol (method [target & args] body...) ...)`

Defines a record: a map with a type tag. The value wraps the entry table every map
uses as `(:C%RECORD tag fields table class)`, so the map verbs read through it
(`get`/`contains?`/`keys`/`vals`/`count`/`seq` read the entries; `assoc`/`update`/
`conj`/`merge` rebuild the table and keep the tag; `dissoc` keeps the record while
every declared field is still present and drops to a plain map otherwise, like the
oracle). `=` compares two records by tag plus entries and never equals a plain map.
Two constructors lower to mangled functions: `->Name` positionally (a wrong count
signals) and `map->Name` from a map (missing fields default to `nil`, extra entries
kept); `(Name. ...)` rewrites to `->Name`. Inline method bodies see the fields as
locals, and an instance call reaches the inline methods and the declared fields
([`.name`](dot-name.md)). The name joins the whole-file pre-scan, so a constructor call may stand
above the definition.

A record prints as its literal, like the oracle: `#user.R{:a 7}` -- the defining
namespace (`-` spelled `_`) plus the name, the declared fields first. The literal
reads back, in source and through [read-string](read-string.md)/[read](read.md):
`#ns.Name{:k v ...}` builds the record over the unevaluated body
(missing fields `nil`, extra keys kept), `#ns.Name[v ...]` positionally (a wrong
count is refused). The class must be a record the program defines; an undotted
`#Name{...}` is a tagged literal and is refused, like the oracle
(`No reader function for tag Name`).

Deviation: `str` of a record spells its literal, where the oracle answers
`user.R@<hash>` (a body overriding `toString` answers its own, below).

```clojure
(defrecord R [a])
(def r (->R 7))
(println r)                       ; #user.R{:a 7}
(println (= r #user.R{:a 7}))     ; true
(println (get r :a))              ; 7
(println (= r (->R 7)))           ; true
(println (= r {:a 7}))            ; false
(println (get (assoc r :b 1) :b)) ; 1
```

The body may implement the interfaces of [reify](reify.md#host-interfaces) a record does not
implement itself -- `IFn`, `IDeref`, `IReduceInit` ... -- and override `toString`, which `str`
reads while the record still prints its literal. Its map interfaces are its own: naming
`ILookup`, `IObj`, `IPersistentMap`, `IHashEq`, `java.util.Map` or `java.io.Serializable`, or
defining a method the record defines itself (`count`, `seq`, `valAt`, `assoc`, `iterator`,
`meta`, `equals`, `hashCode` ...), is the oracle's `Duplicate` refusal, and a method of those
interfaces the record leaves to the interface (`assocEx`, a `java.util.Map` default) is refused
by name. The Java interfaces the body implements (`Runnable`, `Comparable` ...) are what Java
sees of the record too: it crosses into a Java member as a `java.util.Map` of its entries that
implements them ([Java interop](interop.md)).

```clojure
(defrecord Adder [n] clojure.lang.IFn (invoke [_ x] (+ n x)))
((->Adder 10) 5) ; => 15
(defrecord Point [x y] Object (toString [_] (str "<" x "," y ">")))
(str (->Point 1 2)) ; => "<1,2>"
```
