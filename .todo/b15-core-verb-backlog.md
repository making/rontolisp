# b15 — core verb backlog (seq/string/map/HOF + IO entry points)

Difficulty: Low (one `case` + value-lambda + spec case per verb, no new runtime, sliceable)

Status: open. Everything in this file already has a lowering slot
(`ClojureLowering:1523-1668` value/call tables); each row below is one more
`case` + value-lambda + spec case, no new runtime.

Corpus: https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip

## Gap (measured 2026-10-01; all `unknown name` today)

Seq/HOF (by use): `keep`/`keep-indexed` (`index_of_any.clj` — the book's
worked example), `map-indexed` (`exploring.clj` `indexed`), `every?`
(`introduction.clj` `blank?`, `hangman` `valid-letter?`), `some` (`primes.clj`),
`remove`, `distinct`/`partition`/`take-while`/`drop-while` (`functional.clj`
`count-runs`, `primes.clj` wheel), `interleave`/`interpose`/`zipmap`/`group-by`/
`sort`/`sort-by`, `butlast`/`last`/`second` (`snake.clj` `move`),
`concat` already done.
Map: `update`/`update-in`/`assoc-in`/`get-in` (`note.clj` octave walk),
`select-keys`/`merge-with` (`sequences.clj` demos), `into` (`pi.clj`,
`eager.clj` `squares-into`/`preds`), `frequencies`.
Fns: `comp`/`partial`/`complement`/`constantly`/`identity` (`functional.clj`
`count-if`/`faux-curry`, `spec.clj` `opposite`), `memoize` (`male_female*`,
`concurrency.clj`), `trampoline` (`trampoline.clj` — `#(...)` self-return),
`apply` already done.
Preds/casts: `coll?`/`string?`/`symbol?`/`instance?`/`class` (`replace_symbol`,
`life_without_multi`, `instant.clj`), `int`/`long` (`interop.clj` perf trio,
`note.clj` `Thread/sleep`), `unchecked-add` (`interop.clj`).
Reader/sugar: `when-let`/`if-let`/`when-not`/`when-first` (`lazy_index`,
`utils.clj`, `functional.clj`), `if-not`, `comment` already done.
String/IO: regex `#"..."` + `str/split` on patterns (`exploring.clj`
`ellipsize`/`indexable-words` split on `#"\W+"`/`#"\s+"`, `sequences.clj`
`re-matcher`/`re-find` demo) — vs the documented literal-only position (b08);
`spit`/`slurp`/`line-seq`/`file-seq`/`reader` (`eager.clj`, `sequences.clj`
`clojure-loc`, `hangman` `words.txt`), `format` (already CL, needs Clojure-arg
check in `introduction.clj`/`interop.clj`).

## Oracle (host `clj` 1.12.6, verified 2026-10-01)

```clojure
(keep-indexed (fn [i x] (when (odd? x) i)) [10 11 12]) ; => (1)
(map-indexed vector [:a :b])   ; => ([0 :a] [1 :b])
(partition 2 1 [1 2 3])        ; => ((1 2) (2 3))
(distinct [1 1 2])             ; => (1 2)
(interleave [1 2] [:a :b])     ; => (1 :a 2 :b)
(zipmap [:a :b] [1 2])         ; => {:a 1, :b 2}
((comp inc inc) 5)             ; => 7
((partial + 10) 5)             ; => 15
```

Note: `(keep inc [1 nil 2])` throws NPE on the oracle (nil is not a number) —
pin the signal, not a silent skip.

## Scope (slice order: seq → map → HOF → preds → sugar → IO)

- Each verb lowers over the existing seq view / b02 table / CL arithmetic;
  strict lists, `nil`-for-empty, table walk order unspecified (b03/b02 rules).
- `when-let`/`if-let`/`when-not`/`if-not` as `let`+`if` datum rewrites with
  destructuring reuse; `trampoline` as a self-call loop (thunk = 0-arg `fn`).
- `comp`/`partial`/`memoize` as closures over the atom cell (memoize shares
  the b05 cell design); `complement`/`constantly`/`identity` trivial.
- `update(-in)`/`assoc-in`/`get-in`/`select-keys`/`merge-with`/`into` as fresh
  tables/vectors (copy-on-write, b02 rule); `frequencies` as one pass.
- `spit`/`slurp`/`line-seq` only if the `eval` IO layer already has them for
  CL; `clojure.java.io`/`clojure.xml`/`clojure.spec` namespaces stay
  `unknown namespace` (documented out — `spec.clj`/`hangman/specs.clj` excluded).
- Regex stays literal (b08 decision reaffirmed): `#"..."` refused at the
  reader, `split`/`replace` literal; corpus regex uses get reader-refusal pins,
  not silent wrong answers.

## Design constraints

- Lowering + `clojure.lisp` prelude preferred; new CL primitives only via
  `.kb/adding-primitives.md` checklist (steps 1–7 + first-class wrapper).
- No `CompileFrontend.expand` restatement; four-backend `clojure-spec.yaml`
  cases per verb (single-entry maps only where printing pins).
- Value-position lambdas for every new verb (`map`/`filter`/`apply` take them
  bare, like `inc`/`quot`/`nth` already do).

## Acceptance

- Port `index_of_any.clj` (`keep-indexed` version), `exploring.clj`
  (`indexed`/`ellipsize` sans regex), `functional.clj` (`count-runs`),
  `note.clj` (`update-in` slice) into `clojure-spec.yaml`, green on all four.
- `ClojureLoweringTest` pins regex + `clojure.spec/xml/java.io` refusals.
- Docs: per-verb `doc/en+ja/clojure/reference/*.md` + `_catalog.yaml` +
  `.kb/clojure-frontend.md` lowering-table rows; `deviations.md` keeps the
  literal-regex + strict-seq entries.
