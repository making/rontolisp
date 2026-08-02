# MitoE2eTest + documentation for mito / sxql

Difficulty: 中 (the ClPostgresE2eTest / PostmodernE2eTest / ClackE2eTest
shape is established; the work is careful assertion authoring and the
bilingual docs set)

Part of the Mito milestone `.todo/238` — the closing unit. Blocked by
`.todo/249`.

## E2E test

`MitoE2eTest`, opt-in via `RONTOLISP_POSTGRES_E2E=1` (reuse the existing
gate), Testcontainers `postgres:17-alpine`, following the established
per-leg pattern (`.todo/115`'s conclusions — container IP not alias for the
component leg, per-backend table names, wasmtime container on the PG
network):

- Three live legs — interpreter, JVM class, WASM component — asserting
  byte-identical output for: connect-toplevel, deftable, ensure-table-exists,
  insert/find/select (sxql where) /save/delete-dao, table-definition DDL
  text, and one migration diff cycle (per `.todo/249`'s filesystem-scope
  decision).
- The WASI Preview 1 pin: compile-time/call-time error, the ClPostgresE2eTest
  tenth-test shape.
- sxql `yield` pins live in ci-spec.yaml (pure computation, ALL backends
  including Preview 1) — added in `.todo/244`, verify they run under the
  native E2E driver here.

## Docs (both languages, same commit, byte-identical fences)

Per the asdf-library integration checklist and `.kb/documentation-site.md`:

- A mito library page: quickload, connect-toplevel (PostgreSQL only —
  state the MySQL/SQLite3 FFI limitation and the explicit
  `(ql:quickload "dbd-postgres")` requirement from `.todo/245`), deftable,
  CRUD, migration workflow, the trivia-optimizer note only if user-visible
  (match performance on the interpreter), the component-leg run flags
  (`-W gc=y -W exceptions=y -S tcp=y -S inherit-network=y`, IPv4 literal).
- An sxql page (or a clearly-linked section — decide against duplication,
  `.todo/244` deferred the decision here): yield, the statement builders,
  bind values.
- `DocExamplesTest`: runnable examples normalized with the
  `-Drontolisp.doc.fix=true` helper; DB-touching examples as static
  ```console blocks (the established convention for forms needing external
  services).
- Landing/nav updates (`doc/en/nav.yaml` + ja) and the library list page row.

## Milestone close-out

- Update `.todo/238` status; verify every unit's divergences got their
  reason + re-evaluation trigger into the matching `.kb` file (new
  `.kb/mito.md` or extend an existing one — one topic per file; the
  trivia-optimizer divergence from `.todo/243`, the dbi cache decision from
  `.todo/245`, the redefinition scope from `.todo/246`, the migration
  filesystem scope from `.todo/249`).
- Run the full After-Task checklist: format, `./mvnw test`, `-Pweb compile`,
  native E2E, javadoc zero-warnings.
