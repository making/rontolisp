# clojure.core.reducers

Reducers and folders over the `CollReduce` protocol of `clojure.core.protocols`. A reducer
is a reducible view of a collection: it transforms the reducing function a reduction hands
it, so `(into [] (r/map inc (r/filter odd? v)))` builds no intermediate collection. A folder
is also foldable: `fold` reduces a vector in parts and combines the parts' results. Require
`clojure.core.reducers` to use it; it is Clojure source written for rontolisp from the
documented behavior of Clojure's namespace, and runs the same on every backend.

| Var | Behavior |
|---|---|
| `map`, `filter`, `remove`, `mapcat`, `flatten` | `(map f coll)`: the folder of `coll` stepping `(f x)` for each member `x` (a map's entries as `(f k v)`); `filter`/`remove` keep or drop by `(pred x)`, `mapcat` steps the members of each `(f x)`, `flatten` the members of any nesting of sequential collections. Without `coll`, a function awaiting it |
| `take`, `drop`, `take-while` | `(take n coll)`: the reducer of `coll` ending after `n` members, leaving out the first `n`, or ending at the first member `pred` rejects. Without `coll`, a function awaiting it |
| `reduce` | `(reduce f coll)` / `(reduce f init coll)`: like `clojure.core/reduce`, but `(f)` is the init when none is given, and a map or record reduces through `kv-reduce`, as `(f acc k v)` |
| `fold` | `(fold reducef coll)` / `(fold combinef reducef coll)` / `(fold n combinef reducef coll)`: a vector reduced in halves down to `n` members (512 by default), each part from `(combinef)`, the parts' results combined with `combinef` (`reducef` when none is given) in order; any other collection reduced once from `(combinef)`, a map or record through `kv-reduce`. `combinef` must be associative and `(combinef)` its identity |
| `CollFold`, `coll-fold` | The protocol `fold` dispatches through, extended to `nil`, vectors and `Object`; a folder implements it over its source |
| `reducer`, `folder` | `(reducer coll xf)`: `coll` as a reducible collection whose reductions hand their reducing function through `xf`; `folder` also folds |
| `cat` | `(cat)`: a fresh accumulator; `(cat left right)`: both collections in order, either alone when the other is empty; `(cat ctor)`: a combining function whose identity is `(ctor)` |
| `append!` | `(append! acc x)`: `x` added to the accumulator `acc`, which it answers |
| `foldcat` | `(foldcat coll)`: `(fold cat append! coll)`, the members of `coll`'s reduction in one accumulator |
| `monoid` | `(monoid op ctor)`: a combining function of `op` whose identity is `(ctor)` |

An accumulator counts, seqs, prints and reduces as a vector. A reducer is no seq: `seq`,
`count` and `first` of one are refused, like Clojure's.

```clojure
(require '[clojure.core.reducers :as r])
(into [] (r/map inc (r/filter odd? [1 2 3 4 5])))
; => [2 4 6]
(r/fold + (r/map inc (vec (range 1000))))
; => 500500
(r/fold 2 (fn ([] []) ([a b] (conj a b))) conj [1 2 3 4 5])
; => [1 2 [3 [4 5]]]
(r/reduce (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2})
; => 3
(into [] (r/take 2 (r/mapcat (fn [x] [x x]) [1 2 3])))
; => [1 1]
(r/foldcat (r/remove even? [1 2 3]))
; => [1 3]
```

## Differences

- There is no fork/join pool: `fold` reduces its parts one after the other, in order.
  `pool` and `fjtask` are not built in, and naming either is an error that says so.
- `cat` of two non-empty collections answers one accumulator holding both, where Clojure
  answers a `Cat`, a tree of the two whose fold folds each half; a fold of an accumulator
  reduces it whole, like Clojure's `ArrayList`. `->Cat` is not built in. An accumulator is a
  vector here (`vector?` is true; Clojure's `ArrayList` is no Clojure collection).
