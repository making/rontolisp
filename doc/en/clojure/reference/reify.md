# reify

`(reify Protocol (method [target & args] body...) ...)`

Answers one fresh dispatch value per evaluation with a row per method in each
protocol's table: a single-shot map plus methods (never `proxy`, which stays the
`java:` surface). Each instance dispatches through its own tag, so two instances
are never `=` to each other, like the oracle; `=` is identity otherwise. Method
groups stand under protocol names, like `extend-type`. A method named again over
another parameter vector implements another of its arities.

```clojure
(defprotocol P (m [x]))
(def a (reify P (m [_] :a)))
(def b (reify P (m [_] :b)))
(println (m a))       ; :a
(println (m b))       ; :b
(println (= a a))     ; true
(println (= a b))     ; false
(println (satisfies? P a)) ; true
```

A `reify` of `clojure.core.protocols/CollReduce` is a collection `reduce`, `into` and
`transduce` reduce through its `coll-reduce`
([clojure.core.reducers](clojure-core-reducers.md) builds its reducers that way):

```clojure
(require '[clojure.core.protocols :as p])
(def three (reify p/CollReduce
             (coll-reduce [this f] (p/coll-reduce this f (f)))
             (coll-reduce [_ f init] (reduce f init [1 2 3]))))
(reduce + three) ; => 6
(into [] (map inc) three) ; => [2 3 4]
```

## Host interfaces

A body may also implement the `clojure.lang` interfaces the core functions consult, spelled
with their package or imported (`clojure.lang` is no default import, like the oracle's), and
override `Object`'s `toString`, `equals` and `hashCode`. A method is matched by its name and
parameter count against every group of the body, so a `toString` may stand under a protocol.

| Interface | Read by |
|---|---|
| `IReduceInit`, `IReduce` | `reduce` (without an init through `IReduce`), `into`, `transduce`, `run!`, `mapv`, `filterv`, `vec`, `set`, `group-by`, `frequencies` |
| `IKVReduce` | `reduce-kv`, `update-vals`, `update-keys` |
| `Seqable` | `seq` and every function over a seq (`first`, `map`, `filter`, `doseq`, `for` ...), `seqable?` |
| `Counted` | `count`, `empty?`, `counted?` |
| `Indexed` (a `Counted`) | `nth`, vector destructuring, `indexed?` |
| `ILookup` | `get`, `get-in`, a keyword's or symbol's call, map destructuring |
| `IFn` (with `Callable` and `Runnable`) | a call, a function argument (`map`, `filter` ...), `apply` (through `applyTo`), `ifn?` |
| `IDeref` | `deref`, `@` |
| `IMeta`, `IObj` | `meta`, `with-meta`, `vary-meta` (`deftype` only: a `reify` carries metadata itself) |
| `Object` | `str` and printing (`toString`), `=` (`equals`), `.hashCode` and `hash` without `IHashEq` (`hashCode`) |

`instance?` of the interface and an instance call of its method (`(.count x)`) reach the type
too, and so does a protocol extended to the interface. A method the body leaves out is the
oracle's `AbstractMethodError` when called (a `java.util` default method keeps the
interface's, refused by name when called), and any other interface (`IChunkedSeq`,
`java.util.Deque` ...) is refused by name. The Java interfaces a body implements (`Runnable`,
`Comparable`, `Iterable`, a `java.util` collection) and the `Object` methods it overrides are
what Java sees of the value too: it crosses into a Java member as an object implementing them
([Java interop](interop.md)). Deviation: a value overriding `toString` prints as
`#object[user$reify "text"]`, without the oracle's class number and identity hash.

```clojure
(def three (reify clojure.lang.Counted (count [_] 3)
                  clojure.lang.ILookup
                  (valAt [_ k] (get {:a 1} k))
                  (valAt [_ k nf] (get {:a 1} k nf))))
(count three) ; => 3
(:a three) ; => 1
(get three :b :none) ; => :none
(str (reify Object (toString [_] "custom"))) ; => "custom"
```

## Collection interfaces

A body implements a collection the core functions read through its methods, in the order the
oracle asks them, the interface's supers included (`IPersistentMap` is an `Associative`, an
`Iterable` and a `Counted`):

| Interface | Read by |
|---|---|
| `IPersistentCollection` | `conj`, `into`, `merge` (`cons`), `empty`, `=` on either side (`equiv`), `count` of one that is no `Counted` (its seq walked), `coll?` |
| `Associative` | `assoc`, `update`, `assoc-in` (`assoc`), `contains?` (`containsKey`), `find`, `select-keys` (`entryAt`), `associative?` |
| `IPersistentMap`, `MapEquivalence` | `dissoc` (`without`), `keys`, `vals`, `reduce-kv`, `map?`, printing as a map; a map's `=` reads it as a `java.util.Map` only with `MapEquivalence` |
| `IPersistentSet` | `disj` (`disjoin`), `contains?`, `get` and a keyword's call (`contains`, `get`), `set?`, printing as a set |
| `IPersistentStack` | `peek`, `pop` |
| `IPersistentVector` | `vector?`, printing as a vector, a vector's `=` (`count`, `nth`), `subvec` |
| `ISeq` | a `seq` answering one walks its `first` and `next`; `seq?`, printing as a seq |
| `Sequential`, `IPersistentList` | `sequential?`, `list?`; a sequential's `=` and `nth` walk its seq |
| `Reversible` | `rseq`, `reversible?` |
| `IPending` | `realized?` |
| `Sorted` | `subseq`, `rsubseq` (`seqFrom`, `seq`, `comparator`, `entryKey`), `sorted?` |
| `java.lang.Comparable` | `compare`, `sort`, a sorted collection's default order |
| `java.lang.Iterable` | the seq of one that is no `Seqable`, `reduce`, `into`, `vec` (`iterator`), `seqable?` |
| `java.util.Iterator` | [iterator-seq](iterator-seq.md), the seq and reduction of an `Iterable` |
| `java.util.Collection`, `List`, `Set`, `RandomAccess` | `count` (`size`), `nth` (a `RandomAccess` list's `get`), `contains?` (a `Set`'s `contains`), `=`, `pr` as a list, a vector or a set |
| `java.util.Map` | `get`, `contains?`, `find`, `count`, `seq` (`entrySet`), `=`, `pr` as a map |
| `IHashEq` | `hash` (`hasheq`), and the collection hash verbs over a member |
| `java.io.Serializable`, `IEditableCollection`, the transients | `instance?` and instance calls; `transient` calls `asTransient`, `persistent!` and the bang verbs the transient interfaces' methods |

`(.iterator coll)` of a core collection, `clojure.lang.SeqIterator` and `clojure.lang.RT/iter`
answer an iterator over its seq, and `clojure.lang.MapEntry` builds a `[k v]` vector, the
map entry here. Deviations: `first`, `next` and `rest` of an `ISeq` type read it through its
`seq` (the oracle calls its `first`, `next` and `more`), so `next` and `rest` answer that
seq's tail; a verb may call a method another number of times than the oracle (no chunked
seqs); `str` of such a value spells its contents where the oracle answers `Class@hash`, and a
`java.util` one prints its contents under `print` too.

```clojure
(deftype Pairs [m]
  clojure.lang.IPersistentMap
  (count [_] (count m))
  (seq [_] (seq m))
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf))
  (assoc [_ k v] (Pairs. (assoc m k v)))
  (without [_ k] (Pairs. (dissoc m k)))
  (iterator [_] (.iterator m)))
(def p (Pairs. {:a 1}))
(get (assoc p :b 2) :b) ; => 2
(map? p) ; => true
(reduce (fn [acc [k v]] (+ acc v)) 0 p) ; => 1
(pr-str (dissoc (assoc p :b 2) :a)) ; => "{:b 2}"
```

## Map keys and set members

A value whose type has a hash of its own (`IHashEq`'s `hasheq`, `Object`'s `hashCode`) and an
equality of its own (`IPersistentCollection`'s `equiv`, `Object`'s `equals`, or a map, set or
sequential interface the core collections read) keys a map or a set by value, as the oracle's
hash map holds it. A map, set or sequential type finds, and is found by, the core collection `=`
to it (a `data.priority-map` as a set member); any other type finds a value `=` to it through
its own equality. The key stored first stays, as for a core key. A type without a hash of its
own keys by identity, like the oracle's hash map, which hashes it by its identity.

Deviations: every map holds its keys as the oracle's hash map does, while the oracle's array map
(up to eight entries) compares keys without hashing, so it finds an `=` value of a type with no
hash of its own, and compares a lookup key that is no `java.util.Collection` or `Map` by
`equals` alone. A map, set or sequential type is bucketed by its contents rather than its
`hasheq`, so one whose `hasheq` disagrees with its `=` is still found; any other type is
bucketed by its own hash, so a core collection its `equiv` calls equal is not found.

```clojure
(deftype Money [cents]
  Object
  (equals [_ o] (and (instance? Money o) (= cents (.-cents ^Money o))))
  (hashCode [_] cents))
(contains? #{(Money. 5)} (Money. 5)) ; => true
(get {(Money. 5) :five} (Money. 5)) ; => :five
(deftype Pair [a b]
  clojure.lang.Sequential
  clojure.lang.Seqable (seq [_] (list a b))
  clojure.lang.IHashEq (hasheq [_] (hash [a b])))
(contains? #{[1 2]} (Pair. 1 2)) ; => true
(get {(Pair. 1 2) :pair} '(1 2)) ; => :pair
```
