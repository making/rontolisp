# g39: Value arms for head-only `clojure.core` functions (g15 slice)

Difficulty: Medium

Parent: `.todo/g15-remaining-unsupported-clojure-core-vars.md` (re-run
2026-10-22).

30 real functions resolve in head position but answer `unknown name` as a
value, so higher-order use fails while direct calls run: `(isa? A B)` works
but `(map isa? [1 2])` and `(def x isa?)` report `unknown name: isa?`
(same for `await`: `(await (atom 1))` runs, `(def x await)` does not; same
for `list*`). The full list (all head-only already in the 10-14 baseline --
no regression): `aget`, `alength`, `ancestors`, `aset`, `await`,
`await-for`, `ensure`, `extend`, `extends?`, `get-method`, `instance?`,
`isa?`, `list*`, `load`, `make-array`, `make-hierarchy`, `methods`,
`newline`, `pop-thread-bindings`, `prefer-method`, `prefers`,
`push-thread-bindings`, `remove-all-methods`, `remove-method`, `satisfies?`,
`shutdown-agents`, `struct`, `struct-map`, `descendants`, `parents`.

Not in scope here: `unquote`/`unquote-splicing` (reader syntax, no value
possible), `require`/`use`/`in-ns` (ns-wiring forms, head/top-level only by
design), `deliver`/`promise` (refused by name until g35 lands, which
resolves their values with the primitives).

## What to change

- Give each listed function a value arm in the value switch
  (`ClojureLowering.java:5490` region): explicit `case` rows or the
  family `valueOf` lookups (`ClojurePredicateLowering` for `isa?`/
  `instance?`/`satisfies?`, `ClojureHierarchyLowering.valueOf` beside the
  existing `derive`/`underive` row for the hierarchy family,
  `ClojureStateLowering` for `await`/`await-for`/`ensure`,
  per-slice `valueOf` for the rest), following the established shapes
  (`#'WORKER` lambdas, `%CLOJURE-SHARED-VALUE` for site-independent
  values, g16's expander rule where the name is a macro).
- Keep arities oracle-identical in both positions: a value applied to the
  wrong count reports the same `Wrong number of args` wording the head
  call reports (the `bit-and` pins at
  `ClojureLoweringTest.java:3397-3414` are the model).
- No per-verb behavior change in head position: existing spec rows and
  lowering pins stay green untouched; add value-position pins beside them
  (`(def x NAME)` lowers, `(map NAME ...)` runs).

## Acceptance

- `python3 -I probe4.py` re-run: every one of the 30 resolves in both
  positions; `value-not-head` stays exactly the 38 vars;
  `head-only` is exactly `require use in-ns unquote unquote-splicing`
  plus whatever of `deliver promise` g35 has not yet resolved.
- `clojure-spec.yaml` value-position rows (`(map isa? ...)`,
  `(def x await)` applied, higher-order `list*`/`struct`) diffed against
  `clj` on all four backends.
- `.kb/clojure-frontend.md` value-position notes updated where they claim
  head-only.
- Full suite green (merge `origin/develop` right before the run, one maven
  run per worktree, no `src/` edits during it).

## Non-goals

- The `deliver`/`promise` primitives themselves -- that is g35.
- New behavior for the 30 in head position; behavioral parity beyond the
  added pins.
