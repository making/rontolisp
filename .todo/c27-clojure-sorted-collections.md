# c27. Clojure sorted collections (`sorted-map`, `sorted-set`, `subseq`, ...)

Difficulty: High

All unknown names (2026-10-03, exec jar at `093172641`):
`sorted-map`, `sorted-map-by`, `sorted-set`, `sorted-set-by`, `subseq`, `rsubseq`, `vector-of`.

Oracle (`clj` 1.12.6): `(sorted-map :b 1 :a 2)` -> `{:a 2, :b 1}`, `(sorted-set 3 1 2)` -> `#{1 2 3}`,
`(subseq (sorted-set 1 2 3) > 1)` -> `(2 3)`, `(vector-of :int 1 2)` -> `[1 2]`.

Needs a value representation for sorted maps/sets that every existing collection operation understands:
`assoc`/`dissoc`/`conj`/`disj`/`get`/`contains?`/`keys`/`vals`/`seq`/`first`/`rseq`/`count`/`=`/`hash`/printing/`into`/`reduce`/destructuring, structural keys (`.kb/clojure-frontend.md` "Structural keys"), and `compare`-based ordering with a custom comparator for the `-by` variants.
Equality with unsorted maps/sets follows the oracle (`(= (sorted-map :a 1) {:a 1})` -> true).
`vector-of` may answer an ordinary vector if the oracle-visible behavior matches; record what differs.

Decide the representation by measuring: the wasm size and speed of programs that use no sorted collection must not change.
Pin in `clojure-spec.yaml` on all four backends, and add `doc/{en,ja}/clojure/reference` pages with catalog entries.

The type predicates answer `false` for every value today (no sorted collection exists):
`sorted?` must become true of both kinds, `reversible?` true of both (the oracle's `rseq` takes them),
and `map?`/`set?`/`coll?`/`seqable?`/`counted?`/`associative?` (map only)/`ifn?` must see them
(`ClojurePredicateLowering`, the `%clojure-is-*` helpers in `clojure.lisp`).
