# SxQL: `(ql:quickload "sxql")` + pinned SQL generation

Difficulty: 中〜高 (sources are plain defstruct + trivia macrology and the
risky primitives are pre-probed green, but the library is wide; unknowns
surface at load, not parse)

Part of the Mito milestone `.todo/238`. Blocked by `.todo/243` (trivia).

## Goal

sxql-20260101-git loads verbatim and `sxql:yield` produces pinned SQL text on
all four backends. Deps beyond trivia — alexandria, cl-package-locks — load
today (probed).

## Pre-probed GREEN (2026-08-02, don't re-derive)

- `#.` reader eval — sxql uses it pervasively (`#.(string :-op)`,
  `#.*package*` in operator.lisp/statement.lisp/clause.lisp). Works.
- BOA constructors with `&rest`:
  `(defstruct (sql-list (:constructor make-sql-list (&rest elements))))`.
  Works.
- Non-circular `#1=` labels. Works.

## Watch list (grep evidence, verify during the session)

- `subtypep` on STRUCT type names through `:include` chains —
  statement.lisp:304,317 `(subtypep type 'multiple-allowed-clause)` where the
  types are defstruct names in an `:include` hierarchy. Struct classes are
  standard-class metaobjects in the registry (`.kb/clos.md`); confirm subtypep
  walks their ancestry, else this lands in `.todo/246`'s subtypep work.
- `defmethod print-object` on STRUCTS (sql-type.lisp:135, compile.lisp:28) —
  `.kb/clos.md` says print-object is consulted; confirm for struct classes.
- defstruct `:include` + slot re-defaulting + `:print-function` variants
  across sql-type.lisp (`.kb/defstruct.md` is the contract).
- operator.lisp/statement.lisp build constructor names by `intern` at macro
  time and `find-constructor` at runtime — symbol-identity sensitive; the
  reader-case and package rules (`.kb/reader-case-upcase.md`,
  `.kb/packages.md`) should make this Just Work, but pin one
  `sxql:make-statement` round trip early.
- cl-package-locks is a no-op-shape library (loads today); `lock-package`
  effects are not required.

## Acceptance

- `(ql:quickload "sxql")` with unpatched cached sources, zero workarounds.
- Pinned `yield` outputs (unit tests + ci-spec.yaml across backends) for the
  shapes mito emits: `select` with `from`/`where` (incl. `:and`/`:or`/`:in`/
  `:like`), `order-by`, `limit`/`offset`, `left-join` with `:on`,
  `insert-into` with `set=`, `update`, `delete-from`, `create-table` with
  column options (the mito `deftable` output shape), `drop-table`,
  `alter-table`, and placeholder binding (`yield` returning the SQL string
  plus bind values as multiple values — check `.kb/multiple-values.md`
  coverage at the call sites).
- Docs: sxql is a library users will call directly; add the library page per
  the documentation checklist (or fold into the mito page in `.todo/250` —
  decide there, don't duplicate).
