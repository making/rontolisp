# b64. `clojure.spec`: subset or documented refusal -- decide

Difficulty: High

`unknown namespace: clojure.spec.alpha` -- a corpus program specs its functions
(`s/def`, `s/fdef`, `s/valid?`, `s/conform`, ...).

First decision, before any code: is a spec subset worth its runtime? The
honest minimal core is `def`/`valid?`/`conform`/`explain` over regex ops
(`cat`, `alt`, `*, +, ?, &`) and predicates -- a small interpreter over spec
values. Below that, keep the named refusal and record the decision here.

If GO:

- spec values as tagged closures; registry keyed by qualified keyword.
- `instrument`/`check` (test.check property runner) are a separate follow-up --
refuse by name in this item.
- The registry must resolve `::keys` against the file `ns` like any auto-resolve
(b13 already does).

## Oracle

```bash
clj -M -Sdeps '{:deps {org.clojure/spec.alpha {:mvn/version "1.3.4"}}}' \
    -e '(require (quote [clojure.spec.alpha :as s]))
(s/def ::n int?) (println (s/valid? ::n 1)) (println (pr-str (s/conform ::cat [::n ::n] [1 "a"])))'
```

(compose the exact probe in a scratch session; pin `valid?` booleans,
`conform` shapes and `explain-data`'s problem list shape -- or drop
`explain-data` from the subset and pin its refusal.)

## Acceptance

Decision recorded in `.kb/clojure-frontend.md` either way; if GO, `clojure-spec.yaml`
cases on all four backends and the registry/macroexpansion shapes in
`ClojureLoweringTest`.
