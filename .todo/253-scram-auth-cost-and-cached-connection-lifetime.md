# 253. A cached connection is never reaped under clack

Difficulty: 中 (a lifetime decision to record; the performance half is DONE)

Both surfaced while closing `.todo/249` (the Mito milestone). Neither is a
correctness bug; together they made a served mito/clack app unusable against a
default-configured PostgreSQL.

## 1. `dbi:connect` to a scram-sha-256 server: ~60 s -- DONE 2026-08-16

Closed by the native interpreter PBKDF2 (`eval/IroncladNative` +
`eval/Sha2Kernels`, `.kb/asdf.md` "Native PBKDF2 on the INTERPRETER"): the
4096-round derivation went 17,091 ms -> 9 ms, so a SCRAM connect is ~0.1 s and
cannot race any `authentication_timeout`. The measurement below said to check
the compiled backends first, and that is what decided the shape: they were
already fine (~1 s JVM, ~3 s component), so the fast path is interpreter-only
and the compiled backends keep running ironclad's Lisp -- which is also what
keeps `IroncladE2eTest` an honest cross-backend oracle. What remains of this
half is nothing; the original text is kept below as the record.

### The original measurement

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

- ~~Any DB E2E must create its container with `POSTGRES_HOST_AUTH_METHOD=trust`
  (or `md5`), or raise `authentication_timeout`, or it is flaky.~~ Retired: no
  container setting is load-bearing any more (`.kb/mito.md`).
- ~~The obvious fix is a native `pbkdf2-hmac-sha256` (and the `hmac`/`sha256`
  inner loop) rather than the interpreted ironclad one -- the digest itself is
  already pinned byte-for-byte by `IroncladE2eTest`, so a fast path has an
  oracle to match. Measure the compiled backends first: they may already be
  fine, in which case this is an interpreter-only item.~~ Done, exactly that
  way. One measurement worth keeping: replacing only ironclad's
  `update-sha256-block` / `sha256-expand-block` -- the smallest semantic surface
  -- buys ~4x and does NOT solve this, because the interpreted mdx buffering
  around the compression function is the other 23%.

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
- ~~A scram connect is fast enough that the default `authentication_timeout`
  cannot be raced, or the E2E harness requirement is enforced somewhere a test
  author cannot miss it.~~ Met 2026-08-16.
