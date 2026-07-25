# SCRAM-SHA-256: the last three ironclad names cl-postgres needs

Goal: `cl-postgres`'s `scram.lisp` computes correct SCRAM-SHA-256 values on ALL
FOUR backends, so `.todo/115`'s M5 can reach the auth method PostgreSQL 14+
defaults to (`password_encryption = scram-sha-256`).

Depends on `.todo/174` (arbitrary-precision integers on the wasm-GC backends) --
see "Why 174 first" below. Parent: `.todo/115`. Slice history: `.kb/asdf.md`.

## What is missing (grepped 2026-07-25)

Of the nine `ironclad:` names `scram.lisp` calls, six are in the loadable slice
(`digest-sequence`, `make-hmac`, `update-hmac`, `hmac-digest`,
`ascii-string-to-byte-array`, `hex-string-to-byte-array`). Three are not:

1. **`pbkdf2-hash-password`** (`src/kdf/password-hash.lisp`, 61 lines). Its body
   is the one-liner `(pbkdf2-derive-key digest password salt iterations
   (digest-length digest))` -- every part of which the slice already has. The
   file's only out-of-slice reference is `make-random-salt` (prng/), and only as
   the DEFAULT of its `:salt` keyword; cl-postgres always passes an explicit
   salt, so on the interpreter the default never evaluates, but the compile paths
   are eager and need a `make-random-salt` stub (or a real prng slice -- decide
   which; a stub that signals is honest since no caller here wants a random
   salt). Route: add the file to `ironclad-slice.asd` as its own subsystem.
2. **`integer-to-octets`** and **`octets-to-integer`**
   (`src/public-key/public-key.lisp`). Both are self-contained `ash`/`ldb`/
   `integer-length`/`ceiling` converters -- no modular exponentiation, no curves
   -- that merely LIVE in a 3,065-line file which cannot load whole. Route: a
   `ShimLibraries.leafModuleForms` substitution for `public-key.lisp` exposing
   exactly these two (the jzon numeric-leaf precedent, `.kb/asdf.md`).

`saslprep.lisp`'s dependencies are already real on four backends (uax-15 NFKC,
cl-base64).

## Why 174 first

`scram.lisp:316` `gen-client-proof` XORs two 32-byte digests **as integers**:

```lisp
(let* ((int (logxor (ironclad:octets-to-integer client-key)
                    (ironclad:octets-to-integer client-signature)))
       (octet-arry (ironclad:integer-to-octets int)))
  (pad-octet-vector octet-arry 32))
```

That is 256-bit arithmetic. Measured: exact on the interpreter and the JVM
(`LispBigInteger`); on both wasm-GC backends the 256-bit LITERAL alone fails to
compile, because an exact integer there is at most a boxed i64
(`.kb/wasm-bignum.md`). So a four-backend SCRAM is impossible before 174 --
this todo's WASM legs are blocked on it by construction, not by effort.

Also measured, and the reason this todo blocks `.todo/115`'s compile-path work
rather than following it: an undefined function is a **compile-time** error on
the JVM/WASM backends (`Cannot compile: SOME-UNDEFINED-FN` from
`JvmFunctionCallCompiler`), while the interpreter defers it to the call. So
cl-postgres cannot be COMPILED at all until these three names exist, even for a
trust-auth connection that never runs SCRAM.

## Acceptance

Scoped deliberately to the crypto, NOT to a live connection -- a real SCRAM
handshake additionally needs `.todo/115`'s M4 primitives, and that is its own
gate:

- `IroncladE2eTest` (or a sibling) reproduces, on all four backends: the
  `pbkdf2-hash-password` form of the RFC 7677 `SaltedPassword`, and a
  `gen-client-proof`-shaped round trip (`octets-to-integer` -> `logxor` ->
  `integer-to-octets` -> pad to 32) against a known vector, including the
  **leading-zero-byte edge** (`integer-to-octets` drops leading zeros, which is
  exactly why cl-postgres pads to 32 -- an off-by-one here produces a silently
  wrong proof that only shows up as an auth failure).
- A ci-spec case is NOT possible for the ironclad part (it needs the `.asd` on
  disk at compile time, like every ASDF library case).
- `.kb/asdf.md`'s ironclad section updated: slice contents, and the fact that
  `public-key.lisp` is present only as a two-function leaf substitution.

Then `.todo/115` M5 exercises the real handshake in its trust -> password ->
md5 -> SCRAM order against Testcontainers PostgreSQL.

## Non-goals

- Channel binding (`SCRAM-SHA-256-PLUS`): cl-postgres does not implement it.
- The rest of `public-key/` (RSA/DSA/ECC) and `prng/` -- see `.kb/asdf.md` for
  why the full aggregate stays out of reach.
- `.todo/115`'s M4 primitives (`encode-universal-time`, `force-output`,
  `with-standard-io-syntax`, `(listen socket)`, the `loop` shapes).
