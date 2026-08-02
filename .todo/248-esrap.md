# esrap: the PEG parser library loads and parses

Difficulty: 高 (a large macro-DSL library — defrule compilation, CLOS
conditions, cached packrat state; independent of the rest of the milestone so
it can run any time, but expect a long unknowns tail)

Part of the Mito milestone `.todo/238` (substrate; blocked only by
`.todo/241` for its .asd `perform :after` feature pushes). Needed by
mito-migration on the POSTGRES path too: versions.lisp:365 `parse-statements`
splits migration `.sql` files into statements via the esrap grammar in
migration/sql-parse.lisp.

## Goal

`(ql:quickload "esrap")` (esrap-20260101-git; deps: alexandria +
trivial-with-current-source-form, both in the cache) and mito's actual
grammar works:

```lisp
(ql:quickload "esrap")
;; the defrule shapes from mito migration/sql-parse.lisp:
;; (~ "END") case-insensitive terminals, (and ...), (+ (or ...)), (not ...),
;; character-ranges, string, (function ...) transforms, :when guards
(esrap:parse '(* statement) "CREATE TABLE a (id int); CREATE INDEX ...;")
```

## Known specifics (grep/probe evidence, 2026-08-02)

- esrap.asd's `perform :after` pushes 6 capability features and `provide`s —
  handled by `.todo/241`; check whether esrap SOURCES read `#+esrap.*` before
  deciding they can be dropped.
- `define-compiler-macro parse` (src/interface.lisp:67): compiler macros are
  an OPTIMIZATION hook — if unsupported, loading must IGNORE the definition
  cleanly (check current behavior; `.kb/compiler-macros.md` exists — read it
  first), semantics come from the plain function.
- `load-time-value` — probed WORKING (interface.lisp:74, evaluator.lisp:31,
  results.lisp:370).
- trivial-with-current-source-form: not yet probed — likely trivial; probe
  first.
- esrap uses CLOS conditions with accessors (`esrap-parse-error`), `defstruct`
  caches, closures over rule objects, and a rule REGISTRY keyed by symbols —
  the symbol-identity rules (`.kb/packages.md`) matter for `defrule` at load
  vs `parse` at runtime on the compile backends (rules defined at
  library-load/compile time must be callable at runtime — the
  `.kb/symbol-runtime-api.md` seam if the registry funcalls interned names).

## Acceptance

- `(ql:quickload "esrap")` verbatim, zero workarounds.
- Unit tests: the mito sql-parse grammar loaded verbatim and
  `parse-statements` on a 3-statement SQL text with quoted strings,
  dollar-quoted `$$ ... $$` bodies and a `BEGIN...END` block (the exact
  reason this grammar exists) — identical output on all four backends
  (ci-spec.yaml case; the parser is pure computation, so even Preview 1 can
  run it — include it there, TCP is not involved).
- esrap's own README smoke example (a small arithmetic grammar) as a second,
  independent pin.
