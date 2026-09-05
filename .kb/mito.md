# Mito (DAO + schema migration) on rontolisp

**Invariant**: `(ql:quickload "mito")` loads the FULL system — `mito-core` +
`mito-migration` + `lack-middleware-mito` — from unpatched Quicklisp dist sources, and the
PostgreSQL DAO + migration workflow behaves identically on the interpreter, the JVM and the
WASM `--component` backend. WASI Preview 1 is out by design (no TCP, `.kb/tcp-sockets.md`).
PostgreSQL is the only driver; `mito/src/core/db/{mysql,sqlite3}.lisp` load but are never
selected.

Substrate: `.kb/asdf.md` (trivia, sxql, cl-dbi/dbd-postgres, chipz), `.kb/clos.md` (MOP
widening), `.kb/packages.md` (`uiop:define-package` `:use-reexport`).

## Scope: `generate-migrations` is interpreter + JVM only
The DB-side workflow (`migration-status`, `migration-expressions`, `migrate-table`,
`migrate`) runs on all three in-scope backends. WRITING migration files does not:
`ensure-directories-exist` and `delete-file` are call-time errors on WASM
(`.kb/read-load-streams.md`). Closing it needs a tenth preview1 import
(`path_create_directory` / `path_unlink_file`), which shifts every emitted function index
and needs `adapter.wat` + `adapter-http-server-p1.wat` + the `--no-wasi` trap stubs in step.


## Upstream defects reproduced faithfully (do NOT "fix" here)
Each checked against SBCL 2.2.9 on the same sources; rontolisp fails identically.
- Bare relational `:references` dies with an unbound `col-type` slot
  (`mito/src/core/class/table.lisp:52`, `column.lisp:151-153`). Use
  `(other-id :col-type :bigint :references (other id))`.
- `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT <v>` emits `DEFAULT ?` with an EMPTY bind
  list (`sxql/src/statement.lisp:411-416`). Workaround: bind `sxql:*use-placeholder*` to nil
  around `mito.migration:migrate-table`.
- `mito.db:column-definitions` mis-reports `:default` (`postgres.lisp:60` joins `pg_attrdef`
  on `adrelid` only); mito neutralises it with `omit-default`.

## Known rontolisp gaps in mito's surface
- Relational `:col-type <class-name>` needs runtime method construction
  (`ensure-generic-function` / `add-method`), out of scope per `.kb/clos.md`.
- `deftable` conc-name accessors (`user-name`) are never defined — `expandDefclass`
  generates accessors from the ORIGINAL defclass form, which carried no `:readers`.
  `slot-value` works.
- A metaclass-driver slot definition carries the PACKAGE-STRIPPED name, so mito's
  `find-slot-by-name` misses a caller-package symbol and `:references` silently skips
  inheriting the referenced column's type — wrong DDL, no error.
- **`count-dao` — and every sxql SQL FUNCTION operator (`:count`/`:sum`/`:max`/any
  `(:some-function ...)`) — is interpreter-only.** sxql's `find-make-op` expects `find-symbol`
  to answer nil for an unknown name; the compiled backends answer a SYMBOL
  (`.kb/symbol-runtime-api.md`), so the `make-function-op` fallback never runs and
  `symbol-function` signals. Tripwire `MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends`
  ASSERTS the failure — closing the gap turns it red.

## Tests
`MitoE2eTest` — opt-in via `RONTOLISP_POSTGRES_E2E=1`, Testcontainers `postgres:17-alpine`,
`PostmodernE2eTest` shape: DAO round trip and DB-side migration diff cycle asserted
BYTE-IDENTICAL on all three in-scope backends, plus the Preview 1 compile-error pin and the
`count-dao` tripwire. `lack-middleware-mito`'s three branches covered (`.kb/clack.md`).
Docs: `doc/{en,ja}/guides/mito.md`, the mito row in `guides/asdf-systems.md`.
