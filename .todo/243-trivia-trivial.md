# trivia loads and matches (trivia.trivial route, optimizer deferred)

Difficulty: 高 (levels 0-2 are heavy macrology over the freshly-built
substrate; expect unknown unknowns past the three known gates — recommend a
Fable-class model)

Part of the Mito milestone `.todo/238`. Blocked by `.todo/239` (reader
`',@`), `.todo/240` (symbol-macrolet), `.todo/242` (lisp-namespace).

## Goal

`(ql:quickload "trivia")` completes and `trivia:match` / `ematch` / `guard` /
`defpattern` work on all four backends — trivia is THE gate for both sxql
(`match` throughout) and mito (trivia in core).

## The optimizer decision (make it consciously, record it)

Upstream chain: system `trivia` -> `trivia.balland2006` (optimizer) ->
`trivia.trivial` -> level2 -> level1 -> level0. balland2006 additionally needs
**iterate** (a whole loop DSL) and **type-i** — a large, separate substrate
investment that buys only match-clause OPTIMIZATION, zero semantics.

Route for v1: a hand-authored override mapping system `trivia` to the
`trivia.trivial` contents (postmodern-deps.asd precedent). This is
upstream-sanctioned — trivia.trivial's own :description says extension
systems should depend on it "in order to avoid the circular dependency" — and
sxql/mito match semantics are identical, just unoptimized.

Divergence record (write into `.kb`): reason = balland2006 needs iterate +
type-i, neither loads today and neither adds semantics; re-evaluation trigger
= if a real consumer needs iterate itself (or match performance on the
interpreter becomes the bottleneck — note `.todo/182`, the interpreter
re-expands user macros every evaluation, which multiplies unoptimized match
cost), do iterate + type-i + balland2006 as their own milestone and delete
the override. type-i shares `.todo/239`'s reader blocker; that fix is already
in place for it.

## Known remaining gates (grep/probe evidence)

- closer-mop shim lacks `c2mop:compute-slots` and
  `c2mop:generic-function-lambda-list` (used by level2/derived+impl for
  structure/class patterns and lambda-list introspection);
  `slot-definition-initargs`, `slot-definition-name`, `ensure-finalized`,
  `class-slots` already exist. Extend
  `src/main/resources/am/ik/rontolisp/eval/closer-mop.lisp`.
- level1/level2 lean on `symbol-macrolet` shadowing rules — `.todo/240`'s
  shadowing tests should be written FROM these call sites.
- trivial-cltl2 loads today (probed); lisp-namespace comes from `.todo/242`.

## Acceptance

- `(ql:quickload "trivia")` (via the override) with unpatched cached sources.
- Smoke matrix on all four backends (ci-spec.yaml + unit tests): constant /
  variable / cons / list* / vector patterns, `guard`, `or`/`and` patterns,
  `ematch` failure signaling `match-error`, `defpattern`, a struct pattern
  and a class pattern (the shapes sxql/mito actually use — copy from
  sxql/src/operator.lisp and mito/src/core/dao.lisp).
- The interpreter-re-expansion cost measured once (a 1e5-iteration match
  loop) and the number written here for the re-evaluation trigger above.
