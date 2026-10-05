# d32. A registry row for a value-only funcId keeps its function

Difficulty: Medium

The run-time name registry (`_lookup` on the JVM, the WASM registry blob) has a row for every
dispatchable funcId -- `valueFuncIds` included, not only the names a run-time designator can
spell (`DesignatorSpellings`). On the JVM a row is a value the registry makes, so a `#'name`
compiled only in dead code still keeps its function, and its dispatcher case, whenever the
registry is emitted, which is whenever any dispatcher is. Upper bound, measured by dropping
the registry's values altogether (unsound): the gate-on deep-learning examples a further
5-7 KB of class (`ch03/activation-functions` 66,609 -> 60,652 B), bench-report `string` 590 B,
`zlib` 999 B (`.kb/optimize-dead-code-elimination.md`, "Not narrowed: the registry's own
rows").

## Plan

- Decide soundness first: with the gate on (no data evaluator, no `--dynamic`), can a symbol
  naming a value-only funcId reach the registry by any path the probes do not read --
  package enumeration (`do-symbols`, `find-all-symbols`), a printed function name read back?
- If not: the rows become the name-armed set on BOTH backends together (the registries must
  answer the same names), the dispatcher cases stay on `valueFuncIds` plus those rows, and
  `_funName` keeps its own gate. Measure the corpora, bench-report, size-report and examples.
