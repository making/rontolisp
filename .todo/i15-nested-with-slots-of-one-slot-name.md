# i15. Nested `with-slots` of the same slot name fails

Difficulty: Medium

Measured 2026-11-04, all four backends: `(with-slots (x) p (with-slots (x) q x))`. The inner binding
has to shadow the outer. log4cl's compile stops here; the walk is recorded in `.todo/152`.
