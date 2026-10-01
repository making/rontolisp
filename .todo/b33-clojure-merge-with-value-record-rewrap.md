# Clojure: `merge-with` as a value drops the record rewrap

Difficulty: Easy (one runtime `rewrapAnswer` plus record-table reads in
`mergeWithValue`, mirroring `mergeWithForm`/`mergeValue`).

## Gap (by code trace of `ClojureLowering.mergeWithValue`, 2026-10-01;
oracle `clj` 1.12.6.1673)

The call path (`mergeWithForm`) grows a fresh table and rewraps it in the
first non-nil map's record (`rewrapAnswer(firstPresent(syms), acc)`), but the
value path (`mergeWithValue`) answers the raw accumulator table and maps over
each input directly instead of through its entry table. A record input to the
value form therefore misbehaves where a call keeps the type:

```clojure
(defrecord R [a])
(println (merge-with + (->R 1) {:a 2})) ; oracle #user.R{:a 3}
(println ((fn [f] (f + (->R 1) {:a 2})) merge-with)) ; oracle #user.R{:a 3}
```

Found while implementing b23 (which gave `merge` the same treatment its call
path gets, including the record rewrap). Fix by reading each rest map through
its entry table when it is a record (like `mergeValue`'s `onePlist`) and
rewrapping the accumulator in the first non-nil rest map's record (like
`mergeValue`'s `found` + `rewrapAnswer`).

## Acceptance

- `clojure-spec.yaml`: record-typed `merge-with` value pins (entry update plus
  type survival, e.g. through `select-keys`-style entry reads), green on all
  four backends.
- `.kb/clojure-frontend.md` `merge-with` row notes the value rewrap.

## Depends on

b15 (value form), b23 (the `mergeValue` precedent to mirror).
