# 424. The ironclad slice carries no SHA-384/512 and stubs the public-key module

Difficulty: Medium

`ironclad-slice.asd` -- the hand-authored replacement for ironclad's executable
`.asd` (`.kb/asdf.md`) -- declares the reduced core plus SHA-256, HMAC and the
HKDF/PBKDF2 KDFs, which is exactly what cl-postgres' SCRAM-SHA-256 handshake
needed when it was written. Two of its narrowings have a consumer now:

- `(ironclad:digest-sequence :sha384 ...)` / `:sha512` signal
  **Digest SHA384 is not a supported digest** -- there is no
  `ironclad/digest/sha512` subsystem.
- `src/public-key/public-key.lisp` is replaced by a leaf-module SHIM
  (`ironclad-public-key.lisp`) exposing only `octets-to-integer` and
  `integer-to-octets`, so `generate-key-pair`, `sign-message`,
  `verify-signature` and `destructure-private-key` do not exist.

jose (`.todo/419`) wants both: HS384/HS512 need the digests, and the whole
RS256/384/512 + PS256/384/512 family needs RSA. The slice's own comment gives
the reason each was left out ("3,065 lines of RSA/DSA/ElGamal/elliptic-curve
machinery that the loadable slice has no route to"), which is the
re-evaluation trigger `CLAUDE.md` asks for -- and the spike measured that the
reason no longer holds.

## What the spike verified (2026-08-16, interpreter)

Loading the REAL upstream files on top of the loaded slice, in ironclad's own
order, all of them loaded and RAN unpatched:

| file | result |
| --- | --- |
| `src/digests/sha512.lisp` | loads; `:sha512` of `"abc"` matches the FIPS 180-2 vector |
| `src/math.lisp` | loads (needs `.todo/422`, the `for v fixnum = ...` clause) |
| `src/public-key/public-key.lisp` | loads |
| `src/public-key/pkcs1.lisp` | loads |
| `src/public-key/rsa.lisp` | loads |

and then `(ironclad:generate-key-pair :rsa :num-bits 2048)` ->
`sign-message` -> `verify-signature` round-trips, PSS included. Timings on the
interpreter: keygen ~3.2 s, sign ~0.28 s, verify ~0.23 s (2048 bits).

Two narrowings turn out to cost nothing:

- **The PRNG shim is enough.** `ironclad-prng.lisp` draws from
  `rontolisp:random-bytes` on all four backends, and that is what RSA key
  generation and the PSS salt consume -- no Fortuna, no `os-prng.lisp`.
- **`math.lisp` needs no special handling**; it is an `ironclad/core` component
  in the real `.asd` too, and the bignum arithmetic it wants already works.

Not a rontolisp gap, recorded so it is not rediscovered: PS512 with a 1024-bit
key trips ironclad's own `(>= num-bytes (+ (* 2 digest-len) 2))` assertion.
PSS/SHA-512 needs >= 1040 bits.

## The work

Add `ironclad/digest/sha512` (`src/digests/sha512.lisp` -- it defines SHA-384
and SHA-512 both) and an `ironclad/public-key/rsa` subsystem over
`src/math.lisp` + `src/public-key/{public-key,pkcs1,rsa}.lisp`, and make both
dependencies of the aggregate `ironclad` system, following the file's existing
rule about load order (the comment on why `kdf.lisp` is loaded last applies to
`make-instance` of a class from a subsystem outside the slice, and the same
question must be answered for `public-key.lisp`'s class registry).

The `ironclad-public-key.lisp` shim is then RETIRED, not kept beside the real
file: the real one defines the same two converters (the shim reproduces them
verbatim from v0.61), so keeping both is a redefinition race. The check that
decides it: cl-postgres' SCRAM path, which is the shim's only current caller,
must be re-run on all four backends (`.kb/asdf.md`, the postgres probe recipe).

The other public-key files (DSA, ElGamal, the elliptic curves, ed25519) stay
OUT: `rsa.lisp` loads without them, and each is its own consumer question.

## Definition of done

`(ql:quickload "ironclad")` gives `:sha384` / `:sha512` digests and HMACs, and
RSA `generate-key-pair` / `sign-message` / `verify-signature` /
`destructure-private-key` with and without PSS, on all four backends -- pinned
by widening `IroncladE2eTest` with published vectors (FIPS 180-2 for the
digests, RFC 4231 for HMAC-SHA-384/512, and a fixed key pair for RSA so the
signature is a constant rather than a fresh key each run). `ironclad-slice.asd`'s
header comment and `.kb/asdf.md`'s ironclad entry record what is now in and what
is still deliberately out, and the cl-postgres SCRAM handshake is re-verified
against `postgres:17-alpine` after the shim retirement.
