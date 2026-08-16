# db

Talking to PostgreSQL from rontolisp using the real upstream libraries
(cl-postgres, the low-level driver, and postmodern on top of it) rather than a
rontolisp-specific binding.

| Program | Description | Upstream |
| --- | --- | --- |
| [`postgres-hello.lisp`](postgres-hello.lisp) | connect and run two select queries | <https://github.com/marijnh/Postmodern> |
| [`postgres-crud.lisp`](postgres-crud.lisp) | full CRUD cycle: prepared statements (`prepare-query` + `exec-prepared`), an alist row reader, all inside a rolled-back transaction so it is safe to re-run | <https://github.com/marijnh/Postmodern> |
| [`postmodern-crud.lisp`](postmodern-crud.lisp) | the same cycle one layer up: `with-connection`, statements written as S-SQL s-expressions, `with-transaction`, `defprepared`, and the result formats (`:alists`, `:single`, `:column`) | <https://github.com/marijnh/Postmodern> |
| [`postmodern-dao.lisp`](postmodern-dao.lisp) | the DAO layer on top: a `(:metaclass pomo:dao-class)` class as the table definition, `dao-table-definition`, `deftable`/`create-table`, and the CRUD cycle as `insert-dao` / `get-dao` / `update-dao` / `upsert-dao` / `select-dao` / `delete-dao` | <https://github.com/marijnh/Postmodern> |
| [`postgres-web.lisp`](postgres-web.lisp) | notes app: PostgreSQL storage + `rontolisp:http-handler` + cl-who for HTML | <https://github.com/edicl/cl-who> |
| [`bbs-api.lisp`](bbs-api.lisp) | bulletin-board REST API: JSON instead of HTML, routed with tiny-routes and served through Clack — paginated `GET`, a validated `POST`, a `DELETE` that tells 204 from 404, and one error document behind every failure | <https://github.com/jeko2000/tiny-routes> |
| [`database-url.lisp`](database-url.lisp) | not a program: the `DATABASE_URL` parser the four above share, `(load)`ed by each of them | — |

## Setup

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
```

No program here holds a connection string. Each calls
`(database-url-parts (uiop:getenv "DATABASE_URL"))`, where `database-url-parts`
comes from [`database-url.lisp`](database-url.lisp), pulled in with a top-level
`(load ...)` and so spliced in at compile time — the parser compiles natively on
every backend rather than needing `--dynamic`. It takes a URL and knows nothing
about the environment, so the same five values could come from a config file
instead.

```
postgresql://user:password@host:port/database
```

`postgres://` is an alias. The password, the port (5432) and a trailing
`?sslmode=…` (recognised and dropped — `use-ssl` is not wired up here) are all
optional. `%XX` escapes in the user and password are decoded, so a password
holding an `@` travels as `%40`; a `+` stays a literal plus, this being URI
userinfo rather than a query string. Anything else — an unset variable, a
missing host, a non-numeric port — is an error naming what is wrong, never a
silent default.

## Interpreter / JVM

```bash
rontolisp examples/db/postgres-hello.lisp
rontolisp examples/db/postgres-crud.lisp
rontolisp examples/db/postmodern-crud.lisp
rontolisp examples/db/postmodern-dao.lisp
rontolisp examples/db/postgres-web.lisp   # then open http://127.0.0.1:8080
rontolisp examples/db/bbs-api.lisp        # then curl http://127.0.0.1:8080/api/v1/comments

rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
```

`ql:quickload` pulls md5, split-sequence, ironclad, cl-base64, cl-ppcre, uax-15,
alexandria, (for postmodern) s-sql, uiop and bordeaux-threads, (for the web
app) cl-who and (for the REST API) clack, lack and tiny-routes, downloading them
into `~/.rontolisp/quicklisp` on first run.

## WASM

TCP requires `--component` (WASI 0.3 sockets); Preview 1 has no host socket API.
`--env DATABASE_URL` is what puts the variable inside the component: wasmtime
passes no environment through unless asked. Forget it and the program stops on
`no database URL given` — except that an uncaught error on WASM surfaces as a
bare `unreachable` trap with the message lost, so on that backend the missing
flag looks like a crash rather than a diagnostic.

```bash
rontolisp examples/db/postgres-hello.lisp -o postgres-hello.wasm --component --optimize
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y --env DATABASE_URL postgres-hello.wasm
```

`postgres-crud.lisp` and `postmodern-crud.lisp` build and run the same way.
`postgres-web.lisp` runs under `wasmtime serve` and needs one more flag
(`-S cli=y`); `--env DATABASE_URL` reaches a served handler exactly like it
reaches the others.

```bash
rontolisp examples/db/postgres-web.lisp -o app.wasm --component --optimize
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y --env DATABASE_URL app.wasm
```

`bbs-api.lisp` builds and serves with exactly the same two commands.

wasmCloud runs the same component under `wash dev` with
`wasm_proposals: [gc, exception-handling, component-model-async]`. There is
no `--env` flag there; `.wash/config.yaml` needs a top-level `workload:`
block instead (a sibling of `build:`/`dev:`, not nested under `dev:`):

```yaml
workload:
  environment:
    config:
      DATABASE_URL: postgresql://postgres@192.168.11.76:54329/postgres
```

`workload.environment.config` is inline values; `configFrom` / `secretFrom`
instead name entries of a top-level `configs:` / `secrets:` block, for values
that should not sit in the YAML in the clear. Either way this reaches
`uiop:getenv` unmodified — wash links the same `wasi:cli/environment@0.3.0`
interface `wasmtime serve --env` satisfies.

**Spin**
([canary build](https://github.com/spinframework/spin/releases/tag/canary),
4.1.0-pre0+) runs the same component with no flags at all and serves it on
`:3000`. Its sandbox is deny-by-default, so the manifest carries both the
environment and the database address:

```toml
spin_manifest_version = 2

[application]
name = "rontolisp-postgres-web"
version = "0.1.0"

[[trigger.http]]
route = "/..."
component = "notes"

[component.notes]
source = "app.wasm"
allowed_outbound_hosts = ["tcp://127.0.0.1:54329"]
environment = { DATABASE_URL = "postgresql://postgres@127.0.0.1:54329/postgres" }
```

```bash
rontolisp examples/db/postgres-web.lisp -o app.wasm --component --optimize
spin up
```

`allowed_outbound_hosts` takes `<scheme>://<host>:<port>`, and the driver's
plain TCP connect is checked under the **`tcp`** scheme. Omit the entry and
every request 500s with the destination named in the log; omit `environment` and
you get the same bare `unreachable` trap `--env`-less wasmtime gives. Unlike
wash, Spin does not virtualize the loopback.

**A served component imports the environment interface itself.** The WASI 0.3
service world carries no `wasi:cli/environment`, so the preview1 bridge answers
the `environ_*` calls with a zero-entry environment — reading `DATABASE_URL`
there is not the bridge's job. A program that calls `uiop:getenv` instead binds
`wasi:cli/environment@0.3.0` directly and the component declares that import
(`wasm-tools component wit app.wasm` lists it, next to `wasi:sockets`), which is
what `wasmtime serve --env DATABASE_URL` then satisfies. A program that never
reads the environment declares nothing extra, so it still runs on any host that
provides only the service world.

## The bulletin-board API

`bbs-api.lisp` serves three endpoints under `/api/v1`:

| Request | Answer |
| --- | --- |
| `POST /comments` with `{"author": ..., "content": ...}` | `201` + the stored comment (`id`, `author`, `content`, `createdAt`) and a `Location` header |
| `GET /comments?page=1&limit=20&sort=newest` | `200` + `data` (the page) and `pagination` (`totalComments`, `totalPages`, `currentPage`, `limit`, `hasNextPage`, `hasPrevPage`) |
| `DELETE /comments/{id}` | `204`, or `404` when no such comment |

```bash
curl -X POST -H 'content-type: application/json' \
     -d '{"author":"alice","content":"hello"}' http://127.0.0.1:8080/api/v1/comments
curl 'http://127.0.0.1:8080/api/v1/comments?page=1&limit=20&sort=oldest'
curl -i -X DELETE http://127.0.0.1:8080/api/v1/comments/1
```

Every failure is the same document — `timestamp`, `status`, `error`, `message`,
`path` — so a client has one shape to parse: `400` for a body that is not a JSON
object with a non-empty `author` and `content`, or a `page`/`limit`/`sort` that
is not one of the values the contract allows (`limit` tops out at 100); `404`
for a comment or a path that is not there; `405`, with an `Allow` header, for a
known path taken with the wrong method; and `500` for anything the handlers did
not foresee, including the database being down.

Three things are worth reading the source for:

- **The sort direction is spliced into the SQL, everything else is a
  parameter.** An `ORDER BY` direction cannot be a placeholder, so `page-comments`
  builds the statement text — which is safe only because the caller has already
  turned `sort` into one of exactly two literals. `author`, `content`, the id and
  the page window all travel as `$n` parameters.
- **The connection is opened by a POST-MATCH middleware.** `wrap-connection` runs
  only once a route has claimed the request by method *and* path, so a request on
  its way to a 404 never opens one. Piping it in the ordinary way would.
- **The response shapes are CLOS classes.** `json-stringify` serializes a
  standard-object slot by slot in definition order, so the class *is* the schema;
  the `|createdAt|` spelling is what keeps the reader from upcasing a camelCase
  key into `createdat`.

## Notes

- **postmodern loads in its MOP build.** The query, transaction and
  prepared-statement layers `postmodern-crud.lisp` shows all work, and so does
  the DAO layer `postmodern-dao.lisp` shows (`:metaclass dao-class`, `get-dao` /
  `select-dao` / `insert-dao` / `upsert-dao`), running on the definition-time
  metaobject subset: DAO classes are top-level `defclass` forms with literal
  options.
- **SCRAM-SHA-256** completes on all backends well within PostgreSQL's default
  60-second `authentication_timeout`. The interpreter runs its 4096-round PBKDF2
  on a native kernel, so the handshake costs milliseconds there; the compiled
  backends run ironclad's own code and finish in seconds. `trust`, `password`
  and `md5` auth are unaffected.
- **TLS is interpreter/JVM only.** A connection that negotiates SSL cannot work
  under WASM; use plain TCP (the default `:no`).
- **wasmCloud addressing.** wash routes loopback to a per-workload virtual
  network, so the host in `DATABASE_URL` must be a non-loopback address there.
  `wasmtime serve` and Spin both use the real loopback, so `127.0.0.1` works.
- **Instance lifetime.** The program's top level runs once per instance, and
  every host draws that line differently. `wasmtime serve` keeps one instance
  for the whole run; wasmCloud gives each request a fresh instance — this is
  why `postgres-web.lisp` uses `create table if not exists` instead of dropping
  and recreating the table. Spin sits between the two: it reuses an instance
  for 128 requests and then retires it (`--max-instance-reuse-count`, and
  `--max-instance-concurrent-reuse-count` — 16 by default — lets that many
  requests share one instance at a time). Write the top level so that all three
  are fine: idempotent, and with durable state in the database rather than in a
  global.
- **Non-ASCII.** cl-who escapes characters above ASCII as numeric character
  references by default; bind `cl-who:*escape-char-p*` to a predicate matching
  only `<>&'"` to emit raw UTF-8 instead.
