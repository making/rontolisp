# transient

`(transient coll)`

A transient over a copy of the vector, map or set `coll`: the bang verbs
([`conj!`](conj-bang.md), [`assoc!`](assoc-bang.md), [`dissoc!`](dissoc-bang.md),
[`disj!`](disj-bang.md), [`pop!`](pop-bang.md)) edit that copy in place, so a run of them
costs one edit each, and [`persistent!`](persistent-bang.md) hands it back as a collection.
`coll` is untouched. A transient is no collection: `count`, `get`, `nth`, `contains?`,
`find`, a call and a keyword read it, `seq` (and every verb over the seq view) refuses it,
`=` and `hash` are identity, and it prints as the oracle's `#object` without the identity
hash. A list, a sorted collection, a record or a string is refused as the oracle's
`ClassCastException`, nil as its `NullPointerException`; a type implementing
`IEditableCollection` answers its `asTransient`. As a value a one-argument function.

Deviation: a bang verb answers the transient itself, where the oracle's may answer another
object (an array map's `assoc!` past eight entries); a program using the answer behaves
alike. `nth` past the end answers `nil`, as it does for a vector.

```clojure
(println (persistent! (conj! (transient [1 2]) 3))) ; [1 2 3]
(let [v [1] t (transient v)] (conj! t 2) (println v (count t))) ; [1] 2
(println (persistent! (reduce conj! (transient #{}) [1 1]))) ; #{1}
```
