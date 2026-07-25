# ironclad: real loading of the SCRAM-SHA-256 subsystem slice

Parent: `.todo/115` (cl-postgres; ironclad is reached only from scram.lisp:
sha256 / hmac / pbkdf2). Design line: `.todo/147` (real libraries over shims).
This re-opens the "real loading judged INFEASIBLE" verdict recorded when the
dependency grind closed, because both grounds for it have weakened:

- **The executable `.asd`** (defclass on cl-source-file, a defmacro generating
  defsystems, uiop at parse time) blocked *parsing*, not *loading*: the
  BuiltinSystems mechanism (usocket precedent) can hand-author the subsystem
  file lists and never read `ironclad.asd` at all.
- **The 32-bit working state** was the WASM blocker; the boxed exact-integer
  path (`.kb/wasm-bignum.md`) landed and md5 -- the same arithmetic class --
  is REAL on all four backends.

## The needed slice (ironclad v0.61, already in ~/.rontolisp/quicklisp)

`ironclad/core` (minus opt/, doc/, static files) + `digests/sha256` +
`macs/hmac` + `kdf/pbkdf2`, concretely:

    src/package.lisp conditions.lisp generic.lisp macro-utils.lisp util.lisp
    common.lisp digests/digest.lisp digests/sha256.lisp
    macs/mac.lisp macs/hmac.lisp kdf/kdf.lisp kdf/hmac.lisp kdf/pkcs5.lisp

~3-4k lines. Probed 2026-07-25:

- `dotimes-unrolled` (the one `symbol-macrolet` + `&environment` +
  `trivial-macroexpand-all` user in macro-utils.lisp) is used ONLY by ciphers
  and non-SHA-2 digests -- nothing in the slice expands it. Its DEFINITION
  still has to load: needs `defmacro` with `&environment` accepted (can be
  ignored) and `loop ... collect ... finally (return ...)`.
- `bordeaux-threads` (a `:depends-on` of ironclad/core) has zero call sites in
  the slice (it serves prng/); shim it as an empty leaf module
  (`ShimLibraries.leafModuleForms` precedent from the jzon numeric shims).
  `#+sbcl sb-rotate-byte` / `sb-posix` drop out via features.
- CLOS surface: generic.lisp (~40 defgenerics) + digest.lisp (defclass
  digest hierarchy, defmethod dispatch, `make-instance` via `make-digest`) --
  inside the post-todo-116/jzon CLOS subset on paper; the register machinery
  in common.lisp (`define-digest-registers`, struct-backed state,
  `(unsigned-byte 32)` arrays, `rotate-byte` portable fallback) is the part
  most likely to hit a gap.

## Plan

1. Interpreter first: BuiltinSystems entry for `ironclad/digest/sha256` (+
   mac/kdf slices), leaf-module shim for bordeaux-threads, then grind file by
   file (the md5/cl-ppcre workflow). Gate: `(ironclad:digest-sequence :sha256
   ...)` against a NIST vector, then `ironclad:make-hmac` + RFC 4231 vectors,
   then `ironclad:derive-key` PBKDF2 against RFC 7677's SCRAM test vector.
2. Compile paths + WASM after the interpreter is green (same order as md5).
3. E2E: `IroncladE2eTest` via AsdfLibraryE2eSupport + the asdf-systems guide
   rows + an examples/asdf demo (the library-integration checklist).

## Non-goals

- The full ironclad aggregate system (ciphers, aead, public-key, prng): the
  cl-postgres gate needs only the slice above. `dotimes-unrolled` users stay
  out of scope until something needs them.
- Replacing the frozen `cl-postgres-wip` M2 JDK shim decision retroactively;
  if this lands, scram.lisp simply gets the real dependency when `.todo/115`
  resumes.
