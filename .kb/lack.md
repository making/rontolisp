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

## The chain runs on ALL FOUR backends (todo-256, 2026-08-04)

The two compile-backend ceilings that kept the chain interpreter-only are
lifted, and enabling the compile exposed two further latent bugs that this
work fixed -- all four fixes are general, none lack-shaped:

- **JVM branch relaxation**: fast-http's `parse-header-field-and-value` (36.7 KB
  body, generated `tagcasev` state machine) outgrew the signed 16-bit branch
  offset. `am.ik.jvm.BranchRelaxer` now rewrites the out-of-range branches over
  `goto_w` (`.kb/jvm-method-size-limits.md`). The body clears the 65535-byte
  code cap with room (the chain's largest is `http-multipart-parse` at 49.7 KB).
- **`concatenate` deftype-alias result types**: the actual blocker was
  fast-http's `(concatenate 'simple-byte-vector ...)` -- a parameterized user
  deftype alias of the packed octet vector; the compound
  `'(simple-array (unsigned-byte 8) (*))` spelling was already in the vector
  family. `ConcatenateForms.resultFamily` now resolves a designator naming a
  registered deftype through its expansion, on every backend
  (`.kb/concatenate-result-families.md`).
- **babel package redirect** (latent on the interpreter too): http-body's
  `detect-charset` defaults from `babel:*default-character-encoding*` while the
  shim's defvar spells `babel-encodings:` -- two distinct textual symbols until
  the babel package records the two babel-encodings members as import redirects
  (`.kb/packages.md`; `PackageResolverTest#babelSpellingsOfTheBabelEncodingsMembersResolveToTheirHome`).
- **redefined-defun duplicate emission** (latent on BOTH compile backends for
  ANY program): fast-http redefines 11 struct readers as plain defuns. On the
  JVM two methods of one name/descriptor are a load-time `ClassFormatError`
  (now: only the LAST definition is emitted); on WASM the funcIndex reservation
  counted the deduplicating name map instead of the defuns list, shifting every
  top-level-chunk/lambda index (now: `Ctx.numDefuns`;
  `WasmLispCompilerIntegrationTest#redefinedDefunKeepsTheTopLevelChunkIndicesRight`,
  `JvmLispCompilerTest#compileAndRunARedefinedDefunKeepsTheLastDefinition`).

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
wasmtime, network for the first quickload). Eight legs -- the lack chain AND the
substrate, each on all four backends. The language-level pins live
with their own topics (see the table above) and in `ci-spec.yaml`
(`open-if-exists-append-keeps-the-existing-content`,
`defgeneric-short-form-method-combination`, `defstruct-keyword-conc-name`).
