# Clojure: `vec` + collection verbs as values (shcloj4 follow-up)

Difficulty: Medium (lowering-only value lambdas + one call form, over the
existing table runtime; four-backend parity by construction).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673; transcripts in
`.todo/artefacts/b16-shcloj4-inventory/` probes `b18-vec`,
`b18-conjval`, `b18-assocval`, `b18-getval`, `gap-vec-eager`,
`gap-predicates`)

`vec` does not exist at all, and twelve collection verbs that lower in
call position have no function-value form, so any higher-order or
STM/agent use refuses with `unknown name`:

| Probe (oracle 1.12.6) | rontolisp today |
|---|---|
| `(vec '(1 2))` -> `[1 2]` | `unknown name: vec` |
| `(map conj [[1] [2]] [3])` -> `([1 3])` | `unknown name: conj` |
| `(map assoc [{:a 1}] [:b] [2])` -> `({:a 1, :b 2})` | `unknown name: assoc` |
| `(map get [{:a 1}] [:a])` -> `(1)` | `unknown name: get` |
| `(map dissoc [{:a 1}] [:a])` / `(map merge …)` / `(map disj …)` / `(map keys …)` / `(map vals …)` / `(map contains? …)` / `(map set …)` | `unknown name` each |

Corpus blocks: `eager.clj` `squares-seq` (`(vec (map square (range n)))`,
oracle `[0 1 4 9]`), `hangman/core.clj` `available-words`
(`(filter …) vec`), `snake.clj`/`atom_snake.clj` `add-points`
(`(vec (apply map + pts))`), `chat.clj` (`(alter messages conj msg)`,
`(commute messages conj msg)`), `concurrency.clj`
(`(commute messages conj msg)`), `introduction.clj`
(`(swap! visitors conj username)`).

## Design sketch

- `vec`: lower to the existing `vector`-over-seq-view shape (one new call
  case; `(vec nil)` is `[]`, `(vec "ab")` is `[\a \b]`, like the oracle).
- Value forms: extend the `valueOf` switch (`ClojureLowering.java`) with
  `assoc`/`dissoc`/`get`/`contains?`/`keys`/`vals`/`merge`/`conj`/`disj`/
  `set`/`hash-map`/`array-map` lambdas that route through the same
  call-position lowerings (the `update`/`select-keys`/`merge-with`/`into`
  value precedent). `conj`/`assoc` arities are variadic/rest.
- Out of scope (probed, absent, but no in-scope corpus use -- record only):
  `integer?`/`number?`/`keyword?`/`map?`/`set?`/`list?`/`fn?`/`seq?`/
  `char?`/`float?`/`decimal?`/`ratio?` (only `spec.clj`, a non-goal, uses
  any of them).

## Acceptance

- `clojure-spec.yaml`: `vec` of list/vector/string/set/nil, one case per
  new value form above (incl. `alter`/`swap!` over `conj`), green on all
  four backends.
- Corpus pins: `eager.clj` `squares-seq`, hangman `available-words`
  filter shape, snake `add-points`, chat `add-message` round-trip.
- `.kb/clojure-frontend.md`: `vec` row + value-form coverage widened on
  the `assoc`/`conj`/… rows; doc `clojure/reference` pages en+ja.

## Depends on

b16 (harness/probe pairs). Independent of b17-b22.
