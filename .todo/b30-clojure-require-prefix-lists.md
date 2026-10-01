# Clojure: bare `require`/`use` and `ns` reject prefix-list parens

Difficulty: Low (lowering-only: extend the shared `requireSpecs` parser).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673; rontolisp by code trace
of `ClojureLowering.requireSpecs`)

The oracle accepts prefix lists:

```clojure
(require '(clojure [string :as s]))
(println (s/join "," ["a" "b"])) ; => a,b on the oracle
```

rontolisp refuses with `require takes library specs, not ...`: `requireSpecs`
only reaches its prefix-list branch for a *vector* whose first element is not a
symbol (dead shape -- a real prefix list is a *list* datum, which the
`!isVectorDatum` guard throws before the branch). The `requireSpecs` doc comment
already claims prefix lists are supported.

## Design sketch

- In `requireSpecs`, after `unwrapQuote`: a list datum whose head is a plain
  symbol is a prefix list -- each member (bare or quoted) a symbol or a vector
  libspec resolved under the prefix, sharing `requireOne`.
- Keep refusing anything else with the same message.

## Acceptance

- `ClojureLoweringTest`: quoted and unquoted prefix lists wire (`:as`,
  `:refer`, bare members) on the shared path; refusal for a non-symbol head.
- `clojure-spec.yaml`: a prefix-list case (all four backends).
- `.kb/clojure-frontend.md` `ns`/`require` row widened.

## Depends on

b24 (quoted items, shared parser). Independent of b19/b25-b28.
