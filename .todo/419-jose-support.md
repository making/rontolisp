# 419. jose (JOSE / JWT) support (parent)

Difficulty: Medium

Parent item for making [jose](https://github.com/fukamachi/jose) -- Eitaro
Fukamachi's JSON Object Signing and Encryption / JWT library (BSD 2-Clause,
quicklisp dist `jose-20250622-git`) -- loadable and usable on rontolisp. The
children are the gaps a spike found (2026-08-16); this file holds the picture,
the verified baseline and the ordering. Every child is a general gap; none of
them is jose-only.

## What the spike did

`(ql:quickload "jose")` fetched `jose-20250622-git` into the quicklisp cache.
The `.asd` was patched form by form until the system loaded, then the working
tree was patched until the README's own program ran; each patch is one row of
the gap list below and all of them were reverted afterwards.

Dependency status, probed one by one: **cl-base64, split-sequence,
assoc-utils, alexandria, trivial-utf-8 (over the `mgl-pax-bootstrap` shim) and
ironclad all load today, unpatched**. `cl-json` needed three `.asd` parse
widenings (`.todo/420`) and nothing else -- its Lisp sources load verbatim on
all four backends. jose itself is a `:class :package-inferred-system`, which is
supported (`.kb/asdf.md`), so its four files load off their own `defpackage`
headers with no `:components` list.

## The baseline the spike reached

With the workarounds applied, **`jose:encode` runs on all four backends and is
byte-identical across them**: interpreter, JVM (`-o X.class`), WASM Preview 1
and WASM `--component` all produced the same token (jose compiles in EH mode --
`handler-case` -- so both wasm runs need `-W gc=y -W exceptions=y`).

The tokens were checked against two independent oracles, not against ourselves:

- HS256/HS384/HS512 over `(("hello" . "world"))` with the key
  `(ironclad:ascii-string-to-byte-array "my$ecret")` match Python's
  `hmac`/`hashlib` byte for byte.
- The HS256 signature octets match the ones jose's own README publishes.
- `(ironclad:digest-sequence :sha512 "abc")` matches the FIPS 180-2 vector.

| case | result |
| --- | --- |
| `jose:encode` HS256 / HS384 / HS512 / none | OK, all four backends |
| `jose:decode`, string-valued claims | OK on the INTERPRETER; the compiled backends need `.todo/423` |
| `jose:decode` / `inspect-token`, any number / boolean / null / nested claim (i.e. `exp`, `iat`, `nbf`) | the `.todo/411` half is DONE (cl-json's decoder scans numbers / `true` / `false` / `null` / nested aggregates on a stream HANDLE now); re-probed 2026-08-18 and the remaining blocker is `.todo/421` -- `jose/base64`'s `base64url-decode` dies in `trivial-utf-8::logtest` |
| wrong key -> `jws-verification-error` (through `cerror`) | OK |
| malformed token -> `jws-invalid-format` | OK |
| `exp` / `nbf` / `iat` / `jti` claim checks, `:issuer` / `:audience` / `:subject` | OK |
| RS256 / RS384 / RS512, PS256 / PS384 / PS512 | OK once ironclad carries the real public-key stack (`.todo/424`) |
| `ironclad:generate-key-pair :rsa` | OK -- 2048 bits in ~3.2 s on the interpreter, sign ~0.28 s, verify ~0.23 s |

HS384/HS512 need SHA-384/512, and every RS\*/PS\* algorithm needs RSA; both are
`.todo/424`. HS256 and `:none` need neither.

## The children

Blockers, in the order that unblocks the most:

1. `.todo/420` -- three `.asd` parse widenings `cl-json.asd` wants: a top-level
   `(progn ...)`, a component name written as a symbol (`(:module :t ...)`),
   and a `defparameter` whose value is not pure data. Without these
   `(ql:quickload "jose")` never reaches a single Lisp file.
2. ~~`.todo/411` -- `unread-char` on a stream HANDLE signals~~ DONE
   (2026-08-18): a stream handle has its own one-slot pushback now, and
   `(json:decode-json-from-string "{\"exp\":1700000000,\"ok\":true,\"n\":null,\"a\":[1,2,{\"b\":3}]}")`
   answers `((:EXP . 1700000000) (:OK . T) (:N) (:A 1 2 ((:B . 3))))`. That was
   the JSON half of `jose:decode`; the token half is blocked next by `logtest`
   below.
3. `.todo/421` -- `logtest` does not exist. `trivial-utf-8`'s
   `utf-8-bytes-to-string` calls it, so decoding a token's payload to a string
   fails even before the JSON layer.
4. `.todo/422` -- `loop for VAR fixnum = INIT then STEP`: the simple type-spec
   between the variable and its subclause is rejected. ironclad's `math.lisp`
   uses it twice, which is what stops RSA key generation.
5. `.todo/423` -- `progv` is interpreter-only, and cl-json's decoder binds its
   scope variables with it. Every compiled backend refuses the whole program,
   so `jose:decode` is interpreter-only until this lands.
6. `.todo/424` -- the ironclad slice carries SHA-256 only and stubs
   `public-key.lisp`, so HS384/HS512 and the entire RS\*/PS\* family are
   unavailable. The spike loaded the REAL `sha512.lisp`, `math.lisp`,
   `public-key/{public-key,pkcs1,rsa}.lisp` on top of the slice and all of them
   worked, the existing PRNG shim included.

Not a rontolisp gap, recorded so it is not rediscovered: PS512 with a 1024-bit
key fails ironclad's own `(>= num-bytes (+ (* 2 digest-len) 2))` assertion.
PSS/SHA-512 needs a key of at least 1040 bits; 2048 works.

## Vendoring, for the E2E

`AsdfLibraryE2eSupport` needs the sources under `src/test/resources`.
Already vendored: alexandria, assoc-utils, cl-base64, ironclad, split-sequence.
To add: **jose** (BSD 2-Clause), **cl-json** (MIT-style, has `LICENSE`),
**trivial-utf-8** (has `COPYING`).

jose's own `jose/tests/jws` suite cannot be used as the oracle: it
`(:use #:pem)`, and `pem` is not in the Quicklisp distribution. `jose/tests/jwt`
uses only rove + `jose/jwt` and IS runnable under `rontolisp test` once
`.todo/411` and `.todo/423` land -- make that the pinning target, with the
cross-checked HS\* tokens above as the value oracle.

## Definition of done

`(ql:quickload "jose")` loads the UNPATCHED upstream system, and a program doing
`jose:encode` / `jose:decode` / `jose:inspect-token` over HS256/384/512 and the
RSA family -- with integer `exp` / `iat` claims and the claim-check keywords --
runs identically on all four backends. Then: `JoseE2eTest` via
`AsdfLibraryE2eSupport`, the `jose/tests/jwt` suite under `rontolisp test`, an
`examples/asdf/jose-demo.lisp` + README row, the `guides/asdf-systems.md` row
(en+ja), and `.kb/asdf.md`'s entry recording what the slice widening owns.

## Non-goal

JWE (encryption). jose implements JWS/JWT only, so there is nothing to load;
re-scope if upstream adds it.
