# O/R Mapping (mito, sxql)

[Mito](https://github.com/fukamachi/mito) — an O/R mapper for Common Lisp —
loads verbatim via `(ql:quickload "mito")`, and that pulls the whole system:
`mito-core` (the DAO layer), `mito-migration` (schema diffs and migration
files) and `lack-middleware-mito`. Its query clauses are written in
[SxQL](https://github.com/fukamachi/sxql), which also loads and works on its own.

**PostgreSQL is the only database.** `dbd-mysql` and `dbd-sqlite3` need FFI and
are absent; the PostgreSQL driver rides the
[cl-postgres stack](asdf-systems.md), so mito reaches every backend that can
open a TCP socket — the interpreter, a JVM class and a WASM component — and not
WASM Preview 1.

## Defining a table and connecting

`dbd-postgres` must be quickloaded **explicitly**, next to `mito`: `dbi:connect`
resolves its driver over the already-loaded systems, and a compiled program
cannot load a system at run time.

`deftable` is a `defclass` over mito's `dao-table-class` metaclass. The
metaclass protocol runs at DEFINITION time, so a `deftable` must be a top-level
form with literal options. `mito:table-definition` renders the DDL mito would
create — it asks the driver for its type, so it needs a live connection:

```console
$ cat blog.lisp
(ql:quickload '("mito" "dbd-postgres"))

(mito:deftable article ()
  ((title :col-type (:varchar 64))
   (body  :col-type (or :text :null))))

(mito:connect-toplevel :postgres :database-name "blog" :username "postgres"
                       :password "secret" :host "127.0.0.1" :port 5432)

(dolist (statement (mito:table-definition 'article))
  (format t "~a~%" (sxql:yield statement)))
$ rontolisp blog.lisp
CREATE TABLE article (
    id BIGSERIAL NOT NULL PRIMARY KEY,
    title VARCHAR(64) NOT NULL,
    body TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
)
```

The `id` primary key and the `created_at` / `updated_at` pair are mito's
defaults (`:auto-pk`, `record-timestamps-mixin`); `(:auto-pk :uuid)` switches
the key to a generated v4 UUID stored as `VARCHAR(36)`, and
`(:table-name "...")` overrides the derived table name.

`mito:disconnect-toplevel` closes the connection. Call it: the
`trivial-garbage` dependency resolves to a no-op finalizer shim, so nothing
reclaims a connection for you.

## CRUD

```console
$ cat crud.lisp
(ql:quickload '("mito" "dbd-postgres"))
;; ... the deftable and connect-toplevel from above ...

(mito:ensure-table-exists 'article)

(let ((a (mito:create-dao 'article :title "Hello" :body "First post")))
  (format t "~a ~a~%" (mito:object-id a) (slot-value a 'title)))
(mito:insert-dao (make-instance 'article :title "Hello again" :body nil))

(let ((found (mito:find-dao 'article :title "Hello")))
  (setf (slot-value found 'body) "Edited")
  (mito:save-dao found))

(format t "~a~%"
        (mapcar (lambda (a) (slot-value a 'title))
                (mito:select-dao 'article
                                 (sxql:where (:like :title "Hello%"))
                                 (sxql:order-by :id))))

(mito:delete-dao (mito:find-dao 'article :title "Hello again"))
(format t "~a~%" (length (mito:select-dao 'article)))
$ rontolisp crud.lisp
1 Hello
(Hello Hello again)
1
```

Slots are read with `slot-value`: mito injects its `:conc-name` readers from a
metaclass hook that rontolisp records as metaobject data only, so the
`article-title`-style accessors are **not** defined (see "Current limits").
`mito:retrieve-by-sql` and `mito:execute-sql` are there for the raw-SQL cases.

## Schema migration

There are two ways in, and they differ in which backends can run them.

**Diff against the live schema** — all three backends.
`mito.migration:migration-expressions` compares the current class definition
with the table as it exists and answers the statements that would close the gap;
`migrate-table` runs them in a transaction:

```console
$ cat migrate-table.lisp
(ql:quickload '("mito" "dbd-postgres"))
;; ... the same connect-toplevel, and `article` redefined one column wider ...
(mito:deftable article ()
  ((title :col-type (:varchar 64))
   (body  :col-type (or :text :null))
   (tag   :col-type (or (:varchar 16) :null))))

(dolist (statement (mito.migration:migration-expressions 'article))
  (format t "~a~%" (sxql:yield statement)))
(mito.migration:migrate-table 'article)
(format t "~a~%" (mito.migration:migration-expressions 'article))
$ rontolisp migrate-table.lisp
ALTER TABLE article ADD COLUMN tag character varying(16)
NIL
```

**Migration files** — interpreter and JVM only (see "Current limits").
`generate-migrations` writes `db/schema.sql` plus a timestamped
`.up.sql` / `.down.sql` pair, `migration-status` reports what is applied, and
`migrate` applies the pending files (parsing them with esrap):

```console
$ cat db.lisp
(ql:quickload '("mito" "dbd-postgres"))
;; ... the deftable and connect-toplevel from above ...
(mito:generate-migrations #P"db/")
(mito:migration-status #P"db/")
(mito:migrate #P"db/")
$ rontolisp db.lisp
CREATE TABLE "article" (
    "id" BIGSERIAL NOT NULL PRIMARY KEY,
    "title" VARCHAR(64) NOT NULL,
    "body" TEXT,
    "created_at" TIMESTAMPTZ,
    "updated_at" TIMESTAMPTZ
);
Successfully generated: db/migrations/20260804003900.up.sql
 Status   Migration ID
--------------------------
  down    20260804003900
Applying 'db/schema.sql'...
-> CREATE TABLE "article" (
    "id" BIGSERIAL NOT NULL PRIMARY KEY,
    "title" VARCHAR(64) NOT NULL,
    "body" TEXT,
    "created_at" TIMESTAMPTZ,
    "updated_at" TIMESTAMPTZ
);
-> CREATE TABLE IF NOT EXISTS "schema_migrations" (
    "version" BIGINT PRIMARY KEY,
    "applied_at" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "dirty" BOOLEAN NOT NULL DEFAULT false
);
Successfully updated to the version 20260804003900.
```

## SQL generation with SxQL

SxQL is what `select-dao`'s clauses are, and it stands on its own —
`(ql:quickload "sxql")` opens no sockets and renders identical SQL on **all
four** backends. `sxql:yield` answers the SQL string and the bind-value list as
two values:

```console
$ cat query.lisp
(ql:quickload "sxql")
(multiple-value-bind (sql binds)
    (sxql:yield (sxql:select :*
                  (sxql:from :article)
                  (sxql:where (:and (:like :title "%lisp%")
                                    (:in :status '("published" "draft"))))
                  (sxql:order-by (:desc :id))
                  (sxql:limit 10)))
  (format t "~a~%~a~%" sql binds))
(format t "~a~%" (sxql:yield (sxql:insert-into :article
                               (sxql:set= :title "Hello" :body "First post"))))
(format t "~a~%" (sxql:yield (sxql:update :article
                               (sxql:set= :title "Hi")
                               (sxql:where (:= :id 1)))))
(format t "~a~%" (sxql:yield (sxql:delete-from :article (sxql:where (:= :id 1)))))
$ rontolisp query.lisp
SELECT * FROM article WHERE ((title LIKE ?) AND (status IN (?, ?))) ORDER BY id DESC LIMIT 10
(%lisp% published draft)
INSERT INTO article (title, body) VALUES (?, ?)
UPDATE article SET title = ? WHERE (id = ?)
DELETE FROM article WHERE (id = ?)
```

`create-table`, `drop-table`, `alter-table` with `add-column`,
`left-join ... :on`, `limit` / `offset` and `order-by` with `:desc` / `nulls`
are all there. Binding `sxql:*use-placeholder*` to `nil` renders the values
inline instead of as `?` placeholders.

Like every macro-heavy library, hot query construction belongs on a compiled
backend — the interpreter re-expands macros on every evaluation.

## Backends

- **Interpreter** — everything above.
- **JVM class** — `rontolisp blog.lisp -o Blog.class && java Blog`. The
  compiled class is self-contained.
- **WASM component** (`--component`) — needs both socket permissions:

  ```bash
  rontolisp blog.lisp -o blog.wasm --component
  wasmtime run -S tcp=y -S inherit-network=y blog.wasm
  ```

  The `:host` must be an **IPv4 literal** — hostname lookup is unwired on WASM.
- **WASM Preview 1** has no host socket API by design. A mito program compiles
  there (socket call sites become **call-time errors**, so spliced dead code
  builds), and the first socket call fails loudly at run time with a message
  naming the backends that work.

## Current limits

- **PostgreSQL only.** `dbd-mysql` / `dbd-sqlite3` need FFI. mito's own
  `mysql` / `sqlite3` source files still load; they are simply never selected.
- **`:conc-name` accessors are not generated.** mito adds its readers and
  writers from a metaclass hook, and accessor methods here come from the
  original `defclass` form, which carried none. Use `slot-value`.
- **SQL function operators are interpreter-only**: `(:count ...)`, `(:sum ...)`,
  `(:max ...)` and anything else SxQL resolves as a function call — and
  therefore `mito:count-dao` — signal on the JVM and WASM backends. Count with
  `(length (mito:select-dao ...))` there.
- **Writing migration files is interpreter + JVM.** `generate-migrations` and
  the file-deleting branch of `migrate` need directory creation and file
  removal, which the WASM backends do not import; they signal at the call. The
  diff-and-apply path (`migration-expressions`, `migrate-table`,
  `migration-status`, and `migrate` over files that already exist) runs
  everywhere.
- **A bare `:references` needs a `:col-type`.**
  `(other-id :references (other id))` alone signals — an upstream defect,
  reproduced identically by SBCL on the same sources. Write
  `(other-id :col-type :bigint :references (other id))`.
- **Adding a NOT NULL column that has an `:initform`** makes `migrate-table`
  emit `DEFAULT ?` with an empty bind list, and PostgreSQL answers `there is no
  parameter $1` — again upstream, again identical on SBCL. Wrap the call:
  `(let ((sxql:*use-placeholder* nil)) (mito.migration:migrate-table 'article))`.

See also: [Systems (asdf)](asdf-systems.md) for the whole library catalogue and
the cl-dbi / cl-postgres layers underneath, [TCP Sockets](tcp-sockets.md) for
the per-backend socket rules, and [Clack Web Applications](clack.md) for the web
side `lack-middleware-mito` plugs into.
