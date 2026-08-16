# Mito (DAO + schema migration) on rontolisp

**The invariant**: `(ql:quickload "mito")` loads the FULL system --
`mito-core` + `mito-migration` + `lack-middleware-mito` -- from the unpatched
Quicklisp dist sources, and the PostgreSQL DAO + migration workflow behaves
identically on the interpreter, the JVM and the WASM `--component` backend.
WASI Preview 1 is out by design (no TCP, `.kb/tcp-sockets.md`), and PostgreSQL
is the only driver (dbd-mysql / dbd-sqlite3 need FFI; mito's own
`src/core/db/{mysql,sqlite3}.lisp` still LOAD -- they only import `dbi` -- they
are just never selected).

Milestone `.todo/238`; the substrate units are closed and recorded in
`.kb/asdf.md` (trivia, sxql, cl-dbi/dbd-postgres), `.kb/clos.md` (the MOP
widening for mito's metaclasses) and `.kb/packages.md`
(`uiop:define-package` + `:use-reexport`, which is what makes the `mito`
umbrella package work).

## chipz: the CRC32 slice is gone, the real library loads

`AsdOverrides` used to map `chipz.asd` to a hand-authored `chipz-crc32-slice.asd`
declaring `package.lisp` + `crc32.lisp` and nothing else, because
`mito-migration/src/migration/util.lisp` -- which imports exactly `make-crc32` /
`update-crc32` / `produce-crc32` to derive a PostgreSQL advisory-lock id from the
database name -- was the whole closure's only consumer and nothing called
`chipz:decompress`.

That slice wrote its own re-evaluation trigger ("the moment any supported system
calls `chipz:decompress`..."), and `size-report/programs/zlib` pulled it: the
override and the replacement file were deleted on 2026-08-10 and the REAL
`chipz.asd` now loads verbatim -- inflate, bzip2, gzip and zlib containers
included -- on all four backends (`ChipzE2eTest`, `.kb/asdf.md`). The single gate
the slice predicted was real and is closed: `types-and-tables.lisp:107` needed
`fill`, which rontolisp did not have and now does.

Nothing changed for mito: chipz's crc32 of `"mydb"` is `285543882` through the
full system, the same value the slice and SBCL 2.2.9 answer.

## What the migration workflow needed from the core language

Every one of these is owned by its own `.kb` file now; listed here because they
are what stood between "mito-core loads" and "the migration workflow runs":

- `make-pathname` at RUN time, plus `pathname-name` / `pathname-type` --
  `.todo/222`, closed. `.kb/directory-listing.md`.
- `pathname` stopped being an EMPTY type, and is a DISTINCT value since
  todo-304: `(check-type directory pathname)` in `migrate` passes because the
  caller hands `#P"db/"` (the guides' spelling) and the producers mito reads
  from (`uiop:directory-files`, `merge-pathnames`, `make-pathname :defaults`)
  answer pathname values; `(etypecase file (null ...) (pathname ...))` in
  `migration-status` takes the pathname branch by the type itself.
  `.kb/pathnames.md`, `.kb/declarations-type-checks.md`.
- `uiop:directory-files` gained UIOP's optional wildcard argument
  (`"*.up.sql"`). `.kb/directory-listing.md`.
- `uiop:read-file-string`, `delete-file` (+ the `%delete-file` primitive) and
  `y-or-n-p`. `.kb/read-load-streams.md`.
- `make-broadcast-stream` with COMPONENTS -- `generate-migrations` echoes its
  DDL to `*standard-output*` and the migration file at once.
  `.kb/gray-streams.md`.
- **Two CLOS dispatch defects**, both `.kb/clos.md`, both SBCL-checked, and
  together the whole reason `mito.migration:migrate` did not run: a bare
  `call-next-method` dropped the `&optional` section, and the dispatcher had no
  branch for the MEET of two incomparable specializer vectors. cl-dbi's
  `(do-sql conn sql &optional params)` needs both -- its `:around` forwards
  `params` by chaining, and its postgres primary chains again to the
  string-specialized default. With `params` lost the driver took its
  no-parameters branch and sent PostgreSQL a raw `?`; with the branch missing,
  fixing that alone turned the failure into `no next method`. Every mito
  advisory lock (`pg_advisory_lock(?)`) goes through that path.

## Scope decision: `generate-migrations` is interpreter + JVM

The DB-side workflow -- `migration-status`, `migration-expressions`,
`migrate-table`, and `migrate` over migration files that already exist -- runs
on all three in-scope backends. **Writing** migration files does not:
`generate-migrations` calls `ensure-directories-exist`, which is a call-time
error on both WASM backends because no WASI directory-creation call is imported
(`.kb/read-load-streams.md`), and its delete-superseded-files branch calls
`delete-file`, which is a call-time error there for the same reason.

**Reason for the divergence**: closing it means a tenth preview1 import (and a
`path_create_directory` / `path_unlink_file` pair), which shifts every emitted
WASM function index and needs `adapter.wat` + `adapter-http-server-p1.wat` +
the `--no-wasi` trap stubs in step, exactly as `fd_readdir` did
(`.kb/directory-listing.md`). **Re-evaluation trigger**: if anything else needs
directory creation or file removal on WASM, do that import batch and this
scoping disappears with it -- nothing about mito is the obstacle.

## Upstream defects reproduced faithfully (do NOT "fix" them here)

Each was checked against SBCL 2.2.9 loading the SAME cached sources; rontolisp
fails identically, which is a MOP/semantics fidelity signal rather than a gap.

**Bare relational `:references` is broken upstream.**
`(mito:deftable u () ((other :references other)))` -- and the README's own
`(user-id :references (user id))` example -- die with an unbound `col-type`
slot. `mito/src/core/class/table.lisp:52` only rewrites a column when its
`:col-type` is a non-nil non-keyword symbol, so a `:references`-only spec is
never rewritten, and `column.lisp:151-153`'s `table-column-not-null-p` then
reads `%table-column-type` unguarded where its sibling `table-column-type`
guards with `slot-boundp`. `.todo/247` handed this over as a rontolisp bug; it
is not. The form that works is the col-type-carrying one,
`(other-id :col-type :bigint :references (other id))`.

**`ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT <value>` loses its bind.**
Adding a not-null column that has an `:initform` makes `migrate-table` emit
`... DEFAULT ?` with an EMPTY bind list, and PostgreSQL answers
`there is no parameter $1`. The loss is in
`sxql/src/statement.lisp:411-416`: `yield ((statement alter-table-statement))`
neither wraps itself in `with-yield-binds` nor propagates its child's second
value -- it takes the child's yield in a `format ~A` argument position (primary
value only) and hard-codes its own binds to `nil`. `create-table-statement`
three definitions above it does it correctly, so the fix upstream is one line.
Both engines behave identically, and the workaround is the same on both:
`(let ((sxql:*use-placeholder* nil)) (mito.migration:migrate-table ...))`
renders the default inline and succeeds. mito's own file-writing path already
binds that variable; its DB path does not.

**`mito.db:column-definitions` mis-reports a column's `:default`.**
`mito/src/core/db/postgres.lisp:60` joins `pg_attrdef` on `adrelid` only,
without `d.adnum = f.attnum`, so defaults are a cartesian product that
`delete-duplicates :from-end t` resolves arbitrarily. mito neutralises it
itself with `omit-default` (`migration/table.lisp:344`).

## Known rontolisp gaps in mito's surface (each has a todo)

- The relational `:col-type <class-name>` syntax -- the one that actually
  triggers `add-referencing-slots` -- needs runtime method construction
  (`ensure-generic-function` / `add-method` / `standard-method`), which
  `.kb/clos.md` lists as out of scope.
- `deftable`'s conc-name accessors (`user-name`) are never defined: mito injects
  `:readers`/`:writers` into the canonicalized slot spec from a metaclass
  `:around`, and rontolisp records those as metaobject DATA only --
  `expandDefclass` generates accessor methods from the ORIGINAL defclass form,
  which carried none. `slot-value` works.
- A slot definition built by the metaclass driver carries the PACKAGE-STRIPPED
  slot name, so mito's `find-slot-by-name` (an `eq` test on
  `c2mop:slot-definition-name`) misses a caller-package symbol and
  `:references` silently skips inheriting the referenced column's type -- wrong
  DDL, no error.

All three are `.todo/251`; `.todo/253` carries the SCRAM cost and the
cached-connection lifetime. The Gray output-protocol widening a broadcast stream
inherits has landed -- the whole output protocol dispatches now,
`.kb/gray-streams.md`.

**`count-dao` -- and every sxql SQL FUNCTION operator -- is interpreter-only**
(found while writing `MitoE2eTest`, `.todo/254`). `count-dao` builds
`(:count :*)`, and sxql resolves an operator it has no op-struct for through
`find-make-op` -> `(find-symbol "MAKE-COUNT-OP" pkg)` with `:errorp nil`,
expecting `nil` and falling back to a generic `make-function-op`. The compiled
backends answer a SYMBOL for an unknown name
(`.kb/symbol-runtime-api.md` -- they BUILD the canonical spelling instead of
asking what the package owns), so the fallback never runs and `symbol-function`
signals. It reaches `:count` / `:sum` / `:max` / any `(:some-function ...)`, not
just `count-dao`; the comparison and logic operators are unaffected because they
DO have op-structs. **Re-evaluation trigger**: `.todo/254` is the item, and
`MitoE2eTest#countDaoIsUndefinedOnTheCompiledBackends` is the tripwire -- it
asserts the failure, so closing the gap turns it red.

## Verified capabilities worth knowing

- `(:auto-pk :uuid)` works end to end (the second item `.todo/247` handed over
  untested): the DDL is `id VARCHAR(36) NOT NULL PRIMARY KEY`, `create-dao`
  generates a real v4 UUID and `find-dao` round-trips it. It rides the uuid
  library plus the 2-argument `random` / `make-random-state` that landed with
  todo-247.
- The DB-side workflow is byte-identical on the interpreter, the JVM and the
  WASM component, advisory lock included; `generate-migrations` +
  `migrate` (esrap re-reading the generated `.sql`) is interpreter + JVM per the
  scope decision above.

## Coverage

- **`MitoE2eTest`** (`.todo/250`, opt-in via `RONTOLISP_POSTGRES_E2E=1`,
  Testcontainers `postgres:17-alpine`, the `PostmodernE2eTest` shape): the DAO
  round trip and the DB-side migration diff cycle, each asserted BYTE-IDENTICAL
  on the interpreter, the JVM and the WASM component, plus the Preview 1
  compile-error pin and the `count-dao` tripwire above. Its container takes
  `POSTGRES_HOST_AUTH_METHOD=trust` (see the retired SCRAM caveat below).
- The user-facing docs are `doc/{en,ja}/guides/mito.md` (mito AND sxql on one
  page -- mito's query clauses ARE sxql, so a second page would duplicate), the
  rewritten mito row in `guides/asdf-systems.md`, and the nav entry in both
  languages.
- The lack middleware (`lack-middleware-mito`) loads verbatim and its three
  branches (cached / `:no-cache t` / nil config) run on all three in-scope
  backends; `.kb/clack.md` owns the lack/clack machinery it rides.
- The SCRAM caveat for any E2E is RETIRED (2026-08-16): `dbi:connect` against a
  `password_encryption = scram-sha-256` server used to cost ~60 s here (PBKDF2
  x4096 in interpreted ironclad) and race PostgreSQL's 60 s
  `authentication_timeout`, surfacing as `Database error: end of file`. The
  interpreter's PBKDF2 is native now (`.kb/asdf.md`, "Native PBKDF2 on the
  INTERPRETER"), so a SCRAM connect is ~0.1 s and no container setting is
  load-bearing. `MitoE2eTest`'s `POSTGRES_HOST_AUTH_METHOD=trust` stays because
  it is the cheapest auth for a test, not because SCRAM is unaffordable.
