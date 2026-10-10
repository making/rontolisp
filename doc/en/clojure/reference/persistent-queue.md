# PersistentQueue/EMPTY

`clojure.lang.PersistentQueue/EMPTY`

The empty persistent first-in, first-out queue, also reached as `PersistentQueue/EMPTY` once
`clojure.lang.PersistentQueue` is imported. [`conj`](conj.md) and [`into`](into.md) add at the
rear, [`peek`](peek.md) answers the front member and [`pop`](pop.md) the queue without it (of an
empty queue `nil` and the queue itself), `seq` walks front to rear. A queue is a sequential
collection: `=` compares it member by member with a vector or list, `hash` is theirs, `count`,
`empty?` and every seq verb read it, `with-meta` and `meta` carry metadata, `empty` answers the
empty queue. `instance?` is true of `clojure.lang.PersistentQueue` and of the interfaces the
oracle's class implements (`IPersistentList`, `IPersistentStack`, `java.util.Collection` ...),
whose `.size`, `.contains` and `.isEmpty` it answers. `str` is the oracle's
`clojure.lang.PersistentQueue@` and the hex of its `hashCode`. The class is a `deftype` of a
built-in namespace, loaded where a program first names it, so a program naming none carries
none of it.

Deviation: a queue prints as the oracle's `#object` without the identity hash, and `class`
answers the type's keyword `:PersistentQueue`.

```clojure
(def q (conj clojure.lang.PersistentQueue/EMPTY 1 2 3))
(println (peek q) (seq (pop q)) (seq (conj q 4))) ; 1 (2 3) (1 2 3 4)
(println (= q [1 2 3]) (count q) (str q)) ; true 3 clojure.lang.PersistentQueue@7861
```
