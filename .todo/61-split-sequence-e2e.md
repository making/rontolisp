# 61: Phase 3 unit 7 -- split-sequence end-to-end (integration target)

Part of the ASDF Phase 3 split (see `.todo/54-asdf-support.md`, "Phase 3"
section). Run LAST, after units 56-60 (the declarations unit is already DONE): load the real split-sequence v2.0.1
via `asdf:load-system` on all four backends and fix what still breaks.

## Steps

1. Vendor split-sequence v2.0.1 sources into a test-resources directory
   (license permitting -- MIT; keep the license header).
2. Its `.asd` uses `#.`, `:read-file-form`, `:if-feature`, `:in-order-to`:
   `#.` is already skip-with-warning in `.asd` files, `:if-feature` is
   supported; decide whether to tolerate-and-ignore `:read-file-form` (as a
   version value) and `:in-order-to` (test-op only) in `.asd` parsing rather
   than hard-error, since both only affect metadata/test wiring
   (`eval/AsdfSystems`).
3. `extended-sequence.lisp` is gated `:if-feature (:or :sbcl :abcl)` -- it
   must be skipped automatically (CLOS file).
4. Load + exercise `split-sequence`, `split-sequence-if`,
   `split-sequence-if-not` on strings and lists, verify on interpreter, JVM,
   WASM Preview 1, and `--component`.
5. Expected residual gaps to fix here (small, discovered in the 54
   investigation): `loop` clauses the second cut still misses, stray
   declarations, `check-type` specs not covered by the declarations unit's makeTypeTest (.kb/declarations-type-checks.md).
   Anything structural (CLOS, conditions) is out -- gate or document.
6. ci-spec: the compile path needs the `.asd` on disk, which the
   concatenated ci-spec driver cannot provide (same limitation as Phase 1),
   so cover with a dedicated JUnit E2E (LoadInlinerTest-style
   compile-and-run + LispEvaluator test) instead; add a plain-Lisp ci-spec
   case only for the exercised API shape if feasible without asdf.
7. Update `.todo/54` (Phase 3 -> DONE with notes), `.kb/asdf.md`, and the
   docs asdf page ("what can I actually load" section, both languages).
