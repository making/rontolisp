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
| `Object` | `str` and printing (`toString`), `=` (`equals`), `.hashCode` |

`instance?` of the interface and an instance call of its method (`(.count x)`) reach the type
too. A method the body leaves out is the oracle's `AbstractMethodError` when called, and any
other interface (`ISeq`, `IPersistentMap`, `java.util.List` ...) is refused by name.
Deviation: a value overriding `toString` prints as `#object[user$reify "text"]`, without the
oracle's class number and identity hash.

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
