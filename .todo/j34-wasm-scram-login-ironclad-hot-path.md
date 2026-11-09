# WASM component: take the SCRAM login well below the interpreter's 81ms

Difficulty: High

After j30 and j32, cl-postgres's SCRAM-SHA-256 login (PBKDF2-HMAC-SHA-256, 4096 iterations, through
ironclad) on the component backend is about what the interpreter's native kernel costs, not below
it. Shiori opens a connection per request, so the login is nearly all of a page.

## Measured (2026-11-09, develop `a2b0c2678`, wasmtime 50, PostgreSQL 18 SCRAM, server `authentication=`, Apple silicon Mac)

| program | authentication |
|---|---|
| `postgres-hello.lisp`, CLI component | 95-97ms |
| Shiori under `wasmtime serve` | 127-141ms |
| Shiori on sluice's wasi wicket | 125-153ms (a page 0.15s) |
| interpreter (native executable, PBKDF2 on a native kernel) | 81ms |

## Goal

A component login well below 81ms, and a wasi:http handler build at the CLI component's speed or
better.

## Directions

- ironclad's SHA-256 / HMAC / PBKDF2 hot path as the component backend compiles it: 32-bit word
  arithmetic, u32 kept unboxed in locals, the `(unsigned-byte 8)` arrays, the XOR loop, key padding,
  allocation per iteration. j30 found ironclad doing four SHA-256 compressions per PBKDF2 round where
  two suffice, plus CLOS reinitialization and digest copy/finalize (`.kb/asdf.md`, todo 417).
- The handler-only cost j32 found: serve mode's small heap collects 18-19 times per login (about
  30-50ms on Linux x86-64).
- Anything structural beyond that. The 2026-08-16 decision that the compiled backends run ironclad's
  own code (no native PBKDF2 kernel) stands unless the work shows it should not.

Pin with tests asserting the representation or the code path (as j30's fuel budget does), not a time.

## Repro

`.todo/history` rows of j30 and j32 name the commits; their repro (postgres:18-alpine with
`log_connections=all`, `examples/db/postgres-hello.lisp` and `examples/db/shiori` compiled with
`--component --optimize`) applies. Requested by the sluice session; send it the commits and the
measurements when done.
