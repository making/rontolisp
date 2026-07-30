# db

Talking to PostgreSQL from rontolisp using the real upstream libraries
(cl-postgres, the low-level driver, and postmodern on top of it) rather than a
rontolisp-specific binding.

| Program | Description | Upstream |
| --- | --- | --- |
| [`postgres-hello.lisp`](postgres-hello.lisp) | connect and run two select queries | <https://github.com/marijnh/Postmodern> |
| [`postgres-crud.lisp`](postgres-crud.lisp) | full CRUD cycle: prepared statements (`prepare-query` + `exec-prepared`), an alist row reader, all inside a rolled-back transaction so it is safe to re-run | <https://github.com/marijnh/Postmodern> |
| [`postmodern-crud.lisp`](postmodern-crud.lisp) | the same cycle one layer up: `with-connection`, statements written as S-SQL s-expressions, `with-transaction`, `defprepared`, and the result formats (`:alists`, `:single`, `:column`) | <https://github.com/marijnh/Postmodern> |
| [`postgres-web.lisp`](postgres-web.lisp) | notes app: PostgreSQL storage + `rontolisp:http-handler` + cl-who for HTML | <https://github.com/edicl/cl-who> |
| [`database-url.lisp`](database-url.lisp) | not a program: the `DATABASE_URL` parser the four above share, `(load)`ed by each of them | — |

## Setup

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
export DATABASE_URL=postgresql://postgres@127.0.0.1:54329/postgres
```

No program here holds a connection string. Each calls
`(database-url-parts (uiop:getenv "DATABASE_URL"))` — `uiop:getenv` being
rontolisp's one spelling of "read an environment variable", ANSI CL having none
— where `database-url-parts` comes from [`database-url.lisp`](database-url.lisp),
pulled in with a top-level `(load "database-url.lisp")` and so spliced in at
compile time: the parser compiles natively on every backend rather than needing
`--dynamic`. It takes a URL and knows nothing about the environment, so the same
five values can come from a config file or a command-line argument instead.

```
postgresql://user:password@host:port/database
```

`postgres://` works as an alias for `postgresql://`. The password is optional
(what the trust-auth server above wants), so is the port (5432), and so is a
trailing query string — an `?sslmode=…`, which these examples recognise and
drop, `use-ssl` not being wired up here. `%XX` escapes in the user and the
password are decoded, so a password holding an `@` travels as `%40`; a `+` stays
a literal plus, this being URI userinfo rather than a query string. Anything
else — an unset variable, a missing host, a port that is not a number — is an
error naming what is wrong, never a silent default: a fallback address would be
the hardcoded connection this file exists to remove.

## Interpreter / JVM

```bash
rontolisp examples/db/postgres-hello.lisp
rontolisp examples/db/postgres-crud.lisp
rontolisp examples/db/postmodern-crud.lisp
rontolisp examples/db/postgres-web.lisp   # then open http://127.0.0.1:8080

rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
```

`ql:quickload` pulls md5, split-sequence, ironclad, cl-base64, cl-ppcre, uax-15,
alexandria, (for postmodern) s-sql, uiop and bordeaux-threads, and (for the web
app) cl-who, downloading them into `~/.rontolisp/quicklisp` on first run.

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

`workload.environment.config` is inline values; `workload.environment.configFrom`
/ `secretFrom` instead name entries of a top-level `configs:` / `secrets:`
block (a `file:` path or `fromEnv:` list of names to read from the
developer's own shell) for values that should not sit in the YAML in the
clear. Either way this reaches `uiop:getenv` unmodified — wash links the
same `wasi:cli/environment@0.3.0` interface `wasmtime serve --env` satisfies,
just supplied from this block rather than a CLI flag.

**A served component imports the environment interface itself.** The WASI 0.3
service world carries no `wasi:cli/environment`, so the preview1 bridge answers
the `environ_*` calls with a zero-entry environment — reading `DATABASE_URL`
there is not the bridge's job. A program that calls `uiop:getenv` instead binds
`wasi:cli/environment@0.3.0` directly and the component declares that import
(`wasm-tools component wit app.wasm` lists it, next to `wasi:sockets`), which is
what `wasmtime serve --env DATABASE_URL` then satisfies. A program that never
reads the environment declares nothing extra, so it still runs on any host that
provides only the service world.

## Notes

- **postmodern loads in its non-MOP build.** The query, transaction and
  prepared-statement layers `postmodern-crud.lisp` shows all work; the DAO layer
  (`:metaclass dao-class`, `get-dao` / `select-dao` / `insert-dao`) needs the
  metaobject protocol and is not available.
- **SCRAM-SHA-256** completes on all backends within PostgreSQL's default
  60-second `authentication_timeout`. The interpreter is the slow one: its
  4096-round PBKDF2 takes ~50 s, so on a slow machine give the server headroom
  with `-c authentication_timeout=600`. The compiled backends finish in seconds;
  `trust`, `password` and `md5` auth are unaffected.
- **TLS is interpreter/JVM only.** A connection that negotiates SSL cannot work
  under WASM; use plain TCP (the default `:no`).
- **wasmCloud addressing.** wash routes loopback to a per-workload virtual
  network, so the host in `DATABASE_URL` must be a non-loopback address there.
- **Instance lifetime.** The program's top level runs once per instance.
  `wasmtime serve` keeps one instance for the whole run, but wasmCloud gives
  each request a fresh instance — this is why `postgres-web.lisp` uses
  `create table if not exists` instead of dropping and recreating the table.
- **Non-ASCII.** cl-who escapes characters above ASCII as numeric character
  references by default; bind `cl-who:*escape-char-p*` to a predicate matching
  only `<>&'"` to emit raw UTF-8 instead.
