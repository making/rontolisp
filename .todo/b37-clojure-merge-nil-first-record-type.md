# Clojure: `merge`/`merge-with` with a leading `nil` keep a later record's type, the oracle does not

Difficulty: Medium (call path `mergeOf`/`mergeWithForm` plus value paths
`mergeValue`/`mergeWithValue` share the first-non-nil rule; the oracle uses the
first argument; pins and two doc pages move together).

## Gap (oracle `clj` 1.12.6.1673, measured 2026-10-01 during b33)

The merge family keeps a record's type when the FIRST NON-NIL map is a record
(`firstPresent` in `mergeOf`/`mergeWithForm`, `found` in `mergeValue`, and b33's
`mergeWithValue` fix which mirrors them). The oracle keeps it only when the
FIRST map is a record:

```clojure
(defrecord R [a])
(merge nil (->R 1))               ; oracle {:a 1} plain map, repo :R record
(merge-with + nil (->R 1) {:a 2}) ; oracle {:a 3} plain map, repo :R record
(merge (->R 1) nil)               ; oracle record -- both rules agree here
```

`conj` is unaffected (it keeps the target's type regardless, verified on the
oracle the same day). No spec pins a nil-first record input today, so nothing
is red; `merge.md` / `merge-with.md` (en+ja) and the `.kb/clojure-frontend.md`
`merge` / `select-keys`-`merge-with` rows state the first-non-nil rule "like
the oracle", which the measurement contradicts for nil-first inputs.

## Acceptance

- `clojure-spec.yaml`: nil-first record pins for `merge` and `merge-with` in
  call AND value positions (plain-map answers), green on all four backends.
- Call and value paths agree with each other and the oracle; the three doc
  surfaces above state the first-argument rule.

## Depends on

b23 (the `mergeValue` precedent), b33 (the `mergeWithValue` mirror to move
together).
