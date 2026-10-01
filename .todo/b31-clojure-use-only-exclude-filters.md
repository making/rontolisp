# Clojure: `use` ignores its `:only`/`:exclude` filters

Difficulty: Low (lowering-only: honor the parsed filter in `requireOne`).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673; rontolisp by code trace
of `ClojureLowering.requireOne`)

```clojure
(use '[clojure.string :only [upper-case]])
(try (join "," ["a"]) (catch Exception e :no-join)) ; => :no-join on the oracle
```

The oracle narrows: `join` is unresolvable. rontolisp parses `:only` but
`requireOne` checks `if (all)` first, so under `use` (`referAll=true`) every
`clojure.string` var is referred and the filter is silently ignored. The
`namespaces.md`/`use.md` docs already claim `(:only [...])` narrows.

## Design sketch

- In `requireOne`: when `only` is present it wins over `all` (intersect with the
  referred set); `exclude` subtracts in both modes. Same for the `ns` `:use`
  clause path (shared parser).
- No backend change; error when a listed name is not a known var (already the
  `only` behavior).

## Acceptance

- `ClojureLoweringTest`: `use` with `:only` refers the listed var and leaves an
  unlisted one unknown; `:exclude` subtracts under `use` and under `:refer :all`.
- `clojure-spec.yaml`: a narrowing case (all four backends).
- `.kb/clojure-frontend.md` `ns`/`require` row widened (filters honored).

## Depends on

b24 (quoted items, shared parser). Independent of b19/b25-b28/b30.
