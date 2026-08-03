# mito-migration + chipz crc32 slice + full `mito` + lack-middleware-mito

Difficulty: 中〜高 (assembly session: two small new pieces — a chipz slice
and the migration sources — then the umbrella system; the risk is
migration's diff logic exercising corners of everything below it)

Part of the Mito milestone `.todo/238`. Blocked by `.todo/247` (mito-core)
and `.todo/248` (esrap).

## Goal

`(ql:quickload "mito")` — the FULL system (mito-core + mito-migration +
lack-middleware-mito) — and the migration workflow against PostgreSQL:

```lisp
(mito:connect-toplevel :postgres ...)
(mito:deftable user () (...))
(mito:migration-status)        ; table diff detection
(mito:migrate "migrations/")   ; or mito.migration:migrate-table
(mito:generate-migrations "migrations/")
```

PostgreSQL only: the sqlite3-specific rebuild path
(migration/table.lisp:85-150) LOADS but is never taken; keep it that way.

## chipz: a crc32 slice, not the decompressor

migration/util.lisp imports EXACTLY `make-crc32`/`update-crc32`/
`produce-crc32` (advisory-lock id = crc32 of the migration dir). Follow the
ironclad-slice.asd precedent: a hand-authored `chipz`-named system over the
real chipz source file(s) that define crc32 (chipz-20230618-git — crc32 is
self-contained table-driven code; verify which files), reason written in the
.asd header. The full inflate/bzip2 machinery stays out until a real
consumer appears (write that trigger). chipz.asd's `defclass` parse issue is
`.todo/241`'s tolerance; with a hand-authored slice it may be moot — decide
there, don't do it twice.

## Watch list

- mito.asd: `(:version "dbi" ...)` dep (`.todo/241`), plus dep
  `(:feature :sb-package-locks "cl-package-locks")` — feature deps already
  parse; the feature is off here, correct either way.
- migration/versions.lisp: directory listing + `uiop:directory-files` shapes
  (`.kb/directory-listing.md`, the uiop shim), `read`ing migration files,
  `parse-statements` (esrap), advisory locks via
  `pg_advisory_lock` SQL (plain query — fine).
- migration/table.lisp:422 `reinitialize-instance :after` on dao-table-class
  — the REDEFINITION path from `.todo/246` item 3 is load-bearing HERE (a
  migration diff re-evaluates table definitions); if 246 spun it off, that
  follow-up blocks this todo, surface it early.
- lack-middleware-mito (mito-core + dbi only): a Lack middleware closure —
  the clack milestone machinery (`.kb/clack.md`) should carry it; smoke it
  inside a `lack:builder` app.
- Filesystem access on the WASM component leg: migration reads/writes
  `migrations/*.sql` through SourceLoader-independent runtime I/O — check
  what `.kb`/`.todo/222` (make-pathname on compile paths) implies; if
  file-based `generate-migrations` cannot work on the component, scope it to
  interpreter+JVM with the reason + trigger recorded, and keep the
  DB-side `migration-status`/`migrate-table` (no files) on all three.

## Acceptance

- `(ql:quickload "mito")` verbatim (with the declared hand-authored
  overrides only), zero userland workarounds.
- Migration round trip: deftable v1 -> ensure-table-exists -> modify the
  deftable (add a column, widen a varchar) -> `migration-status` reports the
  diff -> `migrate-table` applies it -> re-run reports clean. Pinned against
  `postgres:17-alpine` on the in-scope backends per the filesystem decision
  above.
- `generate-migrations` emits .sql files that `parse-statements` re-reads
  (esrap round trip through real output).
- lack-middleware-mito smoke inside a clack app (one request that queries
  through the middleware-provided connection).

## Findings handed over from `.todo/247` (2026-08-03)

- **Bare relational `:references` is broken**: `(deftable u3 () ((u1 :references u1)))`
  dies IDENTICALLY on every backend inside `create-table-sxql` — the column's
  `col-type` slot is unbound (`table-column-not-null-p` reads
  `%table-column-type` unguarded), i.e. `expand-relational-keys`' rewrite of a
  references column into the derived `<name>-id` col-type did not take effect.
  The col-type-carrying form `(u1-id :col-type :bigint :references (u1 id))`
  works and renders the right DDL, and is what the todo-247 acceptance
  ("relation-less `:references` column type accepted") pinned. Debug from
  `mito.class::expand-relational-keys` / `add-referencing-slots` (the rplacd
  ghost-marker path, `.kb/clos.md` todo-246 "fresh cells per evaluation").
- **`(:auto-pk :uuid)` untested end to end.** The uuid library loads and
  2-arg `random` + `make-random-state` landed (todo-247), so v1/v4 generation
  should run; nothing exercised a uuid-pk table against the DB yet.
- The DDL acceptance pins (serial auto-pk + record-timestamps, explicit
  `:primary-key`, references-with-col-type) ran as MANUAL probes
  (`create-table-sxql` with an explicit `:postgres` driver-type — no DB
  needed; `table-definition` itself reads the live connection's driver). Fold
  them into the automated `.todo/250` E2E as unit-style legs.
