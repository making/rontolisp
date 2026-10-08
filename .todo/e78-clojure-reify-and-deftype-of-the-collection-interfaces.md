# e78. Clojure: `reify`/`deftype` of the collection interfaces

Difficulty: High

e71 gave a `reify`/`deftype`/`defrecord` body the `clojure.lang` interfaces whose one or two
methods a core verb reads (`IReduceInit`, `IReduce`, `IKVReduce`, `Seqable`, `Counted`,
`Indexed`, `ILookup`, `IFn`, `IDeref`, `IMeta`/`IObj`, `Object`'s overrides;
`.kb/clojure-frontend.md` "Host interfaces"). A body naming any other interface is refused by
name, which still stops every library implementing a collection type: instaparse's
`AutoFlattenSeq` and `FlattenOnDemandVector`, data.priority-map, core.rrb-vector, data.avl,
data.finger-tree, data.int-map, ordered, datascript, next.jdbc's mapified row.

Measured 2026-10-08 over 85 libraries (how many name each in a body): `IPersistentCollection` 9,
`IHashEq` 9, `Associative` 9, `Iterable` 8, `Reversible` 7, `IPersistentMap` 7, `Sequential`,
`java.util.Map`, `ISeq`, `IPersistentStack`, `IPersistentSet`, `IEditableCollection` 5 each,
`java.util.Set`, `MapEquivalence`, `Serializable`, `Iterator`, `IPending` 4 each, `Sorted`,
`java.util.List`, `IPersistentVector`, `Comparable`, `Collection` and the `ITransient*` 3 each.

## Plan

1. Measure on the oracle what each verb asks of these: `conj` (`cons`), `assoc`/`dissoc`
   (`Associative`, `IPersistentMap.without`), `empty`, `=` (an `IPersistentCollection`'s
   `equiv` on either side, `Sequential` for sequential equality, `MapEquivalence`), `hash`
   (`IHashEq`; no `hash` verb here yet), `peek`/`pop`, `rseq`, `contains?` (`containsKey`),
   `find` (`entryAt`), `count` of an `IPersistentCollection` that is no `Counted` (it walks the
   seq), `seq`/`first`/`next` of an `ISeq`, `realized?` (`IPending`), `subseq` (`Sorted`),
   `compare` (`Comparable`), the printer (a map or a seq), and the `java.util` and `Iterable`
   arms (`seq`, `count`, `reduce` through an iterator, which a `reify` of `java.util.Iterator`
   would have to answer on every backend).
2. Each group as a family of `ClojureInterfaces`/`ClojureArms`, like e71's: the verbs' arms
   folded for a program naming none.
3. `extend-protocol`/`extend-type` to an interface (`(extend-protocol P clojure.lang.Counted
   ...)`, refused as "needs a core type"): dispatch reaching a type implementing it through
   the same rows (e60 holds `Throwable` and `IRef`).
4. clojure-spec lines on all four backends; re-probe instaparse and data.priority-map.
