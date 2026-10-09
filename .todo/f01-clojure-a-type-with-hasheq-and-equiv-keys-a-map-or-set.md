# f01. Clojure: a type with `hasheq` and `equiv` keys a map or set

Difficulty: High

A deftype or reify implementing `IHashEq` and `equiv` (or `hashCode` and `equals`) is held by
identity as a map key or set member, where the oracle's hash map finds it by `Util.hasheq` and
`Util.equiv` (`.kb/clojure-frontend.md` "Structural keys", "Hashes"; a documented deviation).
Measured 2026-10-09 with data.priority-map 1.2.0, verbatim (its `compile-if` takes the
oracle's `hash-unordered-coll` branch since e87 and e88), the same on all four backends:

| program | oracle | here |
|---|---|---|
| `(contains? #{p} {:a 2 :b 1 :c 3})` | `true` | `false` |
| `(get {p :found} {:a 2 :b 1 :c 3} :missing)` | `:found` | `:missing` |

`(hash p)` and `=` already answer the oracle's (e88). instaparse's parse results
(`{:result afs :index i}` maps in a set, `gll.clj` `push-result`) are found through `=` because
a map is a structural key; a bare AutoFlattenSeq as a key would miss the same way.

## What decides the design

- `%clojure-structural-key-p` takes no typed value; `%clojure-hash` (the bucket hash, a 2^20
  fold, not the oracle's) answers 0 for one. A typed key must bucket with every `=` core value:
  a typed map with the core map of its entries, which the bucket hash of a map does not see.
- The bucket hash's speed is measured ("Structural keys"); Murmur3 for every key is not free on
  wasm (an int past 2^30 boxes).

## Plan

1. Measure on clj 1.12.6 which typed keys the oracle finds by value (`IHashEq` + `equiv`, a
   `hashCode` + `equals` pair, a collection interface without either) and against which core
   keys.
2. Bucket a typed value of a collection family by the bucket hash of its seq view as the core
   kind it is `=` to, and an `equals`/`hasheq` one by its own hash; measure the keyword-only
   and vector-key costs of "Structural keys" before and after.
