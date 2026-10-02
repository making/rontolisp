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
locals. The name joins the whole-file pre-scan, so a constructor call may stand
above the definition.

A record prints as its literal, like the oracle: `#user.R{:a 7}` -- the defining
namespace (`-` spelled `_`) plus the name, the declared fields first. The literal
reads back: `#ns.Name{:k v ...}` builds the record over the unevaluated body
(missing fields `nil`, extra keys kept), `#ns.Name[v ...]` positionally (a wrong
count is refused). The class must be a record the program defines; an undotted
`#Name{...}` is a tagged literal and is refused, like the oracle
(`No reader function for tag Name`).

Deviation: `str` of a record spells its literal, where the oracle answers
`user.R@<hash>`.

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
