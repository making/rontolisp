# Mito (DAO + schema migration) on rontolisp

**Invariant**: `(ql:quickload "mito")` loads the FULL system — `mito-core` +
`mito-migration` + `lack-middleware-mito` — from unpatched Quicklisp dist sources, and the
PostgreSQL DAO + migration workflow behaves identically on the interpreter, the JVM and the
WASM `--component` backend. WASI Preview 1 is out by design (no TCP, `.kb/tcp-sockets.md`).
PostgreSQL is the only driver; `mito/src/core/db/{mysql,sqlite3}.lisp` load but are never
selected.

Substrate: `.kb/asdf.md` (trivia, sxql, cl-dbi/dbd-postgres, chipz), `.kb/clos.md` (MOP
widening), `.kb/packages.md` (`uiop:define-package` `:use-reexport`).

## Scope: `generate-migrations` file writing is real on all three in-scope backends
The DB-side workflow (`migration-status`, `migration-expressions`, `migrate-table`,
`migrate`) runs on all three in-scope backends. WRITING migration files does too since
.todo/257 landed the `path_create_directory` / `path_unlink_file` imports (the
thirteenth and fourteenth of fifteen, adapter + http-server bridge + `--no-wasi` stubs
in step): `ensure-directories-exist` makes the migration directory and `delete-file`
removes a superseded migration file for real. The E2E leg still exercises the DB-side
workflow, which is the part all three share -- a file-writing leg is the
re-verification trigger, not a claim made here.


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
Every exercise sets `mito:*mito-migration-logger-stream*` to nil: `ensure-table-exists` /
`migrate-table` log each statement to `*standard-output*` with its wall-clock time (twice --
`with-sql-logging` and `execute-sql` each push a trace hook), which no expected output can
pin. The program needs more constant-pool entries than one class file indexes, so its JVM
output is `Probe.class` plus `Probe$Part1.class` (`.kb/jvm-method-size-limits.md`, the
split); the three JVM legs were red from 2026-08-28 until the split landed (2026-09-25).
Each JVM leg spends ~250 s compiling, nearly all of it in `expandTopLevelDefinitions`'s
runtime-subtypep ancestor table, not in codegen.
Docs: `doc/{en,ja}/guides/mito.md`, the mito row in `guides/asdf-systems.md`.
