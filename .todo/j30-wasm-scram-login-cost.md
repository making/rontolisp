# WASM component: a PostgreSQL SCRAM-SHA-256 login costs 0.35-0.7s

Difficulty: High

cl-postgres's SCRAM-SHA-256 login (PBKDF2-HMAC-SHA-256, PostgreSQL's 4096 iterations, through ironclad)
is several times slower on the component backend than on the interpreter. A program opening a
connection per request, as `examples/db/shiori` does, pays it on every request; PostgreSQL 18
deprecates md5, so SCRAM is what a server will ask for.

## Measured (2026-11-09)

`examples/db/postgres-hello.lisp` against `postgres:18-alpine` (default `scram-sha-256`), the server's
`log_connections=all` `authentication=` time, rontolisp `6686836` native executable:

| backend | authentication |
|---|---|
| interpreter (the native executable) | 81ms |
| JVM class (`java -cp . PgHello`, one cold run) | 1380ms |
| component under `wasmtime run` 49, macOS arm64 | 345ms |
| component on sluice's wasi wicket (wasmtime 50, Linux arm64 container) | 698ms |

Shiori on that wicket: 1.18s a page with SCRAM against 0.47s with md5.

## What to do

Profile where the component spends the login (SHA-256's 32-bit word arithmetic, HMAC's key padding,
PBKDF2's XOR loop, generic arithmetic or allocation in the compiled code) and make the hot path cheap
on the component backend; the JVM figure is a cold start, check it warm too. Pin with a test asserting
what the fix changes (the code path or the representation), not a time.
