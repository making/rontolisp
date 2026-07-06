# 54: ASDF support (limited) -- phased plan (Phases 1-3 DONE)

Goal: consume a slice of the CL ecosystem through ASDF-style system definitions
(`.asd` files, `asdf:load-system`), even if the supported subset is small.

## Status (2026-07-06)

- **Phase 1 DONE** -- mini-ASDF shim: `asdf` package, `asdf:defsystem` +
  `asdf:load-system` on all backends, `--system-path` +
  `RONTOLISP_SOURCE_REGISTRY` search path. Compile-path handling lives inside
  `LoadInliner`'s recursion (no separate `AsdfInliner`); shared parse/order
  logic in `eval/AsdfSystems.java`. `.asd` files parsed as plain data on both
  paths. Details: `.kb/asdf.md`.
- **Phase 2 DONE** -- reader + package gaps: `*features*` + `#+`/`#-`
  (`:and`/`:or`/`:not`) in the frontend lexer, `#|...|#`, `#:foo` designators,
  `#.` as a read error (tolerant skip-with-warning in `.asd`), package
  nicknames, `defpackage` `:import-from`/`:nicknames`, `:if-feature` on
  components. Details: `.kb/reader-features.md`, `.kb/packages.md`.
- **Phase 3 DONE** -- language gaps for the "simple library" tier, split into
  7 shipped units (declarations/eval-when/check-type; flet/labels; multiple
  values incl. the `%mv-spill` runtime channel; destructuring-bind + full
  defmacro lambda lists; string streams; runtime symbol API; split-sequence
  e2e). The REAL split-sequence v2.0.1 loads via `asdf:load-system` and works
  on all four backends including the second return value. Details in the
  linked `.kb/*.md` files; `SplitSequenceE2eTest` + ci-spec
  `split-sequence-residue-features`.
- **Beyond the plan** -- parse-number + cl-utilities also run on all 4 backends
  (todo 65); `ql:quickload` (limited Quicklisp subset, auto-download + cache)
  layered on top, all 4 backends (todo removed). See memories
  [[asdf-library-candidates]], [[quicklisp-quickload]].

## Verdict (unchanged)

Porting real ASDF (asdf.lisp + UIOP, ~15k lines) is not feasible: it needs
CLOS, conditions/restarts, special variables, the full pathname API, runtime
package manipulation, readtables, multiple values. The shipped path is an
**API-compatible mini-ASDF shim**: parse `.asd` as data, resolve
component/dependency order ourselves, drive the existing `load` machinery.

## Phase 4 (remaining -- medium libraries, still not real ASDF)

The next frontier. None started as of 2026-07-06:

- Condition system + `unwind-protect` (.todo/39)
- Dynamic/special variable binding (.todo/84) -- today `defvar` is a plain
  global, scoping is lexical-only; a deep evaluator/compiler change. Root cause
  behind .todo/82 and .todo/83.
- CLOS subset (.todo/40)
- Pathname layer (`merge-pathnames`, `probe-file`, `*load-pathname*`)
- Readtables (.todo/41)

Only after these does UIOP-lite territory make sense.

## Open questions (mostly settled by the implementation)

- Compile-path `load-system` requires literal system names (LoadInliner
  precedent) -- **yes**, as shipped.
- Registry: `./` + `--system-path` + `RONTOLISP_SOURCE_REGISTRY`;
  `ql:quickload` adds `~/.rontolisp/quicklisp`.
- Shim is all-Java (the compile path needs it before any Lisp runs).
