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
run on every backend by the two `LackEcosystem*E2eTest` classes:

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

## Alongside a Clack SERVER, on all four backends (todo-279)

The chain above runs STANDALONE on every backend, but the reason to reach for
Clack at all is a served body: `lack/request:request-body-parameters` over the
buffered `:raw-body` a handler receives. That COMBINATION -- a program that
quickloads both a Clack server and `lack-request` -- did not compile on the JVM
or on either WASM backend until todo-279, and the cause was placement, not
semantics: `HttpServerLibrary` prepends `http-server.lisp` (whose buffered half
subclasses the Gray protocol) at index 0 while the protocol itself arrives at
the trivial-gray-streams shim's splice site, mid-program. The subclass preceded
its base class. Full mechanics and the two rules the repair has to keep:
`.kb/gray-streams.md`.

The four-backend pin is `SERVED_BODY_EXERCISE`: `lack:builder` wraps the app and
`rontolisp::%http-serve-request` -- the one server-side request path every
transport calls -- drives it over a `%http-body-stream` body. It runs the
request DIRECTLY rather than through `clack:clackup` because Preview 1 has no
incoming TCP and a clack program under `--component` is a `wasmtime serve`
component, not a runnable CLI one (`.kb/clack.md`). The clackup-and-fetch
spelling of the same chain is pinned on the interpreter and the JVM.

## `-session` on a `--no-wasi` reactor needs BOTH host hooks (2026-08-09)

The session middleware is the one member of the set that a zero-import reactor
cannot serve on its own, and it needs the two hooks for two DIFFERENT reasons at
two different times (`.kb/wasm-export-no-wasi.md` has both):

- **Load time, the clock.** `lack.middleware.session.state.cookie` defaults its
  `expires` slot to `(get-universal-time)` -- a top-level read while the system
  loads -- so without `__ronto_set_time` called before `_initialize` the module
  dies during initialization, not at the first request.
- **Request time, real entropy.** The session id is `rontolisp:random-bytes`,
  which a `--no-wasi` build refuses unless `--host-random` points `random_get`
  at the host: the module-local generator must not be passed off as
  cryptographic entropy, and a session id is exactly the case that rule is
  about.

With both, the whole `clack` + `lack:builder (:session)` stack instantiates,
answers, sets `lack.session=<id>` and recognises the cookie on the next request
(measured on node 24). Worth knowing while reading that cookie: upstream's
`expires` DEFAULT is a timestamp rather than a duration and the header is
`(+ (get-universal-time) expires)`, so a default-configured cookie expires
roughly twice the current universal time from now -- around 2153. That is
upstream's arithmetic, identical on every backend, not a clock artifact.

## The DEFAULT middleware has to report the APPLICATION's error (todo-283)

`clack:clackup` wraps every application in lack's `:backtrace` middleware unless
`:use-default-middlewares nil`. That middleware defaults its `output` parameter to the
SYMBOL `'*error-output*` and reports through `(symbol-value output)` -- and on all three
compile backends that call was itself an error, so a failing handler's ACTUAL condition
was replaced by `The variable *ERROR-OUTPUT* is unbound` behind a bare 500 (finding the
real fault meant re-running with `:use-default-middlewares nil` to get the middleware out
of the way). Two general fixes, neither lack-shaped: the eval runtime's variable mirror
now seeds the standard stream defaults (`.kb/symbol-runtime-api.md`), and the JVM's
stream table exists from the start when the standard handles are reserved
(`.kb/standard-output-redirect.md`). Pinned by
`LackEcosystemE2eTest#backtraceMiddlewareReportsTheApplicationsRealError*` (interpreter
and JVM -- it needs a real socket, like the clackup legs), which asserts the 500 AND that
stderr names the handler's own condition.

## Tests

`LackEcosystemE2eTest` -- the interpreter and JVM legs, NO container -- and
`LackEcosystemWasmE2eTest` -- the Preview 1 and `--component` legs, Docker for
the pinned wasmtime. Both opt-in (`RONTOLISP_LACK_E2E=1`; network for the first
quickload), sharing their programs through `LackE2eSupport`. **The split is
load-bearing, not tidiness**: with every leg in one `@Testcontainers` class, a
machine without Docker skipped the container-free legs too, and reported
`Tests run: 2, Skipped: 2, BUILD SUCCESS` while the only test covering the
served-body regression never ran. Anything Docker-gated belongs in the Wasm
class and nowhere else.

Sixteen legs -- the lack chain, the substrate and the served body, each on all
four backends, plus the clackup-and-fetch spelling and the backtrace-middleware report
on the two that can serve.
The splice ORDERING itself is pinned separately by `GrayStreamsLibraryTest`,
which needs neither Docker nor network and therefore runs in a plain
`./mvnw test`. The language-level pins live
with their own topics (see the table above) and in `ci-spec.yaml`
(`open-if-exists-append-keeps-the-existing-content`,
`defgeneric-short-form-method-combination`, `defstruct-keyword-conc-name`).
