# Clojure: `(identical? v [])` is false for every empty vector, map and set

Difficulty: Medium

Found by the j07 probe round: specter 1.1.6 tests "no collected values" by identity with the
empty vector literal, the canonical `PersistentVector.EMPTY`:

```clojure
;; com/rpl/specter/impl.cljc:287 (macro compiled-traverse-with-vals*) and :298, :428 (terminal*)
(if (identical? vals# [])
  (result-fn structure)
  (result-fn (conj vals# structure)))
```

Here the test is always false, so every selected value comes back wrapped with its (empty)
collected values: `(select [ALL :a even?] data)` prints `[[2] [4]]` (oracle `[2 4]`),
`(select-one [:a :b] m)` `[7]` (oracle `7`), and `(transform [ALL (collect-one :b) :a] + data)`
calls `+` with a vector: `+: The value #(3) is not of type NUMBER`. Silent wrong answers, not a
refusal.

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(defn f [v] (identical? v []))
(prn (f []) (identical? [] []) (identical? {} {}) (identical? #{} #{})
     (identical? (pop [1]) []) (identical? (empty [1 2]) []) (identical? (vec nil) []))
```

- oracle: `true true true true true true false`
- interpreter and JVM class: `false false false false false false false`

The oracle's rule: the reader's `[]`, `{}`, `#{}` and every core operation that answers an empty
vector/map/set through `EMPTY` (`pop` of a one-element vector, `empty`; `(vec nil)` and
`(into [] nil)` are `false` in the oracle, measured) answer one shared object per
type; `'()` is `PersistentList.EMPTY` (here `()` is `nil`, the pinned empty-list deviation, and
`(identical? '() '())` is already `true`).

## Direction

Measure first which core verbs answer the canonical empty object in the oracle (`[]`, `{}`,
`#{}`, `(empty x)`, `(pop [x])`, `(disj #{x} x)`, `(dissoc {:a 1} :a)`, `(subvec v 0 0)`,
`(persistent! (transient []))`, `(vector)`, `(hash-map)`, `(hash-set)`, `(with-meta [] m)` -- the
last is NOT identical), then make the same ones share one per-program constant on every backend
(KB `.kb/quoted-data.md`: a quoted datum is one shared constant per quote site; this is one per
TYPE, across sites). Mutation safety: is any empty vector here ever mutated in place (transients,
KB "transients: one copy edited in place")? The shared one must not be.

## Done when

- The repro and the measured verb list print the oracle's answers on all four backends (ci-spec
  case).
- specter's probe lines 2, 5, 8, 9 print the oracle's lines without the `(empty? vals)` hunks of
  j07 `specter/patches.diff`.
