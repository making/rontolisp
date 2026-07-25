# ironclad: real loading of the SCRAM-SHA-256 subsystem slice

Parent: `.todo/115` (cl-postgres). Design line: `.todo/147` (real libraries over
shims).

## Status: the slice is DONE (2026-07-25)

The `ironclad/core` + `digest/sha256` + `mac/hmac` + `kdf/pkcs5` + `kdf/kdf`
slice loads from ironclad v0.61's REAL sources and reproduces the FIPS 180-2,
RFC 4231 and RFC 7677 vectors byte-identically on ALL FOUR backends
(`IroncladE2eTest`; sources vendored under `src/test/resources/ironclad`).
Mechanics, the deliberate load-order/scope deviations and the mechanism that
made an executable `.asd` loadable at all: `.kb/asdf.md` (the `AsdOverrides`
replacement-`.asd` tier + the ironclad slice section). The features it forced
are recorded in `.kb/defstruct.md` (`:include`, `:type (vector ...)`),
`.kb/clos.md` (the instance-initialization protocol, keyword congruence,
`call-next-method` rest forwarding, ambiguous slot writes, runtime `typep`),
`.kb/symbol-runtime-api.md` (`symbol-name` drops the qualifier; `find-package`
/ `symbol-package` / `type-of` / 2-argument `find-symbol`; quoted lone symbols
resolve) and `.kb/reader-features.md` (`#N@(...)`, readtable no-ops).

`.todo/115` can now take the real dependency for MOST of what it needs -- but
NOT all of it. Verified by grepping `scram.lisp` (2026-07-25): of the nine
`ironclad:` names it calls, six are in this slice (`digest-sequence`,
`make-hmac`, `update-hmac`, `hmac-digest`, `ascii-string-to-byte-array`,
`hex-string-to-byte-array`) and THREE are not:

- `pbkdf2-hash-password` (`kdf/password-hash.lisp`, 61 lines). Its body is the
  one-liner `(pbkdf2-derive-key digest password salt iterations
  (digest-length digest))`, all of which this slice has. The file's only
  out-of-slice reference is `make-random-salt` (prng/), and only as the DEFAULT
  of its `:salt` keyword -- cl-postgres passes an explicit salt, so the default
  never evaluates on the interpreter. The compile paths are eager, so a
  `make-random-salt` stub (or a prng slice) is needed there.
- `integer-to-octets` / `octets-to-integer` (`public-key/public-key.lisp`).
  Both are self-contained `ldb`/`loop` byte<->integer converters -- no
  arbitrary-precision math, no elliptic curves; they merely LIVE in the 3,065-line
  public-key file. Loading that file whole is not viable, so the route is a
  `ShimLibraries.leafModuleForms` substitution for `public-key.lisp` exposing
  just these two (the jzon numeric-leaf precedent).

Neither is on the critical path for the first cl-postgres milestones: SCRAM is
the LAST auth method in `.todo/115`'s M5 order (trust -> password -> md5 ->
SCRAM), and trust auth uses no digests at all.

## What is left

1. **`concatenate` with a non-string result type.** `(concatenate '(vector
   (unsigned-byte 8)) a b)` / `(concatenate 'list ...)` signal "concatenate
   supports only the string result type" on every backend. This is a general CL
   gap, not an ironclad one; it is the single reason `kdf/hmac.lisp` (HKDF,
   RFC 5869) is excluded from the slice, and it also blocks `#'concatenate` as a
   first-class value (a `BuiltinFunctionWrappers` entry needs a working
   multi-type `concatenate` first). Fixing it re-enables `ironclad/kdf/hmac` by
   adding the file back to `ironclad-slice.asd` -- nothing else.
2. **Struct predicate vs later `:include` children.** `(base-p child)` is `NIL`
   when the child's defstruct follows the parent's, because the predicate bakes
   the descendant tags known at its own definition site. `typep` is correct. The
   two candidate fixes and why neither was taken are written into
   `.kb/defstruct.md`; `LispEvaluatorTest#defstructIncludeInheritsSlotsAndTypeTests`
   pins the current answer, so that test is the thing to change.
3. **Widening the slice** (ciphers / public-key / prng / the other digests) is
   NOT planned: nothing needs it. The next real consumer decides. `dotimes-unrolled`
   users stay out until then -- its DEFINITION loads, but no expansion of it does
   (`symbol-macrolet` is still unsupported).

## Non-goals

- The full ironclad aggregate system.
- Replacing the frozen `cl-postgres-wip` M2 JDK shim decision retroactively.
