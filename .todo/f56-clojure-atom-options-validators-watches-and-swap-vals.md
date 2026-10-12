# f56. Clojure: atom options, validators, watches, `swap-vals!`/`reset-vals!`, `await-for`

Difficulty: Medium

Found while writing `examples/clojure/state.clj` (2026-10-12, interpreter; every backend lowers
the same): the reference-type API stops short of what Clojure programs use routinely.

| program | clj 1.12.6 | here |
|---|---|---|
| `(atom 1 :validator pos?)`, `(atom 1 :meta {:a 1})` | an atom | `atom takes an initial value` (lowering refusal) |
| `(set-validator! a pos?)`, `(get-validator a)` | nil / the fn | `unknown name` |
| `(swap-vals! a inc)`, `(reset-vals! a 2)` | `[old new]` | `unknown name` |
| `(add-watch a :k f)`, `remove-watch` | the reference | refused by name: `atom watches need a design` (`ClojureLowering`, `.kb/clojure-frontend.md` lowering table) |
| `(await-for 10 ag)` | true/false | `unknown name` |

`ref` and `agent` already take `:validator` (`(ref 3 :validator #(>= % 0))` answers
`Invalid reference state` like the oracle), so the atom is the outlier.

## What decides the design

- Watches: the oracle calls each watch after the value is set, on the thread that set it, for
  atoms, refs (after commit), agents and vars; the key replaces an earlier watch of that key.
  The atom representation must carry a watch table without costing a program that never
  watches (the per-program strip `.kb/clojure-frontend.md` describes for other arm families).
- `swap!` across request threads is atomic (`f34`): `swap-vals!` must reuse that CAS loop.
- `await-for` over the synchronous agents (`agent.md`: no thread pool) answers true once the
  queue is run, or false where another thread still runs it past the timeout.

## Plan

1. clojure-spec rows for each line of the table, oracle-identical, four backends.
2. Atom options and validator, `swap-vals!`/`reset-vals!`, `await-for`; then watches for atoms,
   refs, agents and vars.
3. User docs (`doc/en` + `doc/ja` reference pages), `.kb/clojure-frontend.md` lowering table;
   extend `examples/clojure/state.clj` with them.
