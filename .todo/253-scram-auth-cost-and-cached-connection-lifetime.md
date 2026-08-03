# 253. A SCRAM PostgreSQL connect costs ~60 s, and a cached connection is never reaped under clack

Difficulty: 中 (two independent items that meet in the same program: a hot-loop
performance item with a clear target, and a lifetime decision to record)

Both surfaced while closing `.todo/249` (the Mito milestone). Neither is a
correctness bug; together they make a served mito/clack app unusable against a
default-configured PostgreSQL.

## 1. `dbi:connect` to a scram-sha-256 server: ~60 s, and it RACES the server

Same box, same program, `postgres:17-alpine`:

| server auth | connect |
| --- | --- |
| default (`password_encryption = scram-sha-256`) | **60031 ms** |
| `POSTGRES_HOST_AUTH_METHOD=trust` | 110 ms |

545x. It is PBKDF2-HMAC-SHA256 x4096 running in interpreted ironclad
(`.kb/asdf.md`, the ironclad slice). PostgreSQL's default
`authentication_timeout` is 60 s, so the connect sometimes LOSES the race and
surfaces as `Database error: end of file` at `Environment.java` while the server
logs `FATAL: canceling authentication due to timeout` -- an intermittent failure
that looks like a socket bug and is not one.

Consequences to fix or record:

- Any DB E2E must create its container with `POSTGRES_HOST_AUTH_METHOD=trust`
  (or `md5`), or raise `authentication_timeout`, or it is flaky. Recorded in
  `.kb/mito.md`; `.todo/250`'s `MitoE2eTest` must follow it.
- The obvious fix is a native `pbkdf2-hmac-sha256` (and the `hmac`/`sha256`
  inner loop) rather than the interpreted ironclad one -- the digest itself is
  already pinned byte-for-byte by `IroncladE2eTest`, so a fast path has an
  oracle to match. Measure the compiled backends first: they may already be
  fine, in which case this is an interpreter-only item.

## 2. `connect-cached` + clack = one connection per request, never disconnected

`dbi.cache.thread:steal-cache-table` keys the pool on `bt2:current-thread`
(`cl-dbi-20260101-git/src/cache/thread.lisp:19-22`) and
`clack-handler-rontolisp` serves each request on a FRESH virtual thread
(`.kb/concurrent-served-requests.md`). So the *cached* branch -- which is
`lack-middleware-mito`'s default for `:postgres` -- opens a new connection per
request and only `cleanup-cache-pool` would reap it, which nothing calls.
Combined with item 1 that is a ~60 s, unbounded-connection app.

The `dbi-deps.asd` override deliberately selects the per-thread cache on the
thread-capable backends (`.kb/asdf.md`), and that is right for a thread POOL --
it is the one-thread-per-request model that makes the key useless. Options, in
order of preference:

1. Give the served-request path a thread whose identity is stable per
   CONNECTION-worthy unit, or
2. call `dbi:disconnect-cached-all` (or `cleanup-cache-pool`) at request end in
   the rontolisp clack handler, or
3. document `:no-cache t` as the supported setting for a served mito app and
   record the reason + trigger in `.kb/mito.md`.

Whatever is chosen, it is a DIVERGENCE from what the upstream middleware
promises and needs its reason written down, not just its "how".

## Acceptance

- A served mito app over N requests opens a bounded number of connections, and
  the number is stated in `.kb/mito.md` (or `.kb/clack.md`) with the reason.
- A scram connect is fast enough that the default `authentication_timeout`
  cannot be raced, or the E2E harness requirement is enforced somewhere a test
  author cannot miss it.
