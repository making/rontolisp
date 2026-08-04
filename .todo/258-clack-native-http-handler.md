# Re-implement `rontolisp:http-handler` as the Clack protocol (breaking change)

Difficulty: High

`rontolisp:http-handler` was implemented before Clack support landed, so running a
Clack application costs a per-request double conversion: rontolisp request plist ->
clack env -> app -> clack response list -> rontolisp response plist. Make the value a
handler RECEIVES the Clack environment plist and the value it RETURNS the Clack
response list, so a Clack application IS a rontolisp handler and
`clack.handler.rontolisp` converts nothing per request.

Status: partially implemented, uncommitted in the working tree (see "What is already
done"). The interpreter and the WASM component legs work and are measured; the JVM
backend, the tests, the examples and the docs are untouched, so `./mvnw test` fails on
the JVM/WASM http-handler tests until they are updated.

## Decisions already made (2026-08-04, by the author)

1. **Request body**: the raw `http-handler` keeps rontolisp's ASYNCHRONOUS stream as
   the default; only the Clack path buffers. So the environment's SHAPE is always the
   Clack environment (15 keys) and only what `:raw-body` holds changes with the mode.
   The directive takes `:raw-body :stream` (default) / `:buffered`, and
   `rontolisp::%http-server-start` takes the same keyword. The clack shim passes
   `:buffered`.
2. **A bare string response body is an ERROR** (upstream-faithful). The reason bites
   rontolisp harder than upstream: `lack/app/file` answers a PATHNAME body and a
   rontolisp pathname IS its namestring, so accepting a string would make the
   `:static` middleware serve a file's PATH as its contents, silently, with a 200.
3. **Function responses: the DELAYED form only.** `(funcall body responder)` where the
   responder receives the 3-element response. The streaming WRITER form (a 2-element
   response, responder answers a writer closure) is refused loudly by the closure the
   responder returns.
4. **The operator keeps the name `rontolisp:http-handler`.**
5. **Hard break, no transition window.** `rontolisp:fetch` keeps its
   `(:status :headers :body)` plist unchanged -- it is the client side, a different
   thing.

## How big the breaking change is

Every handler's source changes.

```lisp
;; before
(defun handle (req)
  (list :status 200
        :headers '(("content-type" . "text/plain"))
        :body (format nil "~a ~a" (getf req :method) (getf req :path))))

;; after
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "~a ~a" (getf env :request-method) (getf env :path-info)))))
```

| | before | after |
|---|---|---|
| method | `:method`, the string `"GET"` | `:request-method`, the KEYWORD `:GET` |
| path | `:path` (raw) | `:path-info` (percent-DECODED) |
| query | `:query` | `:query-string` |
| headers (in) | `:headers`, dotted alist | `:headers`, an EQUAL HASH TABLE (lowercased names, repeats joined with `", "`) |
| body (in) | `:body` | `:raw-body` (still the async stream by default) |
| response | plist `(:status :headers :body)` | POSITIONAL list `(status headers [body])` |
| headers (out) | dotted alist | keyword plist (a dotted alist is still accepted) |
| body (out) | a string was fine | a LIST of strings; a bare string is refused |

Blast radius in this repo: 23 files under `examples/` mention the directive (about 12
are `.lisp` programs, the rest are READMEs / `spin.toml` / `examples.yaml`), plus every
http-handler fence in `doc/en/**` and `doc/ja/**`, plus the test files listed below.

**Migration hazard worth calling out in the release note**: the RESPONSE side fails
loudly (a non-integer car signals), but the REQUEST side fails SILENTLY -- `(getf env
:method)` simply returns nil. A half-migrated handler compiles and serves nils.

## Performance baseline (develop 3bb8dd34, Bench.java, closed loop)

Interpreter / JVM: 8 connections, 10s. WASM: 4 connections, 10s, wasmtime 47.0.2.

| backend | case | rps | mean |
|---|---|---|---|
| interpreter | native http-handler | 18880.2 | 0.42ms |
| interpreter | clack GET | 7831.7 | 1.02ms |
| interpreter | clack POST (read-line body) | 6227.6 | 1.28ms |
| JVM | native http-handler | 21781.1 | 0.37ms |
| JVM | clack GET | 13170.2 | 0.61ms |
| wasm component | native | 4018.4 | 0.99ms (module 641392 B) |
| wasm component | clack | 3617.9 | 1.10ms (module 1717524 B) |

Clack overhead over native: interpreter 2.41x, JVM 1.65x, wasm 1.11x.

## A design this work already MEASURED AND REJECTED -- do not repeat it

The first cut had every backend hand a positional raw tuple to ONE shared
`rontolisp::%http-serve-request` written in rontolisp (`http-server.lisp`), which built
the environment, ran the application and normalized the response. Measured:

| case | before | after | |
|---|---|---|---|
| interpreter native | 18880 rps / 0.42ms | 6125 rps / 1.30ms | **3.1x slower** |
| interpreter clack | 7832 rps / 1.02ms | 4958 rps / 1.61ms | **1.6x slower** |

It made the CLACK path -- the one this work exists to speed up -- slower too. Folding
the four chained `async-defun`s into one moved it only from 5762 to 6125 rps, so the
async frames were not the cost: **building the environment in INTERPRETED Lisp is**
(15-key plist, equal hash table, percent-decode, header walk, response normalize --
several hundred interpreted forms per request).

## The design to build (corrected)

**Declare the shape once; construct it in each backend's host language.** Same
structure the old `HttpPlistShape` had.

- `compiler/ClackEnv.java` -- the ordered 15-key declaration (`FIELDS`). Every backend
  loops it and supplies only the per-field value, with a `default ->` that throws, so
  adding a key fails each backend loudly until its extraction is written.
- `compiler/HttpPlistShape` -> rename to `FetchResponseShape`, delete the `request`
  record, keep the `response` record + defaults for `rontolisp:fetch` only.
- **Interpreter**: build the environment in Java (`LispEvaluator.buildClackEnv`).
- **JVM**: build it in the emitted `handle(Request)` bytecode
  (`JvmHttpHandlerRuntimeBuilder`), the same way it builds the old plist today. The
  `usesHashTables` gate (`JvmLispCompiler` ~:713) must also open for http-handler, and
  `maxStack`/`maxLocals` (`JvmHttpHandlerRuntimeBuilder` ~:416) are hand-written and
  TRUSTED by `StackMapAugmenter` -- an under-count is a VerifyError at class load, not
  a compile error.
- **WASM component**: build it in Lisp (`http.lisp`'s serve half over
  `http-server.lisp`'s `%http-make-env`). That code is compiled, not interpreted, so
  the interpreter's problem does not apply.
- **The one shared piece**: the buffered `:raw-body`. A bivalent Gray stream is a CLOS
  instance, which hand-written bytecode cannot build, so every backend calls
  `http-server.lisp`'s `%http-body-stream` -- and only when the request HAS a body (a
  GET gets `:raw-body nil` and pays nothing).
- Response normalization is likewise per backend, except the cold arms (octet vector,
  and anything added later) which may delegate to one shared Lisp function.

### The 15 environment keys (all verified against upstream)

`:REQUEST-METHOD` (upcased keyword) / `:SCRIPT-NAME` `""` / `:PATH-INFO`
(percent-decoded) / `:QUERY-STRING` (raw text after the first `?`, nil when absent) /
`:SERVER-NAME` + `:SERVER-PORT` (from the `Host` header, else the listening address) /
`:SERVER-PROTOCOL` (keyword) / `:REQUEST-URI` (the raw target verbatim) / `:URL-SCHEME`
/ `:REMOTE-ADDR` + `:REMOTE-PORT` (real on the JDK backends, nil on the component --
`wasi:http@0.3.0` exposes no peer accessor at all) / `:HEADERS` (equal table,
lowercased names, repeats joined with `", "`, never nil) / `:CONTENT-TYPE` /
`:CONTENT-LENGTH` (integer) / `:RAW-BODY`.

The plist must be freshly consed and proper on every request: lack-request `rplacd`s
its last cons to append `:cookies` / `:query-parameters` / `:body-parameters`, and the
mount / session middleware `setf getf` into it.

### The buffered `:raw-body` -- and a real bug it closes

`flexi-streams.lisp:84-127` already implements an in-memory octet input stream over
`rontolisp:fundamental-binary-input-stream` in pure Lisp, with `stream-read-byte` and a
real `stream-file-position`. The same construction gives `:raw-body`
`read-line`/`read-char` AND `read-byte`/`read-sequence`/`file-position` off one cursor.

That matters beyond ergonomics: **today's `:raw-body` is a `%make-string-input-stream`,
a CHARACTER stream that rejects `read-byte`, so on a real `clackup` the
`lack:make-request` -> `circular-streams` -> `http-body:parse` chain cannot run --
`lack:builder`, sessions, CSRF and ningle do not work over the native server.** Closing
that is part of this work.

## Measurements of the corrected design (interpreter + wasm implemented)

| case | baseline | rejected design | corrected |
|---|---|---|---|
| interpreter native | 18880 / 0.42ms | 6125 / 1.30ms | **18111 / 0.44ms** (-4%) |
| interpreter clack GET | 7832 / 1.02ms | 4958 / 1.61ms | **13688 / 0.58ms (+75%)** |
| interpreter clack POST | 6228 / 1.28ms | -- | 3961 / 2.02ms (-36%) |
| wasm native | 4018 / 0.99ms | -- | 3664 / 1.09ms (-9%), module 641392 -> 867310 B |
| wasm clack | 3618 / 1.10ms | -- | 3567 / 1.12ms (-1.4%), module 1717524 -> 1870715 B |

Interpreter native is back to parity (the -4% is the 15 keys + hash table). Clack GET
improves 75%, taking the Clack overhead from 2.41x to 1.32x.

**On the WASM component this change buys no throughput at all**, because per-request
instantiation dominates there (see `.todo/259`); its value on that backend is API
consistency plus the `read-byte` fix above. Verified working end to end, including
percent-decoding (`/a%20b` -> `/a b`) and a Clack app answering through `clackup`.

### Regression still open (1): interpreter POST, -36%

Cause identified. The buffered `:raw-body` is a CLOS Gray stream, so `read-line` falls
through to `%gray-default-read-line`, which dispatches a generic function PER CHARACTER
in interpreted Lisp (the old `%make-string-input-stream` was a Java `BufferedReader`).
An 18-byte body costs +0.74ms.

Fix: make the buffered `:raw-body` a JAVA-BACKED octet input stream handle -- add a
`byte[]` + cursor handle kind to `Environment`'s stream table and serve
`read-char`/`read-line`/`read-byte`/`read-sequence`/`file-position` from it. That gives
the interpreter and the JVM Java speed AND byte accuracy, and the lack chain works. The
WASM component keeps the Lisp Gray class (construction is native per backend, by
design). Also give the Gray class a single-pass `stream-read-line` method regardless.

### Regression still open (2): wasm module size, +35% on the native path

`http-server.lisp`'s CLOS Gray class + UTF-8 encoder + environment builder are spliced
into EVERY serve component, including ones that never ask for `:buffered`. Filter the
splice on `%serve-buffer-body-p` (in `HttpServerLibrary`) so a non-Clack component gets
neither the Gray class nor `%http-body-stream` / `%http-utf8-encode`. Effect of
`--optimize` on this is unmeasured.

## Status 2026-08-04 (second session): implementation COMPLETE, uncommitted

Everything below is in the working tree; nothing is committed. Remaining before
this todo can close: re-measure (the acceptance bar) and the required
post-task checks (full `./mvnw test` green run, `-Pweb compile`, native E2E
with the new ci-spec cases, javadoc).

What landed on top of the first session's state:

1. **Interpreter POST regression CLOSED** -- `eval/HttpRequestBodyStream` (a
   Java `byte[]`+cursor bivalent stream in the stream table, opened/closed via
   `Environment` hooks; the transport closes it per request). `read-line` /
   `read-char` / `peek-char` / `file-position` got concrete-type arms;
   `read-byte`/`listen` ride the existing `InputStream` arm.
   `HttpHandlerSupport.Request.body` is now `byte[]` (byte accuracy).
   The Gray class (kept for the compiled backends) gained a single-pass
   `stream-read-line`.
2. **WASM module-size regression CLOSED** -- `HttpServerLibrary.process(program,
   bufferBody)` filters the buffered-body half out of a default-mode program,
   and `HttpLibrary` SYNTHESIZES `%serve-request-body` per mode instead of
   branching on a runtime `%serve-buffer-body-p` flag (which is gone). Native
   serve component: 867310 -> 714598 B (the remainder is the env builder /
   normalizer, genuinely used now).
3. **JVM backend DONE, with a better design than planned**: instead of building
   the 15-key env in hand-assembled bytecode, the emitted `handle(Request)` is
   thin glue -- `eval/HttpHandlerJvmRuntime.buildEnv/toResponse` (real Java in
   the runtime value rep; a serve class needs the rontolisp jar anyway) plus a
   DIRECT INVOKESTATIC of the compiled `%http-normalize-response` (the shaker
   edge) and the compiled `%http-body-stream` Gray instance for `:buffered`.
   No `maxStack` hand-trust risk; `usesHashTables` forced by `usesHttpHandler`.
   `ClackEnv.usesBufferedBody` (moved to `compiler` so `codegen.jvm` can read
   it) freezes the mode at compile time; the directive/seam compilers accept
   and drop the keyword pair.
4. **`HttpPlistShape` -> `FetchResponseShape`** (response record only, fetch);
   request record + `%http-request-*` helpers deleted; `ClackEnvTest` +
   `FetchResponseShapeTest` pin the two shapes.
5. **Tests**: `HttpHandlerTest`/`HttpHandlerJvmTest` rewritten for the Clack
   contract (+ buffered raw-body, delayed response, bare-string refusal,
   two-element response, percent-decoding cases); every compiler-driving serve
   harness now splices `HttpServerLibrary` (+`GrayStreamsLibrary`), including
   the two shaker corpus tests; `ClackE2eTest` guards the GET probe's nil
   `:raw-body`; `LackEcosystemE2eTest` gained the `lack:builder`-over-real-
   `clackup` legs (interpreter+JVM) the bivalent body unlocks; ci-spec gained
   4 net-new four-backend cases (env shape / percent-decode / normalizer / the
   buffered body stream -- verified by hand on all four backends). The
   interpreter lazy-loads http-server.lisp on a direct `RONTOLISP::%HTTP-*`
   call (the usocket/restart resolveFunction pattern) so those cases run
   without a server. `http-server.lisp` joined resource-config.json.
6. **Examples migrated** (15 programs + README prose; both CI legs compile for
   each, http-handler.lisp verified end-to-end under wasmtime serve). Docs
   (en/ja) migrated in a parallel pass. `.kb/http-server.md` written;
   fetch-http/clack/async-await/url/reader-case-upcase/README.md updated
   (the dangling http-plist-shape reference fixed).

## Final measurements (2026-08-04, this machine, Bench.java closed loop)

Interpreter/JVM: 8 connections, 10s. WASM: 4 connections, 10s, wasmtime 47.0.2.
Run-to-run noise on the native paths is about +/-5% (repeat runs of jvm native
spanned 19320-21032 rps, interpreter native 18351-20344).

| backend | case | baseline (develop 3bb8dd34) | after the cutover |
|---|---|---|---|
| interpreter | native | 18880 rps / 0.42ms | 18351-20344 rps / 0.43ms (parity) |
| interpreter | clack GET | 7832 / 1.02ms | **13369 / 0.60ms (+71%)** |
| interpreter | clack POST (read-line body) | 6228 / 1.28ms | **12836 / 0.62ms (+106%)** |
| JVM | native | 21781 / 0.37ms | 19320-21032 / 0.39ms (parity/noise) |
| JVM | clack GET | 13170 / 0.61ms | **20255 / 0.39ms (+54%)** |
| JVM | clack POST | (not in baseline) | 18332 / 0.44ms |
| wasm component | native | 4018 / 0.99ms (641392 B) | 3884 / 1.03ms (714598 B) |
| wasm component | clack | 3618 / 1.10ms (1717524 B) | 3632 / 1.10ms (1880010 B) |

The acceptance bar holds: the Clack path improves on every backend where
throughput can improve (the wasm component is per-request-instantiation
dominated, `.todo/259` -- its win here is API consistency + the `read-byte`
fix), and the native path stays within noise of the baseline (the residual
cost is the 15-key environment + header hash table, the price of the shape).
The interpreter POST regression (was -36%) is not just closed but inverted
(+106%): the Java-backed bivalent stream beats the old string-reader path.
The wasm native module is +11% over baseline (was +35% before the splice
filter); what remains is the env builder / normalizer the path genuinely runs.

Verification: full `./mvnw test` 5390 green; `DocExamplesTest` 672 green
(docs fixed via `-Drontolisp.doc.fix=true`); `-Pweb compile` green;
`./mvnw clean javadoc:jar` zero warnings; native image built and
`CiSpecE2eTest` (1272 = 4 backends, incl. the 4 new http cases) green.

Verification hygiene note: one full-suite run produced phantom failures
because compiles ran concurrently with it AND earlier failed serve tests left
host `wasmtime serve` processes bound to their fixed ports (the integration
tests background them and never kill them). `pkill -f "wasmtime serve"` before
judging serve-test failures.
