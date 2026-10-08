# clojure.datafy

Values turned into data, and navigation from data back to what it stands for, over the
protocols of `clojure.core.protocols`. Require `clojure.datafy` to use it;
`clojure.core.protocols` is loaded before the program, as in Clojure, so a qualified name
reaches it without a `require`. Both are Clojure source written for rontolisp from the
documented behavior of Clojure's namespaces, and run the same on every backend.

| Var | Behavior |
|---|---|
| `clojure.datafy/datafy` | `(datafy x)`: `x` as data through `Datafiable`; when that answers a new collection, its metadata holds `x` as `:clojure.datafy/obj` and the symbol of its class as `:clojure.datafy/class`. An atom answers `[value]` |
| `clojure.datafy/nav` | `(nav coll k v)`: what `v`, found under `k` in `coll`, stands for, through `Navigable`; `v` itself unless extended |
| `clojure.core.protocols/Datafiable`, `datafy` | The protocol behind `datafy`: `nil` and every other value answer themselves; extendable through metadata |
| `clojure.core.protocols/Navigable`, `nav` | The protocol behind `nav`, extendable through metadata |
| `clojure.core.protocols/IKVReduce`, `kv-reduce` | `(kv-reduce amap f init)`: `reduce-kv` as a protocol a program extends for its own types |
| `clojure.core.protocols/InternalReduce`, `internal-reduce` | `(internal-reduce s f start)`: `reduce` of a seq as a protocol |

A protocol declared extendable through metadata finds a method in the value's metadata under
the method's qualified symbol, ahead of the extensions:

```clojure
(require '[clojure.datafy :as d] '[clojure.core.protocols :as p])
(def conn (with-meta {:id 7} {`p/datafy (fn [c] {:connection (:id c)})}))
(d/datafy conn) ; => {:connection 7}
(::d/obj (meta (d/datafy conn))) ; => {:id 7}
(d/datafy (atom 5)) ; => [5]
(defrecord Node [id])
(extend-protocol p/Navigable Node
  (nav [n k v] (if (= k :parent) (->Node v) v)))
(d/nav (->Node 1) :parent 0) ; => #user.Node{:id 0}
```

## Differences

- `CollReduce` and `coll-reduce` (a protocol method of two arities) and `iterator-reduce!`
  are not built in; naming one is an error that says so.
- `reduce` and `reduce-kv` do not consult `InternalReduce` or `IKVReduce`: an extension is
  reached by calling `internal-reduce` or `kv-reduce` itself.
- An exception datafies to itself (Clojure answers `Throwable->map`'s map), and a
  namespace or class to itself too.
