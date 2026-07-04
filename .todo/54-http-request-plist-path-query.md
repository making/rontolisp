# http-handler request plist: split `:path`, add `:query` (breaking change)

**Decision (2026-07-04):** backward compatibility is explicitly NOT a goal --
the owner wants the ideal API shape now, while the only consumers are this
repo's examples/docs/ci-spec. Do not add compatibility keys or transition
aliases.

## Goal

Today the request plist's `:path` is wasi:http's `path-with-query` leaking
through: a request to `/get?a=1` gives `:path "/get?a=1"`, so every handler
starts by hand-writing `path-only`/`query-of` string splitting (see
`examples/httpbin.lisp`, `examples/wasmcloud/*`). Mainstream server APIs
(WSGI/Rack `PATH_INFO`+`QUERY_STRING`, Servlet, Go `r.URL.Path`/`RawQuery`)
split at the interface. Change the plist to:

- `:path` -- the path only, query stripped (`"/get"`)
- `:query` -- the raw query string without the `?` (`"a=1"`), or `nil` when
  the request has none (raw string, NOT parsed: decoding/param-map policy
  belongs to the URL library, `.todo/55-url-query-library.md`)

`:method` / `:headers` / `:body` are unchanged. Percent-decoding is NOT done
here (raw values pass through, as today).

## Where the request plist is built (all three must change)

1. **Interpreter**: `HttpHandlerSupport.java` -- the `Request` record
   (`method`/`path`/`headers`/`body`, ~line 54) and the exchange-to-Request
   conversion (~line 155-164). Split there (first `?`; everything after it,
   `""` query -> nil at the Lisp layer or keep `null`). The plist itself is
   assembled where `LispEvaluator` registers the handler bridge -- grep for
   the `:path` keyword construction.
2. **JVM**: `JvmHttpHandlerRuntimeBuilder` -- the injected `handle()`
   marshals `HttpHandlerSupport.Request` into the plist through `_invoke_1`.
   Since interpreter and JVM share `HttpHandlerSupport.Request`, consider
   splitting inside the record/factory once (e.g. `Request.path()` returns
   split path + a new `query()` accessor) so both backends inherit it.
3. **WASM component**: `HttpHandlerInliner` (cli pre-pass) synthesizes the
   `%http-dispatch` wrapper in Lisp; the serve adapter passes
   path-with-query as one string. Split inside the generated Lisp wrapper
   (a synthesized `%`-helper defun) before building the request plist --
   the adapter and core ABI stay untouched.

## Consumers to update (grep for `:path` + `path-only`/`query-of`)

- `examples/httpbin.lisp` -- drop `path-only`/`query-of`, use `:query`
  (keep `parse-args` until `.todo/55` lands, then switch).
- `examples/magic-8-ball.lisp`, `examples/linalg-api.lisp`,
  `examples/http-handler.lisp`, `examples/dog-fetcher.lisp` -- most compare
  `(getf request :path)` to literals; after the split the comparisons become
  exact (that is the point). Remove any local `path-only` helpers.
- `examples/wasmcloud/` -- `http-hello-world`, `http-handler`,
  `http-kv-handler`, `service-tcp/http-api.lisp` all carry copies of
  `path-only`/`query-of`/`query-param`; delete the helpers, read `:query`.
- Docs: `doc/en/guides/http-handler.md` and
  `doc/en/reference/functions/rontolisp-http-handler.md` document the plist
  keys; update, and mirror byte-identically to `doc/ja/**` in the same
  commit (CLAUDE.md "Updating the Documentation Site"). Run the
  `-Drontolisp.doc.fix=true` DocExamplesTest helper afterwards.
- `src/test/resources/ci-spec.yaml` + Java tests: grep for `http-handler`
  cases across `LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest` and any `HttpHandlerSupport` tests.

## Verification

Serve programs are "verified" on four targets: interpreter, JVM class
(rontolisp jar on the classpath), `wasmtime serve -W gc=y` (component), and
ideally `wash dev` (wasmCloud; each `examples/wasmcloud/*/.wash/config.yaml`
already builds via the native `rontolisp` binary on PATH). Exercise a path
WITH a query (`curl '.../get?a=1&b=two'`) and one WITHOUT (`:query` must be
nil) on every target. Then the standard tail: `./mvnw spring-javaformat:apply
test`, native-image build + `CiSpecE2eTest` (CLAUDE.md "Verifying the Native
Image End-to-End"), `DocExamplesTest`.

Related: `.todo/55-url-query-library.md` (the parsing layer on top of
`:query`), `.todo/51-wasi-http-incoming-handler-spin.md` (original serve
design).
