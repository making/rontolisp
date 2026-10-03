# b94. Seq verbs the oracle answers lazily never answer over an infinite input

Difficulty: Medium

Measured 2026-10-03, oracle `clj` 1.12.6.1673 vs exec jar at `2ea187782`:

- `(take 3 (remove odd? (iterate inc 0)))`: oracle `(0 2 4)`; ronto never answers. The
  same for `keep`, `keep-indexed`, `map-indexed`, `distinct`, `interpose` and
  `partition`: each answers a strict list over the whole-collection view, which realizes
  an infinite input forever. `interleave` steps, so it stops at its shortest input, but
  over only infinite inputs it recurses until the stack overflows.
- `map`/`filter`/`concat`/`for` and the b57 verbs follow the lazy-or-strict rule (a lazy
  input answers a lazy seq, a strict one a strict list); these do not.

## Plan

- Give each verb a lazy arm under the same rule: `remove` as `filter` over the
  complement, `keep` as nil-dropping over `map`, `map-indexed`/`keep-indexed` over a
  counter, `distinct` with its seen table in the closure, `interpose`/`partition`/
  `interleave` as stepping wrappers. A strict input keeps its strict answer.
- Measure the raw wasm size of a strict-input program per verb (b21/b60 convention).

## Pin

- `clojure-spec.yaml` (all four backends): `(take n (verb ... (iterate inc 0)))` per verb,
  every line diffed against the oracle.
