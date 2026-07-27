# db

Talking to PostgreSQL from rontolisp using the real upstream driver (cl-postgres,
Postmodern's low-level driver) rather than a rontolisp-specific binding.

| Program | Description | Upstream |
| --- | --- | --- |
| [`postgres-hello.lisp`](postgres-hello.lisp) | connect and run two select queries | <https://github.com/marijnh/Postmodern> |
| [`postgres-crud.lisp`](postgres-crud.lisp) | full CRUD cycle: prepared statements (`prepare-query` + `exec-prepared`), an alist row reader, all inside a rolled-back transaction so it is safe to re-run | <https://github.com/marijnh/Postmodern> |
| [`postgres-web.lisp`](postgres-web.lisp) | notes app: PostgreSQL storage + `rontolisp:http-handler` + cl-who for HTML | <https://github.com/edicl/cl-who> |

## Setup

```bash
docker run --rm -p 54329:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine
```

## Interpreter / JVM

```bash
rontolisp examples/db/postgres-hello.lisp
rontolisp examples/db/postgres-crud.lisp
rontolisp examples/db/postgres-web.lisp   # then open http://127.0.0.1:8080

rontolisp examples/db/postgres-hello.lisp -o Prog.class && java Prog
```

`ql:quickload` pulls md5, split-sequence, ironclad, cl-base64, cl-ppcre, uax-15,
alexandria and (for the web app) cl-who, downloading them into
`~/.rontolisp/quicklisp` on first run.

## WASM

TCP requires `--component` (WASI 0.3 sockets); Preview 1 has no host socket API.

```bash
rontolisp examples/db/postgres-hello.lisp -o postgres-hello.wasm --component --optimize
wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y postgres-hello.wasm
```

`postgres-crud.lisp` builds and runs the same way. `postgres-web.lisp` runs under
`wasmtime serve` and needs one more flag (`-S cli=y`):

```bash
rontolisp examples/db/postgres-web.lisp -o app.wasm --component --optimize
wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y app.wasm
```

wasmCloud runs the same component under `wash dev` with
`wasm_proposals: [gc, exception-handling, component-model-async]`.

## Notes

- **SCRAM-SHA-256** completes on all backends within PostgreSQL's default
  60-second `authentication_timeout`. The interpreter is the slow one: its
  4096-round PBKDF2 takes ~50 s, so on a slow machine give the server headroom
  with `-c authentication_timeout=600`. The compiled backends finish in seconds;
  `trust`, `password` and `md5` auth are unaffected.
- **TLS is interpreter/JVM only.** A connection that negotiates SSL cannot work
  under WASM; use plain TCP (the default `:no`).
- **wasmCloud addressing.** wash routes loopback to a per-workload virtual
  network, so the database must be reachable at a non-loopback address.
- **Instance lifetime.** The program's top level runs once per instance.
  `wasmtime serve` keeps one instance for the whole run, but wasmCloud gives
  each request a fresh instance — this is why `postgres-web.lisp` uses
  `create table if not exists` instead of dropping and recreating the table.
- **Non-ASCII.** cl-who escapes characters above ASCII as numeric character
  references by default; bind `cl-who:*escape-char-p*` to a predicate matching
  only `<>&'"` to emit raw UTF-8 instead.
