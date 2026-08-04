# The lack ecosystem (`lack-request` / `lack-response` / the middleware set)

The `.todo/231` half of the Clack milestone (`.kb/clack.md`): Eitaro Fukamachi's
Lack request/response layer and its middleware load VERBATIM from the Quicklisp
dist and run for real -- body parsing (urlencoded and multipart) and a session
round trip.

## What loads, verbatim, with zero userland workarounds

`lack-request`, `lack-response`, `lack-util`, `lack-middleware-accesslog`,
`-auth-basic`, `-mount`, `-static`, `-when`, `-session`, `-csrf`, `lack-app-file`,
plus the dependency closure the request half drags in: `http-body` -> `fast-http`
-> `smart-buffer` + `circular-streams`, `yason`, `quri`, `trivial-mimes`,
`trivial-rfc-1123`, `xsubseq`, `proc-parse`, `cl-utilities`.

Nothing in that list is patched. Getting there took four language/runtime items
that each have their own `.kb` section, and every one of them is general -- none
is a lack-shaped special case:

| gap | where it lives now |
| --- | --- |
| `defgeneric (:method-combination progn :most-specific-last)` (yason's `encode-slots`) | `.kb/clos.md` |
| `:if-exists :append` (smart-buffer's disk spill) | `.kb/read-load-streams.md` |
| `uiop:with-temporary-file` + the temporary-file trio; built-in `:depends-on` edges | `.kb/asdf.md` |
| `flex:make-in-memory-input-stream` / `flex:vector-stream` as real Gray streams | `.kb/gray-streams.md` |
| a KEYWORD `:conc-name` (fast-http's `(defstruct (http (:conc-name :http-)))`) | `.kb/defstruct.md` |
| `*package*`-derived symbol construction (alexandria `format-symbol t`, macro expansion package) | `.kb/packages.md` |

## The chain is INTERPRETER-ONLY, and the two reasons are pre-existing ceilings

`lack-request` compiles on neither compile backend today, and both failures are
LOUD compile-time errors of documented invariants -- not silent run-time
divergence, and not caused by this work:

- **JVM**: fast-http's `parse-header-field-and-value` is a generated state
  machine whose body outgrows the signed 16-bit branch offset
  (`.kb/jvm-method-size-limits.md`; `GOTO_W` exists in `am.ik.jvm.Opcode` but no
  emitter uses it and there is no relaxation pass).
- **both WASM backends**: http-body's `slurp-stream` spells
  `(apply #'concatenate '(simple-array (unsigned-byte 8) (*)) ...)`, outside the
  literal result-type family the compilers accept
  (`.kb/concatenate-result-families.md`).

The tree-shaker cannot rescue either: `http-body:parse` dispatches to the
multipart parser, so the giant defun is reachable from any `lack-request` program.
Tracked in `.todo/256`.

**Re-evaluation trigger**: when either ceiling lifts, re-run
`LackEcosystemE2eTest`'s lack leg on that backend and promote it from the
interpreter-only test to the four-backend shape.

## The substrate DOES run on all four backends

What sits under the chain -- `smart-buffer` + `flexi-streams` -- is compiled and
run on every backend by `LackEcosystemE2eTest`:

- an in-memory octet stream through `read-byte` / `read-sequence` /
  `file-position` (the value `finalize-buffer` hands the multipart parser for
  every body under the memory limit);
- the DISK-SPILL path: a payload past `smart-buffer:*default-memory-limit*` lands
  in a `uiop:with-temporary-file` temporary and every further chunk APPENDS to it.
  Interpreter and JVM spill for real; both WASM backends signal the standard
  `ensure-directories-exist` message at CALL time -- the documented
  no-directory-creation divergence (`.kb/directory-listing.md`), reported through
  `handler-case` rather than trapping. Lifting that is `.todo/257` (two more
  preview1 imports plus their `wasi:filesystem@0.3.0` adapter halves).

## Tests

`LackEcosystemE2eTest` (opt-in `RONTOLISP_LACK_E2E=1`: Docker for the pinned
wasmtime, network for the first quickload). Five legs -- the lack chain on the
interpreter, the substrate on all four backends. The language-level pins live
with their own topics (see the table above) and in `ci-spec.yaml`
(`open-if-exists-append-keeps-the-existing-content`,
`defgeneric-short-form-method-combination`, `defstruct-keyword-conc-name`).
