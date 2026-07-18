# cl-base64 integration (real library load via asdf:load-system)

**Status: DONE on all four backends (2026-07-18, worktree `worktree-cl-base64-085`,
uncommitted).** cl-base64 v3.4 is vendored under `src/test/resources/cl-base64/`
(BSD, COPYING kept) and `ClBase64E2eTest` exercises encode/decode/uri/columns/
usb8/integer/error-path on interpreter + JVM + WASM P1 + `--component`, all green.
Full mechanics: `.kb/asdf.md` (cl-base64 section) + `.kb/symbol-runtime-api.md`.

## What landed (all confirmed by tests)

- A (keystone): `symbol-name`/`princ`/`~A`/`string` strip the leading `:`/`#:`
  package marker on every backend (`prin1` keeps them); 1-arg `intern` interns
  into the current package on the interpreter (`PackageResolver.internSpelling`).
  User approved the contract change 2026-07-18. json.lisp + pinned tests + docs
  updated; split-sequence/cl-utilities/cl-who/parse-number stay green.
- B: `(setf (schar/char s i) c)` -- `%schar-set` in-place on the interpreter,
  setq-rebuild lowering (`expandScharSetFunctional`, variable place only) on
  JVM/WASM.
- C: `cerror` lowers to `error` (continue-format dropped); `error`/`signal`/
  `warn` gained FUNCTION values (interpreter = full designator via
  `rebuildSignalForm`; compiled = datum-only lite wrappers gated on `#'op`).
- D: `locally`, defclass slot `:type` tolerated, `write-char` (expands to
  `write-string`), `make-array :element-type 'character` = a string,
  `make-array :initial-contents`, `aref` on strings (all three backends).

## Remaining (follow-ups, not blockers)

- WASM i31 limit: `integer-to-base64-string` of an integer beyond 2^30 degrades
  to a float on the WASM backends and produces wrong digits (pre-existing
  numeric model; the E2E pins 1234567). Fixing means a WASM bignum story.
- Compiled `#'error` forwards the datum only: a typed `(apply #'error (list
  'cls :k v))` signals a plain condition named after the class (catchable, but
  slot readers see nothing). Full fidelity needs a runtime condition
  constructor over the compile-time ClosRegistry.
- `*-to-base64-stream` / `base64-stream-to-*` compile but are untested (need CL
  stream objects at the call boundary).
