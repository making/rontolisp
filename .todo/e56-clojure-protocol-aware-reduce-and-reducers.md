# e56. Clojure: `reduce` through `CollReduce`/`IKVReduce`, and `clojure.core.reducers`

Difficulty: High

`clojure.core.protocols` ships (`Datafiable`, `Navigable`, `IKVReduce`, `InternalReduce`), but
`CollReduce`/`coll-reduce` is refused by name: `defprotocol` takes one parameter vector per
method here and `coll-reduce` has two. `reduce`, `reduce-kv`, `into` and `transduce` are
lowerings that consult no protocol, so a record, deftype or `reify` extending `CollReduce`
or `IKVReduce` (data.priority-map and instaparse extend `IKVReduce`, next.jdbc
`clojure.core.reducers/CollFold`) is not reduced through its extension.
`clojure.core.reducers` depends on both: its reducers are `reify` objects that only
`CollReduce` makes reducible.

## Plan

1. Multi-arity protocol methods in `defprotocol`/`extend-*`/`reify`/inline bodies.
2. `reduce`/`reduce-kv`/`into`/`transduce` dispatch to a `CollReduce`/`IKVReduce` extension
   for typed values, behind an arm stripped when the program extends neither (the
   `ClojureArms` way: a program that extends nothing stays byte-identical).
3. Ship `CollReduce`/`coll-reduce` in `clojure.core.protocols`, then
   `clojure.core.reducers`: `fold` split like the oracle's `foldvec` (halves until `n`,
   default 512), a map folded through `kv-reduce` (`(f k v)`), `foldcat`/`cat`/`append!`
   over a portable accumulator, `fjtask`/`pool` refused (no fork/join pool). Measured
   2026-10-08 on clj 1.12.6: `(r/fold 2 (fn ([] []) ([a b] (conj a b))) conj [1 2 3 4 5])`
   is `[1 2 [3 [4 5]]]`.
4. clojure-spec lines on all four backends; `doc/*/clojure/reference/`.
