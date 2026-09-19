# Scheme `values` reference: re-check the first-class `values` claim

Difficulty: Low

`doc/{en,ja}/scheme/reference/values.md` says that `values` used as a first-class
procedure (`(apply values '(1 2))`, `values` through a variable, inside `eval`) returns only
its first value on the compiled backends. Since the `%mv-spill` channel became exact
(`.kb/multiple-values.md`, 2026-09-19), the `#'values` wrapper is `values-list`, so this
may no longer be true.

Plan: run each of the three shapes on all four backends. If they now return every value,
fix the paragraph in both languages and pin the behavior in `scheme-spec.yaml`; if some
still return one value, keep the claim for exactly those and file the gap.
