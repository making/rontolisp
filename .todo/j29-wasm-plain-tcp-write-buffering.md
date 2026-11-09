# WASM component: plain TCP writes go out one by one and wait on delayed ACKs

Difficulty: Medium

On the component backend a plain TCP socket writes through (`.kb/tcp-sockets.md`, "A TLS entry BUFFERS
its writes ... Plain TCP still writes through"): each write is a separate `wasi:sockets` write, and so a
separate segment. The host's socket has Nagle on (wasmtime-wasi 50 sets no `TCP_NODELAY`, and
wasi:sockets has no option for it), so a message written in pieces, as cl-postgres writes its
messages, holds each piece until the peer ACKs the last, and the peer delays that ACK (40ms on Linux).

## Measured (2026-11-09)

`examples/db/shiori` as a component on sluice's wasi wicket (wasmtime 50, a Linux container on
OrbStack), PostgreSQL 18 in the next container, md5 auth:

| | `/books` (6 statements) | first start (schema and sample data) |
|---|---|---|
| as is | 0.47s | 15.6s |
| delayed ACK off on PostgreSQL's side (`ip route change ... quickack 1`) | 0.025s | 0.24s |

PostgreSQL's statement log: 84ms from each `parse` to its `bind`, 43ms to the next `parse`. On a macOS
host's loopback the same component answers a page in about 0.1s (no delayed ACK there), so the stalls
show on Linux, where such a component is deployed.

## What to do

Buffer a plain TCP entry's writes as the TLS entry's are, flushed at the same points (a read that must
ask the host, `listen`, `force-output`/`finish-output`, `close`, a full buffer); cl-postgres and the
other socket users already `force-output` at a message's end. Weigh dropping the TLS-only machinery's
`(wait-for 1)` for plain TCP, where wasmtime does not drop a write's tail. Pin it where the TLS buffer
is pinned, and measure Shiori again (sluice's `.kb/shiori-on-wasi-wicket.md` has the setup).
