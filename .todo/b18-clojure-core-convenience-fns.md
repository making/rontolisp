# Clojure: core convenience fns the book uses everywhere (shcloj4 basics)

Difficulty: Medium (all lowering + `clojure.lisp`/prelude over existing
primitives; no new value model. Random fns pin shapes, never values).

## Gap (every line verified 2026-10-01: oracle answers, rontolisp refuses
`unknown name: <fn>`)

| Probe (oracle 1.12.6) | rontolisp today |
|---|---|
| `(mapv inc [1 2 3])` -> `[2 3 4]` vector | `unknown name: mapv` (`eager.clj`, hangman `letters`) |
| `(filterv odd? [1 2 3 4])` -> `[1 3]` | `unknown name: filterv` |
| `(mapcat reverse [[1 2] [3 4]])` -> `(2 1 4 3)` | `unknown name: mapcat` (`eager.clj` `preds-seq`) |
| `(rand-int 5)` / `(rand 5)` / `(rand-nth coll)` / `(shuffle coll)` | `unknown name` (snake/apple, hangman players, `pi.clj`, `Thread/sleep (rand 500)`) |
| `(symbol "a" "b")` / `(keyword "a" "b")` / `(name :foo/bar)` -> `"bar"` / `(namespace :foo/bar)` -> `"foo"` | `unknown name` (`utils.clj`, `test.clj`, `import_static.clj`) |
| `(char 97)` -> `\a`, `(int \a)` -> `97` (`int` exists) | `unknown name: char` (hangman `letters`) |
| `(boolean 1)` -> `true` | `unknown name: boolean` |
| `(ffirst [[1 2]])` -> `1`, `(nfirst [[1 2 3]])` -> `(2 3)` | `unknown name` (book sequential idioms) |
| `(assert pred)` / `(assert pred msg)` | `unknown name: assert` (`index_of_any.clj` `^{:test ...}`) |

Oracle details that pin the semantics: `mapv`/`filterv` answer vectors
(`(type (mapv inc [1]))` is `PersistentVector`); `mapcat` is strict-ish
concat-of-maps over the seq view (nil-safe like `concat`); `rand-int` is
`[0,n)` ints, `(rand)` -> double `[0,1)`; `shuffle` answers a vector-ish
permutation (pin membership + count, never order); `name` works on
string/keyword/symbol, `namespace` answers nil when absent; `assert`
answers nil and throws on false (message form evaluated lazily).

## Design sketch

- `mapv`/`filterv`/`mapcat`/`ffirst`/`nfirst`/`boolean`/`char`/`name`/
  `namespace`/`symbol`/`keyword`/`assert`: lower to existing core
  (`vector`/`mapcar`/`remove-if`/seq view/`concatenate`/`%clojure-str-of`
  parts) or one `clojure.lisp` helper each; first-class values are the
  usual rest/count lambdas (the `nth`/`quot`-as-values precedent).
- `rand`/`rand-int`/`rand-nth`/`shuffle`: lower over the shared `random`
  generator (`.kb/random.md` -- draws from a program-owned generator, never
  a host call per draw); `rand-nth` empty signals like the oracle,
  `shuffle` is a Fisher-Yates over a copied vector.
- Keywords in `name`/`namespace` read the `(:C%KEYWORD spelling)` wrapper
  (split at the existing `/` rule, `::` left to b19).

## Acceptance

- One `clojure-spec.yaml` case per fn above (values pinned; random fns pin
  predicates/shapes + empty/singleton edges), green on all four backends.
- Corpus pins: hangman `letters` equals `(mapv char (range 97 123))`;
  `(mapcat vals [...])`; `rand-nth` membership.
- `.kb/clojure-frontend.md` rows + doc reference pages en+ja.

## Depends on

b16 (harness). b19 needs the `/`-split half of this item; otherwise
independent of b17/b20-b22.
