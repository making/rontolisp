# The lack ecosystem (`lack-request` / `lack-response` / the middleware set)

Fukamachi's Lack layer loads VERBATIM from the Quicklisp dist and runs on all four backends --
body parsing (urlencoded, multipart) and a session round trip. Part of the Clack milestone
(`.kb/clack.md`).

Loads unpatched: `lack-request`, `lack-response`, `lack-util`, `lack-middleware-accesslog`,
`-auth-basic`, `-mount`, `-static`, `-when`, `-session`, `-csrf`, `lack-app-file`, plus
`http-body` -> `fast-http` -> `smart-buffer` + `circular-streams`, `yason`, `quri`,
`trivial-mimes`, `trivial-rfc-1123`, `xsubseq`, `proc-parse`, `cl-utilities`.

Enabling language gaps, each documented elsewhere: `defgeneric (:method-combination progn
:most-specific-last)` (`.kb/clos.md`); `:if-exists :append` (`.kb/read-load-streams.md`);
`uiop:with-temporary-file` and built-in `:depends-on` edges (`.kb/asdf.md`); flexi-streams
in-memory streams as real Gray streams (`.kb/gray-streams.md`); KEYWORD `:conc-name`
(`.kb/defstruct.md`); `*package*`-derived symbol construction (`.kb/packages.md`).

## Notes

- `am.ik.jvm.BranchRelaxer` rewrites out-of-range branches over `goto_w`
  (`.kb/jvm-method-size-limits.md`); largest body `http-multipart-parse`, 49.7 KB, under the
  65535-byte cap. `ConcatenateForms.resultFamily` resolves a deftype result designator
  (`.kb/concatenate-result-families.md`). The babel package records two babel-encodings members
  as import redirects (`.kb/packages.md`).
- **Redefined defun** (was latent on BOTH compile backends for ANY program): the JVM emits only
  the LAST definition (duplicates are a load-time `ClassFormatError`); WASM reserves funcIndexes
  from `Ctx.numDefuns`, not the deduplicating name map.
- DISK SPILL past `smart-buffer:*default-memory-limit*` uses a `uiop:with-temporary-file`
  temporary each further chunk APPENDS to. Interpreter and JVM spill for real; both WASM backends
  signal the standard `ensure-directories-exist` message at CALL time, through `handler-case`
  (`.kb/directory-listing.md`). Not lifted.
- A program quickloading a Clack server AND `lack-request` is a PLACEMENT problem:
  `HttpServerLibrary` prepends `http-server.lisp` at index 0 while the Gray protocol arrives
  mid-program at the trivial-gray-streams splice site (`.kb/gray-streams.md`).
- `-session` on a `--no-wasi` reactor needs BOTH host hooks (`.kb/wasm-export-no-wasi.md`):
  `__ronto_set_time` before `_initialize` (cookie `expires` defaults to `(get-universal-time)` at
  load time) and `--host-random` (session id is `rontolisp:random-bytes`). Upstream's `expires`
  default is a timestamp, not a duration, so a default cookie expires around 2153 everywhere.
- `clack:clackup` wraps every application in lack's `:backtrace` middleware unless
  `:use-default-middlewares nil`; it reports through `(symbol-value '*error-output*)`, which must
  not itself error or the real condition is lost behind a bare 500 -- fixed by the eval runtime's
  variable mirror seeding the standard stream defaults (`.kb/symbol-runtime-api.md`) and the JVM
  stream table existing from the start (`.kb/standard-output-redirect.md`).

## Tests

- `LackEcosystemE2eTest` (interpreter + JVM, NO container) and `LackEcosystemWasmE2eTest`
  (Preview 1 + `--component`, Docker). Both opt-in (`RONTOLISP_LACK_E2E=1`, network), sharing
  `LackE2eSupport`. **The split is load-bearing**: one `@Testcontainers` class made a Docker-less
  machine skip the container-free legs too and report BUILD SUCCESS. Anything Docker-gated
  belongs in the Wasm class.
- `SERVED_BODY_EXERCISE` -- four-backend served-body pin driving `rontolisp::%http-serve-request`
  directly. `ServeConditionCatchComponentE2eTest` on `ServeComponentE2eSupport` -- a served
  `--component` build must not turn a caught condition into a wasm `unreachable` trap.
- `GrayStreamsLibraryTest` pins splice ORDERING. ci-spec:
  `open-if-exists-append-keeps-the-existing-content`,
  `defgeneric-short-form-method-combination`, `defstruct-keyword-conc-name`.
