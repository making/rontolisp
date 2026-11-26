# clojure.datafy

Values turned into data, and navigation from data back to what it stands for, over the
protocols of `clojure.core.protocols`. Require `clojure.datafy` to use it;
`clojure.core.protocols` is loaded before the program, as in Clojure, so a qualified name
reaches it without a `require`. Both are Clojure source written for rontolisp from the
documented behavior of Clojure's namespaces, and run the same on every backend.

| Var | Behavior |
|---|---|
| `clojure.datafy/datafy` | `(datafy x)`: `x` as data through `Datafiable`; when that answers a new collection, its metadata holds `x` as `:clojure.datafy/obj` and the symbol of its class as `:clojure.datafy/class`. An exception answers [Throwable->map](throwable-to-map.md)'s map; an atom, ref or agent `[value]` with its metadata |
| `clojure.datafy/nav` | `(nav coll k v)`: what `v`, found under `k` in `coll`, stands for, through `Navigable`; `v` itself unless extended |
| `clojure.core.protocols/Datafiable`, `datafy` | The protocol behind `datafy`: `nil` and every other value answer themselves; extendable through metadata |
| `clojure.core.protocols/Navigable`, `nav` | The protocol behind `nav`, extendable through metadata |
| `clojure.core.protocols/CollReduce`, `coll-reduce` | `(coll-reduce coll f)` / `(coll-reduce coll f val)`: `reduce` of a collection by the collection itself. A record, deftype or `reify` extending it reduces through it under `reduce`, `into`, `transduce` and the other verbs built on `reduce` ([reduce](reduce.md)) |
| `clojure.core.protocols/IKVReduce`, `kv-reduce` | `(kv-reduce amap f init)`: `reduce-kv` of a collection by the collection itself, which `reduce-kv`, `update-vals` and `update-keys` reach for a record, deftype or `reify` extending it |
| `clojure.core.protocols/InternalReduce`, `internal-reduce` | `(internal-reduce s f start)`: `reduce` of a seq as a protocol |

A protocol declared extendable through metadata finds a method in the value's metadata under
the method's qualified symbol, ahead of the extensions:

```clojure
(require '[clojure.datafy :as d] '[clojure.core.protocols :as p])
(def conn (with-meta {:id 7} {`p/datafy (fn [c] {:connection (:id c)})}))
(d/datafy conn) ; => {:connection 7}
(::d/obj (meta (d/datafy conn))) ; => {:id 7}
(d/datafy (atom 5)) ; => [5]
(:cause (d/datafy (ex-info "boom" {:code 7}))) ; => "boom"
(defrecord Node [id])
(extend-protocol p/Navigable Node
  (nav [n k v] (if (= k :parent) (->Node v) v)))
(d/nav (->Node 1) :parent 0) ; => #user.Node{:id 0}
```

A type reduces through its own `CollReduce` or `IKVReduce` row:

```clojure
(require '[clojure.core.protocols :as p])
(defrecord Bag [items])
(extend-protocol p/CollReduce Bag
  (coll-reduce ([b f] (reduce f (:items b))) ([b f init] (reduce f init (:items b)))))
(reduce + 10 (->Bag [1 2 3])) ; => 16
(into [:x] (->Bag [1 2])) ; => [:x 1 2]
(deftype Pair [a b]
  p/IKVReduce
  (kv-reduce [_ f init] (f (f init :a a) :b b)))
(reduce-kv (fn [acc k v] (conj acc k v)) [] (Pair. 1 2)) ; => [:a 1 :b 2]
```

## Differences

- `iterator-reduce!` is not built in (it reduces a `java.util.Iterator`); naming it is an
  error that says so.
- `reduce` and `reduce-kv` consult `CollReduce` and `IKVReduce` for a record, deftype or
  `reify` only: an extension of either protocol to `nil`, `Object` or a core kind (a
  `String`, a map) is reached by calling `coll-reduce` or `kv-reduce` itself, while
  Clojure's `reduce` takes it for a collection that does not reduce itself (a string, a
  map). `reduce` does not consult `InternalReduce`.
- A namespace or class datafies to itself (Clojure answers a map of its members). An
  exception's map has no frames ([Throwable->map](throwable-to-map.md)).
