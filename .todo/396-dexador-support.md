# 396. dexador support (parent)

Difficulty: Medium

Parent item for making [dexador](https://github.com/fukamachi/dexador) --
the de-facto CL HTTP client -- loadable and usable on rontolisp. The children
are the gaps a spike found (2026-08-16); this file holds the picture, the
verified baseline and the ordering. Each child is independently useful and
none of them is dexador-only.

## What the spike did

`(ql:quickload "dexador")` fetched `dexador-20260101-git` (0.9.15) into the
quicklisp cache; the system was copied to a scratch tree, patched form by form
until it loaded, and driven against a local echo server. The patch list IS the
gap list below. Deps were probed one by one first: **fast-http, quri, fast-io,
babel, trivial-gray-streams, trivial-garbage, chunga, cl-ppcre, cl-cookie,
trivial-mimes, chipz, cl-base64, usocket, bordeaux-threads, alexandria all
load today, unpatched**. Only `cl+ssl` does not (it is CFFI-based).

## The baseline the spike reached

With the workarounds applied, dexador RUNS on **three of the four backends** --
interpreter, JVM (`-o X.class`), and WASM `--component`:

| case | result |
| --- | --- |
| `dex:get` | OK |
| `:headers` on the request | OK |
| UTF-8 body | OK |
| gzip (`Content-Encoding`) via chipz | OK |
| 302 redirect follow | OK |
| 404 -> `dex:http-request-failed` | OK (signals) |
| `dex:post` string / alist / JSON | OK |
| `:cookie-jar` + `Set-Cookie` | OK |
| `:want-stream t` | BROKEN when measured -- `.todo/400` is done since, re-probe |
| `https://` | OK since 2026-08-16 (`.todo/399` done) -- via the cl+ssl shim over `rontolisp:tls-upgrade` |
| 2nd..5th return values (status, headers, uri) | BROKEN -- `.todo/397` |

WASM Preview 1 does not compile at all (`.todo/405`).

Operational note for the component run: trivial-mimes reads `/etc/mime.types`
at LOAD time and signals `No MIME.TYPES file found anywhere!` without it, so
the component needs `--dir /etc` (`wasmtime run -W gc=y -W exceptions=y
-S inherit-network=y -S allow-ip-name-lookup=y --dir /etc`). Decide when
closing this parent whether that is documented or whether trivial-mimes gets a
built-in fallback table.

## The children

Blockers, in the order that unblocks the most:

1. `.todo/397` -- `unwind-protect` cleanup clobbers the protected form's
   secondary values, on ALL FOUR backends. This is the one that makes
   `(dex:get url)` answer only the body: dexador returns
   `(values body status headers uri stream)` through an `unwind-protect` whose
   cleanup ends in `(values)`. Independent of dexador, and the most serious
   finding of the spike.
2. ~~`.todo/398` -- the babel/babel-encodings shim needs the decoding-MAPPING
   protocol~~ **DONE (2026-08-16)**: `lookup-mapping` /
   `code-point-counter` / `octet-counter` / `decoder` / `encoder` over
   `babel:*string-vector-mappings*`, `unicode-char`,
   `enc-max-units-per-char`, `get-character-encoding`, the
   `character-coding-error` hierarchy and `*suppress-character-coding-errors*`,
   with `string-to-octets`/`octets-to-string` re-expressed as drivers over the
   mapping layer (`.kb/asdf.md`, `BabelMappingE2eTest`).
   `src/decoding-stream.lisp` now loads VERBATIM.
3. ~~`.todo/401` -- ASDF: `:defsystem-depends-on` (dexador.asd's first line),
   `asdf:component-version` (dexador's User-Agent), and
   `asdf:system-relative-pathname` on the compile paths (trivial-mimes).~~
   **DONE (2026-08-16)**: the option is parsed and both loaders resolve it
   ahead of `:depends-on`, a built-in dependency ANNOUNCES its features to the
   system that names it (the new `trivial-features` shim declares `:unix` and
   `:little-endian`), and `asdf:component-version` reads back a plain-string
   `:version` on every backend (`.kb/asdf.md`, `AsdfMetaobjectsE2eTest`).
   `system-relative-pathname` needed nothing -- it has worked on all four
   backends since todo-374 and is now pinned. `dexador.asd` itself now parses;
   `(ql:quickload "dexador")` reaches cl+ssl -> cffi, i.e. `.todo/399`.
4. `.todo/402` -- the CL leftovers: `file-namestring`, the `nstring-*` case
   family (chunga), and the environment-enquiry family.
5. ~~`.todo/404` -- `uiop:symbol-call` has no compiler case, so dexador's
   backend dispatch cannot be compiled.~~ **DONE (2026-08-16)**: two halves.
   The operator now carries uiop's own Lisp definition beside the call-position
   fold, so `#'uiop:symbol-call` is a value (`.kb/uiop.md`); and the
   dispatch gate probes the uninterned `#:member` designator spelling
   (`compiler.DesignatorSpellings`, shared by both backends), without which
   `'#:request` compiled and then died undefined at run time. Dexador's
   `ecase` over `*dexador-backend*` compiles and runs on all four backends,
   unreachable `:winhttp` arm and all (`ci-spec.yaml`
   `uiop-symbol-call-as-a-value`).
6. `.todo/403` -- a run-time `(export 'pkg::name pkg)` does not publish a
   function under the `pkg:name` spelling. Not on dexador's own path (found
   while building the spike shims), but it breaks the common
   `defun`-then-`export` idiom.

Then the feature work:

7. ~~`.todo/399` -- `cl+ssl` shim over a new TLS-over-an-existing-stream
   primitive.~~ **DONE (2026-08-16)**: `rontolisp:tls-upgrade` (stream host
   [:insecure v]) upgrades an already-connected handle on interpreter + JVM
   (compile error on both WASM targets, like the rest of the TLS family), and
   the `cl+ssl` built-in shim system rides it -- `make-ssl-client-stream`
   (`:verify` defaulting from the `with-global-context`-carried mode, so
   `dex:*not-verify-ssl*`/`:insecure` reaches the primitive), signals on
   client certs / `:verify-location` (`.kb/tcp-sockets.md`). dexador's exact
   `(:import-from :cl+ssl ...)` list resolves; `(ql:quickload "dexador")` now
   loads past cl+ssl and stops at `usocket:socket-option` (`.todo/114`).
8. ~~`.todo/400` -- the Gray INPUT protocol is defined but no built-in
   dispatches to it, so `:want-stream t` is unusable (and fails DIFFERENTLY on
   each backend, which is its own reason to fix it).~~ **DONE (2026-08-16)**:
   `peek-char` / `unread-char` / `read-char-no-hang` / `open-stream-p` /
   `stream-element-type` now dispatch beside the read family that already did,
   `stream-read-char` is the ONE method a character input stream must define
   (everything else has a default over it, including a one-slot pushback behind
   `stream-unread-char`), and the failure when a class supplies no reader is the
   same on all four backends (`.kb/gray-streams.md`, ci-spec
   `gray-stream-input-protocol-widening`). `:want-stream t` itself is worth
   re-probing against the spike tree: the spike's row above predates this, and
   dexador's `decoding-stream` defines exactly the three ownable operators and
   `stream-unread-char`. Left behind: `.todo/411`.
9. `.todo/405` -- WASM Preview 1 has no non-blocking input probe, so `listen`
   is a compile error and dexador cannot target it.

`usocket:socket-option` (dexador sets `:receive-timeout` with it) is a usocket
shim gap and is tracked in `.todo/114`, not here.

## The alternative considered and DEFERRED: a shim over `rontolisp:fetch`

Instead of loading dexador's sources, `dex:get`/`dex:post` could be a shim
system implemented over `rontolisp:fetch` -- the `usocket.lisp` /
`clack-handler-rontolisp.lisp` pattern. **Decision (2026-08-16): not now.
Reach the UNPATCHED load first, then re-evaluate with that in hand.**

Why it is not the default route:

- `.todo/147` (design line, 2026-07-18) puts the boundary at usocket-CLASS
  libraries -- per-implementation portability layers that cannot support
  rontolisp from their side. dexador is ordinary portable CL, and the spike
  proved it: every dependency but cl+ssl loads unpatched. **cl+ssl is the one
  that falls on the shim side of that line**, which is what `.todo/399`
  already is.
- `fetch` delegates redirect following, `Content-Encoding` decompression,
  chunked decoding and header normalization to THREE different host clients
  (JDK `HttpClient`, `wasi:http`, the reactor host's own `fetch`). A
  dex-shaped API on top of that imports host policy divergence into a contract
  that is precise about `:max-redirects`, cookie-jar merging and the body's
  encoding. Real dexador does all of it in Lisp: the spike got byte-identical
  gzip / 302 / cookie / UTF-8 answers on interpreter, JVM and `--component`.
- It is not a thin shim. `:want-stream`, the connection pool, the
  `retry-request` / `ignore-and-continue` restarts, the per-status condition
  hierarchy, multipart bodies and `:proxy` would all be reimplemented, and
  every divergence from upstream is silent.

What it WOULD buy, for the re-evaluation:

- `https://` for free -- `.todo/399` (High) disappears on this path, as do
  `.todo/398`, `.todo/401`, `.todo/404` and chunga's half of `.todo/402`.
- The targets real dexador can NEVER reach: a `--no-wasi --host-fetch` reactor
  (Cloudflare Worker) and the browser playground have no TCP sockets at all.
- It does NOT fix `.todo/397` (a language bug any program can hit) and does
  NOT fix `.todo/405` (`fetch` is a compile error on plain Preview 1 too).
- Artifact size is a WEAK argument either way: `LibraryDefunPruner` has
  covered ASDF-spliced third-party trees since 2026-08-09 (`.todo/147`'s "an
  ASDF-spliced third-party tree is not tree-shaken" predates that), and the
  spike's 5.6 MB `.class` / 4.5 MB `.wasm` were measured WITHOUT `--optimize`.
  Measure before arguing from it.

If it is ever adopted, it must NOT claim the `dexador` name: an opt-in system
of its own, refusing to load beside the real one -- the `tiny-routes/lite`
precedent (`ShimLibraries.CONFLICTS`).

## Definition of done

`(ql:quickload "dexador")` loads the UNPATCHED upstream system, and a program
doing `(dex:get url)` / `(dex:post url :content ...)` -- reading the status and
the header table from the secondary values -- runs on all four backends over
`http://` and, on interpreter and JVM, over `https://`. Then: an
`examples/net/` program, `ci-spec.yaml` coverage, a `doc/{en,ja}` page under
the library guides, and a `.kb/` file for whatever invariant the cl+ssl shim
ends up owning.

## Non-goal

winhttp (`#+windows`), and dexador's `:proxy`/SOCKS5 paths. Neither was
exercised; re-scope if a consumer appears.
