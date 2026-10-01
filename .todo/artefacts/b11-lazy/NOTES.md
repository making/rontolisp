# b11 decision spike: lazy seqs (recorded 2026-10-01)

## Why strict-only (b03) cannot spell the corpus

The b03 seq view (`ClojureLowering.seqForm`) coerces every collection to a STRICT
list up front (`lists pass through, vectors/strings coerce, maps contribute entry
vectors`). An infinite seq has no strict list: `(take 5 (iterate inc 0))` would
coerce an unbounded structure before `take` sees its first element, and the
`primes` sieve (`lazy-seq` + `cycle` wheel over an unbounded candidate stream)
cannot be spelled at all. Hence the named refusals (`lazy sequences are not
supported: lazy-seq`, `infinite range is not supported`). Option (2) from the
card -- keep refusal + eager `mapv`/`filterv` alternatives -- covers none of the
measured corpus (`lazy-seq` x6, `lazy-cat` x2, `cycle` x1, `repeat` x3,
`repeatedly` x1, `iterate` x4), so it is rejected.

## Chosen design: memoized-thunk wrapper in pure CL (option 1)

A lazy seq is `(LIST :C%LAZY cell)` where `cell` is `(CONS thunk-or-nil
realized-seq)`, built by `rontolisp::%clojure-make-lazy` and forced by
`rontolisp::%clojure-realize` (both `defun`s in the spliced `clojure.lisp`,
the b07 shape). Realization is `rplaca`/`rplacd` on the cell -- primitives
every backend already compiles (`JvmRplacaCompiler`, `WasmRplacaCompiler`) --
so the representation is identical on all four backends with NO new
per-backend compiler case, NO new `LispNames` entry, NO `BuiltinFunctionWrappers`
entry: a lowering-only change plus library `defun`s, which the adding-primitives
checklist expressly allows (prelude-function shape). No backend learns a
Clojure name: every new symbol is `rontolisp::%clojure-*`, referenced from
lowered core forms exactly like `%clojure-write-datum`.

- `lazy-seq` lowers to `(%clojure-make-lazy (lambda () <body-progn>))`; the
  thunk runs at most once per wrapper object (the `car` is cleared on first
  force). `lazy-cat` desugars to `(concat (lazy-seq e1) ...)` at datum time.
- `repeat`/`cycle`/`iterate`/`repeatedly` (infinite arities) lower to
  `%clojure-repeat` / `%clojure-cycle` / `%clojure-iterate` /
  `%clojure-repeatedly` helpers that build wrapper chains; the finite arities
  (`repeat n x`, `repeatedly n f`) answer STRICT lists, so they print like the
  oracle instead of `#<LazySeq>`.
- `seq`/`first`/`rest`/`next` lower through `%clojure-seq` (one-level realize;
  strict inputs take the old `cond` path verbatim). `take`/`drop` step through
  `%clojure-seq` per element, so `(take 5 infinite)` terminates with a strict
  prefix. `map`/`filter`/`concat`/`cons` test `%clojure-lazy-p` on the (top)
  input and answer a wrapper when lazy, the old strict form otherwise -- the
  invariant "any seq containing a lazy tail IS a wrapper" keeps the test
  top-level (consumers never see a strict cons with a lazy tail).
- Printer (`%clojure-write`, hence `println`/`pr`/`str` and the REPL echo):
  a wrapper -- or any value structurally containing one -- prints `#<LazySeq>`
  without forcing (realize-or-refuse: refuse). `%clojure-node-p` treats a
  wrapper as a leaf so the cycle walk never descends into a cell.

Out, documented: chunking (no 32-item chunks; every element realizes singly),
parallel realization, `delay`/`future` (b14), infinite `range` (reuses this
after b11), and lazy inputs to the non-listed verbs (`doseq`/`for`/`reduce`/
`keep`/etc. consume one level via `%clojure-seq`; pass a `take`n prefix --
the pre-existing "verbs assume the right collection kind" clause).

## Size measurement (per `.kb/size-measurement.md`: raw wasm bytes, `code` section)

Target number FIRST: raw bytes of the `code` section (deployment pays raw;
the change ADDS code rather than moving it, so gzip tracks raw here).

Measured 2026-10-01, x86-64 Linux, `take-5-iterate` probe
`(println (take 5 (iterate inc 0)))` vs strict-range probe
`(println (take 5 (range 0 100)))`, `-o probe.wasm`, `wasm-objdump -h`:

- strict-range probe baseline: code = 31,429 B (total 32,402 B).
- take-5-iterate probe: code = 38,349 B (total 39,348 B).
- Delta: +6,920 B code (+22%) for the lazy runtime reachable from one probe
  (make-lazy/realize/seq/take/iterate/call-dispatcher + printer leaf guard;
  `LibraryDefunPruner` drops repeat/cycle/repeatedly/concat-lazy/filter-lazy
  here). Per-verb marginal cost is one small defun each (all under 500 B of
  code).

The delta is the feature, not overhead: strict-only cannot spell the probe at
any size. Shipped because the same representation runs on all four backends
with no per-backend code.

## Alternatives rejected (measured or argued)

- Eager-only + refusal (option 2): covers 0 of the 17 corpus uses. Rejected.
- New per-backend lazy struct (a `LispLazy` value type + 4 print/hash/compare
  implementations): the b02 persistent-map argument -- a representation every
  backend prints, hashes and compares, for what pure CL already spells. Rejected.
- `delay`/`force` reuse for the cell: the repo has no CL `delay` primitive
  (only Scheme's `delay` syntax over `rontolisp::%scheme-force`); a bespoke
  two-slot cons cell is smaller and keeps the tag beside the set/keyword tags.
