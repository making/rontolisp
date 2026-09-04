# Mito (DAO + schema migration) on rontolisp

**Invariant**: `(ql:quickload "mito")` loads the FULL system — `mito-core` +
`mito-migration` + `lack-middleware-mito` — from unpatched Quicklisp dist sources, and the
PostgreSQL DAO + migration workflow behaves identically on the interpreter, the JVM and the
WASM `--component` backend. WASI Preview 1 is out by design (no TCP,
`.kb/tcp-sockets.md`). PostgreSQL is the only driver (dbd-mysql / dbd-sqlite3 need FFI;
mito's `src/core/db/{mysql,sqlite3}.lisp` still LOAD — they only import `dbi` — they are
just never selected).

Substrate units: `.kb/asdf.md` (trivia, sxql, cl-dbi/dbd-postgres), `.kb/clos.md` (MOP
widening for mito's metaclasses), `.kb/packages.md` (`uiop:define-package` +
`:use-reexport`, which makes the `mito` umbrella package work).

chipz loads verbatim (no CRC32 slice override any more) on all four backends
(`ChipzE2eTest`). chipz's crc32 of `"mydb"` is `285543882`, matching SBCL 2.2.9.

## Core-language pieces the migration workflow needed (all closed)

- Run-time `make-pathname`, `pathname-name`, `pathname-type` — `.kb/directory-listing.md`.
- `pathname` is a DISTINCT non-empty type, so `(check-type directory pathname)` in
  `migrate` and `(etypecase file (null ...) (pathname ...))` in `migration-status` work.
  `.kb/pathnames.md`, `.kb/declarations-type-checks.md`.
- `uiop:directory-files` with UIOP's optional wildcard (`"*.up.sql"`).
- `uiop:read-file-string`, `delete-file` (+ `%delete-file` primitive), `y-or-n-p` —
  `.kb/read-load-streams.md`.
- `make-broadcast-stream` with COMPONENTS (`generate-migrations` echoes DDL to
  `*standard-output*` and the migration file at once) — `.kb/gray-streams.md`.
- **Two CLOS dispatch defects** (`.kb/clos.md`): a bare `call-next-method` dropped the
  `&optional` section, and the dispatcher had no branch for the MEET of two incomparable
  specializer vectors. cl-dbi's `(do-sql conn sql &optional params)` needs both; every mito
  advisory lock (`pg_advisory_lock(?)`) goes through that path.

## Scope: `generate-migrations` is interpreter + JVM only

The DB-side workflow (`migration-status`, `migration-expressions`, `migrate-table`,
`migrate` over existing files) runs on all three in-scope backends. WRITING migration files
does not: `generate-migrations` calls `ensure-directories-exist` and `delete-file`, both
call-time errors on WASM (no WASI directory-creation/unlink import,
`.kb/read-load-streams.md`). Closing it means a tenth preview1 import
(`path_create_directory` / `path_unlink_file`), which shifts every emitted WASM function
index and needs `adapter.wat` + `adapter-http-server-p1.wat` + the `--no-wasi` trap stubs in
step, as `fd_readdir` did. **Re-evaluation trigger**: if anything else needs directory
creation or file removal on WASM, do that import batch and this scoping disappears.

## Upstream defects reproduced faithfully (do NOT "fix" here)

Each checked against SBCL 2.2.9 on the SAME sources; rontolisp fails identically.

- **Bare relational `:references` is broken upstream.**
  `(mito:deftable u () ((other :references other)))` — and the README's
  `(user-id :references (user id))` — die with an unbound `col-type` slot.
  `mito/src/core/class/table.lisp:52` only rewrites a column when its `:col-type` is a
  non-nil non-keyword symbol; `column.lisp:151-153`'s `table-column-not-null-p` then reads
  `%table-column-type` unguarded where `table-column-type` guards with `slot-boundp`. Use
  `(other-id :col-type :bigint :references (other id))`.
- **`ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT <value>` loses its bind.** Emits
  `DEFAULT ?` with an EMPTY bind list; PostgreSQL answers `there is no parameter $1`.
  `sxql/src/statement.lisp:411-416`: `yield ((statement alter-table-statement))` neither
  wraps itself in `with-yield-binds` nor propagates its child's second value, and hard-codes
  its binds to `nil` (`create-table-statement` above it is correct). Workaround on both
  engines: `(let ((sxql:*use-placeholder* nil)) (mito.migration:migrate-table ...))`.
- **`mito.db:column-definitions` mis-reports a column's `:default`.**
  `mito/src/core/db/postgres.lisp:60` joins `pg_attrdef` on `adrelid` only, without
  `d.adnum = f.attnum`. mito neutralises it with `omit-default`
  (`migration/table.lisp:344`).

## Known rontolisp gaps in mito's surface

- Relational `:col-type <class-name>` (the syntax that triggers `add-referencing-slots`)
  needs runtime method construction (`ensure-generic-function` / `add-method` /
  `standard-method`), out of scope per `.kb/clos.md`.
- `deftable`'s conc-name accessors (`user-name`) are never defined: mito injects
  `:readers`/`:writers` into the canonicalized slot spec from a metaclass `:around`, and
  rontolisp records those as metaobject DATA only — `expandDefclass` generates accessors
  from the ORIGINAL defclass form, which carried none. `slot-value` works.
- A slot definition built by the metaclass driver carries the PACKAGE-STRIPPED slot name,
  so mito's `find-slot-by-name` (`eq` on `c2mop:slot-definition-name`) misses a
  caller-package symbol and `:references` silently skips inheriting the referenced column's
  type — wrong DDL, no error.
- **`count-dao` — and every sxql SQL FUNCTION operator — is interpreter-only.** `count-dao`
  builds `(:count :*)`; sxql resolves an unknown operator through `find-make-op` ->
  `(find-symbol "MAKE-COUNT-OP" pkg)` with `:errorp nil`, expecting `nil` and falling back
  to `make-function-op`. The compiled backends answer a SYMBOL for an unknown name
  (`.kb/symbol-runtime-api.md` — they BUILD the canonical spelling instead of asking what
  the package owns), so the fallback never runs and `symbol-function` signals. Reaches
  `:count` / `:sum` / `:max` / any `(:some-function ...)`; comparison and logic operators
  are unaffected (they have op-structs). Tripwire
  `MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends` ASSERTS the failure — closing the
  gap turns it red.

## Verified

- `(:auto-pk :uuid)` end to end: DDL is `id VARCHAR(36) NOT NULL PRIMARY KEY`, `create-dao`
  generates a real v4 UUID, `find-dao` round-trips it.
- The DB-side workflow is byte-identical on interpreter, JVM and WASM component, advisory
  lock included.
- `lack-middleware-mito` loads verbatim; its three branches (cached / `:no-cache t` / nil
  config) run on all three in-scope backends. `.kb/clack.md` owns the lack/clack machinery.
- SCRAM is no longer a cost concern: the interpreter's PBKDF2 is native
  (`.kb/asdf.md`), so a SCRAM connect is ~0.1 s. `MitoE2eTest`'s
  `POSTGRES_HOST_AUTH_METHOD=trust` stays only because it is the cheapest test auth.

## Coverage

- `MitoE2eTest` — opt-in via `RONTOLISP_POSTGRES_E2E=1`, Testcontainers
  `postgres:17-alpine`, the `PostmodernE2eTest` shape: DAO round trip and DB-side migration
  diff cycle, each asserted BYTE-IDENTICAL on interpreter / JVM / WASM component, plus the
  Preview 1 compile-error pin and the `count-dao` tripwire.
- Docs: `doc/{en,ja}/guides/mito.md` (mito AND sxql on one page), the mito row in
  `guides/asdf-systems.md`, nav entry in both languages.
