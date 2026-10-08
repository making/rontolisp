# e79. Clojure: `clojure.core/iteration`

Difficulty: Medium

`iteration` (1.11) is an unknown name here. The oracle answers a `reify` of
`clojure.lang.Seqable` and `IReduceInit` over a step function: `seq` walks it lazily, and
`reduce` with an init, `into` and `vec` reduce it. e71 gave a body both interfaces
(`.kb/clojure-frontend.md` "Host interfaces"), so it can be built the same way. Measured
2026-10-08 (clj 1.12.6):

```clojure
(def it (iteration (fn [k] (when (< k 3) {:k k :v (* k 10)})) :initk 0 :kf (comp inc :k) :vf :v))
(vec it)        ; [0 10 20]
(seq it)        ; (0 10 20)
(reduce + it)   ; ClassCastException: no IReduce
```

## Plan

1. A failing clojure-spec line first.
2. `iteration` with its keyword options (`:somef`, `:vf`, `:kf`, `:initk` and the oracle's
   defaults): Clojure source over a `reify`, or a library worker storing the Seqable and
   IReduceInit rows itself (then a producer of both families).
3. `doc/*/clojure/reference/iteration.md` and the catalog row.
