# WASM component: a SCRAM login in a wasi:http handler still costs 0.7s

Difficulty: High

j30 (c6572de15) brought cl-postgres's SCRAM-SHA-256 login on a CLI component from 335ms to 97ms, but
not in a wasi:http handler component: Shiori and `examples/db/postgres-web.lisp` still spend about
690ms authenticating, the same as before the fix. Both open a connection per request, so every page
pays it; on sluice's wasi wicket a Shiori page is 0.67s, nearly all of it the login.

## Measured (2026-11-09)

develop `e337acadf`, built from source (`./mvnw -DskipTests package`, the exec jar), each compiled
with `--component --optimize`; `postgres:18-alpine` (default `scram-sha-256`, `log_connections=all`),
the server's `authentication=` time; an Apple silicon Mac.

| component | host | authentication |
|---|---|---|
| `postgres-hello.lisp`, before (`6686836`) | `wasmtime run` 49 | 335ms |
| `postgres-hello.lisp` | `wasmtime run` 49 (also with `-O pooling-allocator=y`, `-W timeout=100s`) | 95-105ms |
| `postgres-hello.lisp` | `wasmtime run` 49 / 50, Linux arm64 container | 101-125ms |
| `postgres-web.lisp` | `wasmtime serve -S cli=y -S tcp=y -S inherit-network=y -S p3=y -W component-model-async=y` 49 | 676-687ms (first request 2.4s) |
| Shiori (`examples/db/shiori`) | the same `wasmtime serve` | 692-720ms |
| Shiori, before (`04cea9c` build) | the same `wasmtime serve` | 684-692ms |
| Shiori | sluice's wasi wicket (wasmtime 50, Linux arm64 container) | 630-700ms |

The host is ruled out: the CLI component is fast on wasmtime 49 and 50, macOS and Linux, pooled or
not, with epoch interruption or not; the HTTP components are slow on plain `wasmtime serve`. What
differs is the program's shape: a wasi:http handler (`rontolisp:http-handler`, its top level run in
the first request) against a command.

## What to do

Find why the j30 fusion (or another hot path of the login) does not apply when the program is a
wasi:http handler component: compare the generated code of ironclad's SHA-256 / HMAC / PBKDF2 in
`postgres-hello.wasm` and `postgres-web.wasm`, and where the handler's login spends its time. Done
when a handler's login costs about what the CLI component's does. Pin with a test asserting what the
fix changes (the code path or the representation in the handler build, as j30's fuel budget does),
not a time.

## Repro

```text
docker run -d --rm --name scram-pg -p 127.0.0.1:54331:5432 -e POSTGRES_PASSWORD=pw postgres:18-alpine -c log_connections=all
export DATABASE_URL=postgresql://postgres:pw@127.0.0.1:54331/postgres
rontolisp examples/db/postgres-hello.lisp -o pg-hello.wasm --component --optimize
rontolisp examples/db/postgres-web.lisp -o pgweb.wasm --component --optimize
wasmtime run -S tcp=y -S inherit-network=y --env DATABASE_URL pg-hello.wasm
wasmtime serve --addr 127.0.0.1:18089 -S cli=y -S tcp=y -S inherit-network=y -S p3=y -W component-model-async=y --env DATABASE_URL pgweb.wasm &
curl -s -o /dev/null http://127.0.0.1:18089/   # a few times
docker logs scram-pg 2>&1 | grep -o 'authentication=[0-9.]* ms'
```
