# 385. The package-introspection doc block is stale, and nothing verifies it

Difficulty: Low

`doc/{en,ja}/reference/packages.md`'s "Package introspection" section quotes
`(length (rontolisp:list-functions))` as **367**; the answer is **394**
(2026-08-15). Its `list-macros` listing is stale too -- it is missing
`PPRINT-LOGICAL-BLOCK`, which the ci-spec's `list-*` case does carry.

The numbers drifted because **`DocExamplesTest` does not check this page**: the
```lisp fence carries `; =>` annotations, but the fixer/verifier only walks the
per-operator DETAIL pages (`reference/{functions,macros,special-forms}/*.md`),
so a guide/reference PAGE-level fence is written once and never re-measured.
Every one of those numbers is a hand-maintained copy of something the build can
compute, which is the shape that always goes stale.

## Acceptance

- The three listings and the count in `doc/en/reference/packages.md` and
  `doc/ja/reference/packages.md` match what the interpreter answers (same file
  set, byte-identical fences, only prose translated).
- Either the page's fences come under `DocExamplesTest` (the verifier walks
  page-level ```lisp blocks with `; =>` annotations, and
  `-Drontolisp.doc.fix=true` rewrites them), or the block is demoted to a
  ```console sample that no longer claims to be a runnable, current answer.
  The first is the point -- a number a test does not re-measure will drift
  again.
- Audit the other non-detail pages for `; =>` fences the verifier skips; they
  are the same hazard.

## Non-goals

- Widening `DocExamplesTest` to the whole `doc/` tree in one pass if the
  guides carry examples that cannot run headless (fetch/serve/stdin). Those
  belong in ```console blocks; the acceptance above is per-page.
