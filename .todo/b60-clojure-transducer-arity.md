# b60. transducer arities: `(into to xform coll)` and the reducible HOFs

Difficulty: Medium

`transducers are not supported yet: into` -- `(into {} (map f) coll)` and
friends. The one-argument "transducer" arities (`(map f)`, `(filter pred)`,
`(take n)`, `(mapcat f)`, `(partition-all n)`...) are the same design behind
several `not supported yet` refusals (`mapcat` lone-function, `merge-with`
transducer).

## Scope decision to make first

A transducer here can be a tagged closure the consuming verbs interpret
(`:C%XFORM step-fn`), with `into`/`reduce`/`transduce`/`eduction` as the
consumers -- no `volatile`-mediated machinery needed for correctness, only for
the oracle's stateful transducer contract, which single-threaded lowering may
simplify (document the deviation if the completion step is skipped). Sequence
completion (`(fn) rf`) matters for `partition-all`/`take` transducers; decide
per the corpus cases, refuse the rest by name as today.

## Oracle

```bash
clj -M -e '(println (pr-str (into [] (map inc) [1 2 3])))
(println (pr-str (transduce (map inc) + [1 2 3])))
(println (pr-str (eduction (filter odd?) (range 6))))'
```

`(into to xform coll)` result equality is the pinned core; `xcomp` composition
`(comp (map a) (filter b))` of transducers must compose left-to-right like the
oracle.

## Acceptance

`clojure-spec.yaml` cases on all four backends for the pinned consumers;
lifting the lone-function refusal on `mapcat`/`merge-with` pinned in
`ClojureLoweringTest`.
