# g41: closures over loop variables capture the cell

Difficulty: Medium

A closure created in a `dotimes`/`loop` body captures the variable's cell, so
every closure made in the loop observes the final value:

```clojure
(def fns [])
(dotimes [i 3] (def fns (conj fns (fn [] i))))
(println (mapv (fn [f] (f)) fns)) ; [3 3 3] here, [0 1 2] on clj 1.12.6
```

The oracle's `dotimes`/`loop` gives each closure the iteration's value (verified
on `clj` 1.12.6, 2026-10-22). The shape is the host's: a CL `dotimes` binds once
and assigns, and closures close over the cell. It predates the agent pool, which
only exposed it: with sender-thread drains every agent action ran before its
loop variable mutated, so `(send b (fn [v] (conj v i)))` printed `[0 1 2 3 4]`;
on the pool the actions observe the mutated cell. g38 works around it in its own
rows and tests with a `let`-bound copy per iteration.

## What to change

- Decide the semantics (per-iteration capture like the oracle, or a documented
  deviation) and implement it where `dotimes`/`loop` (and any other construct
  reusing one cell across iterations) lower.
- The fix must keep every backend identical and stay out of the agent pool's
  way; the g38 rows and tests using `let`-bound copies keep passing either way.

## Acceptance

- New tests: the snippet above prints `[0 1 2]` (or the deviation is pinned in
  `doc/en+ja/clojure/deviations.md` with a corpus row showing the cell capture).
- `clojure-spec.yaml` row, `.kb/clojure-frontend.md` loop section, en/ja docs
  mirrored in the same commit.
- Full suite green (merge `origin/develop` right before the run, one maven
  run per worktree, no `src/` edits during it).

## Non-goals

- The agent pool itself (g38); STM retry (g37); futures/promises (g35).
