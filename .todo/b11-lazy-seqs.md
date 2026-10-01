# b11 — lazy seqs (lazy-seq/lazy-cat + repeat/cycle/iterate/repeatedly)

Difficulty: High (new memoized-thunk value model on all four backends + printer interaction)

Status: open. The book's Ch.5 backbone; the frontend currently refuses all of
it by name (`lazy sequences are not supported: lazy-seq`, `ClojureLowering:1670`,
`infinite range is not supported`, `ClojureLowering:2655`).

Corpus: https://media.pragprog.com/titles/shcloj4/code/shcloj4-code.zip

## Gap (measured 2026-10-01)

- `lazy-seq` x6, `lazy-cat` x2, `cycle` x1, `repeat` x3, `repeatedly` x1,
  `iterate` x4. Representative uses:
  - `introduction.clj`: `(def fibs (lazy-cat [0 1] (map + fibs (rest fibs))))`
  - `functional.clj`: `lazy-seq-fibo`, `head-fibo` (holds-the-head warning),
    `by-pairs` (`lazy-seq` + `when-let`), `count-heads-*` over it.
  - `primes.clj`: full sieve over `lazy-seq` + `cycle` wheel.
  - `replace_symbol.clj`: `replace-symbol :collection` method (`lazy-seq` + `when (seq coll)`).
  - `lazy_index_of_any.clj`: `logging-seq` (`lazy-seq` + `when-let` + `cons`), `indexed` over `(iterate inc 0)`.
  - `male_female_seq.clj`: `(def m-seq (map m (iterate inc 0)))`.
  - `note.clj` comment: `(repeat 15 rand-note)`.

## Oracle (host `clj` 1.12.6, verified 2026-10-01)

```clojure
(take 5 (lazy-seq (cons 1 (lazy-seq (cons 2 nil))))) ; => (1 2)
(take 5 (repeat 3))            ; => (3 3 3 3 3)
(take 5 (cycle [1 2]))         ; => (1 2 1 2 1)
(take 5 (iterate inc 0))       ; => (0 1 2 3 4)
(take 3 (repeatedly #(7)))     ; => (7 7 7)
```

Laziness is observable: `logging-seq` prints only what is realized;
`(take n infinite)` terminates; unrealized tails never run.

## Scope

- Decision spike first (record in `.todo/artefacts/b11-lazy/`): strict-only
  (b03) cannot spell `(take 5 (iterate inc 0))` or `primes`. Options:
  1. Memoized-thunk seq struct (closure + atom cell, realized on `seq`/`first`/`rest`),
     one representation every backend prints/hashes/compares — the b02 cost argument.
  2. Keep refusal + eager `mapv`/`filterv` alternatives (does NOT cover the corpus).
- Minimal shippable if (1): `lazy-seq` macro (body memoized once), `lazy-cat`
  as nested `concat`, `repeat`/`cycle`/`iterate`/`repeatedly` over it, existing
  `take`/`drop`/`first`/`rest`/`next`/`seq`/`map`/`filter`/`concat` realize
  through it. Infinite `range` stays refused until this lands, then reuses it.
- Realization is at-most-once per seq object (memoized); `str`/`pr` of an
  unrealized infinite seq must NOT hang the printer (realize-or-refuse rule,
  documented).

Out: chunking (32-item chunks stay unimplemented, documented), parallel
realization, `delay`/`future` laziness (b14).

## Design constraints

- New runtime only if the spike measures it: same value model on all four
  backends (interpreter + JVM + both WASM), or the feature stays refused.
  No backend learns a Clojure name; new core helpers (if any) are `rontolisp:`-
  free CL over existing primitives where possible.
- `CompileFrontend.expand` order untouched; `clojure-spec.yaml` stays the
  concatenation slicer (infinite seqs only behind `take`, never a bare print).
- `.kb/adding-primitives.md` applies if new CL primitives are needed
  (checklist steps 1–7 + `BuiltinFunctionWrappers` for first-class `lazy-seq`
  fns); otherwise lowering-only.

## Acceptance

- Spike notes with measured sizes (wasm bytes for `take 5 (iterate inc 0)`
  vs strict `range`, per `.kb/size-measurement.md`) and the chosen design.
- New `clojure-spec.yaml` cases: `lazy-seq-memoizes-once`,
  `lazy-cat-fibs-prefix`, `take-over-repeat-cycle-iterate-repeatedly`,
  `logging-seq-realizes-only-take` — green on all four backends.
- `primes` prefix case (`(take 10 primes)` = first 10 primes) as the book-shaped
  proof; prints only via `take`.
- Docs: `doc/en+ja/clojure/{semantics,deviations,reference}.md` + lowering-table
  row in `.kb/clojure-frontend.md` (chunking absent, infinite print rule).
