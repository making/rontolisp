# URL / query-string library (`rontolisp:url-*`, `rontolisp:query-param*`)

`rontolisp`-package functions (not CL standard): `url-decode`, `url-encode`,
`query-params`, `query-param`, `url-path`, `url-query`. User-facing behavior
lives in `doc/*/reference/functions/rontolisp-{url-decode,url-encode,
query-params,query-param,url-path,url-query}.md`. The policy layer over the
raw `:query` string that `rontolisp:http-handler` puts in the request plist
(`.kb/fetch-http.md`), and equally useful for building `fetch` URLs.

**Single Lisp-source implementation** (the json.lisp / linalg.lisp pattern):
`src/main/resources/am/ik/rontolisp/eval/url.lisp` -- external single-colon
public defuns + internal `rontolisp::%url-` helpers, written in
already-canonical package shape (`PackageResolverTest.
urlLibraryFormsAreAResolverFixedPoint` pins that resolving it is a no-op).
Every public entry point is a plain fixed-arity defun, so like
`LinalgLibrary` (and unlike `JsonLibrary`) there is NO call-site rewriting:

- **Interpreter** -- `LispEvaluator.resolveFunction` lazily evaluates
  `UrlLibrary.forms()` into the global environment on the first resolution of
  a public name (`UrlLibrary.isUrlFunction`: rontolisp-qualified + member in
  the exported set).
- **Compile path** -- `UrlLibrary.process(program)` runs in
  `RontoLispCli.compileToFile` and the web playground (outermost of the three
  library pre-passes; compiler unit tests call it explicitly): any qualified
  spelling anywhere (quoted mentions and `#'` count), or a bare exported name
  under `(in-package rontolisp)`, prepends the library defuns.

**Names registered in**: `LispNames` (URL_DECODE etc.), the `rontolisp`
package set in `PackageRegistry` (externality), and
`PackageIntrospection.RONTOLISP_FUNCTION_NAMES` (sorted -- shows up in
`(rontolisp:list-functions :rontolisp)`, pinned by evaluator/JVM/WASM tests,
ci-spec and the docs). The native binary embeds `url.lisp` via
`resource-config.json` (typeReachable `UrlLibrary`).

**Portability inside url.lisp** (same rules as json.lisp, `.kb/json.md`):
only ASCII structural characters are examined via `char-code`; a `%XX` run is
collected into a byte list first so multi-byte UTF-8 sequences reassemble;
`(= (length "あ") 3)` detects byte-indexed (WASM) vs UTF-16 backends --
decoded bytes are emitted directly on WASM and UTF-8-decoded to UTF-16 units
(surrogate pairs combined) elsewhere; `url-encode` does the inverse (encodes
UTF-16 surrogate pairs back to 4-byte UTF-8 before percent-encoding;
uppercase hex, space -> `%20`, RFC 3986 unreserved pass through).
`url-decode` also maps `+` -> space (query-string convention). Strictness:
invalid hex, truncated escapes and invalid UTF-8 byte sequences signal
errors; lone surrogates signal in `url-encode`.

**Semantics worth remembering**: `query-params` returns an ALIST of
`(key . value)` strings (duplicates preserved in order, bare `flag` ->
`("flag" . "")`, empty segments skipped, nil/"" -> nil); `query-param`
returns the first decoded match or nil, nil-safe in the query argument;
`url-query` returns nil when there is no `?` but `""` for a bare trailing
`?` (matching the `:query` plist key); splitting is at the FIRST `?`.

**Tests**: `LispEvaluatorTest` (url*/queryParam* cases),
`JvmLispCompilerTest.compileAndRunUrl*` (+ cl-user introspection
non-pollution), `WasmLispCompilerTest.urlOpsCompileInEveryMode`,
`WasmLispCompilerIntegrationTest.urlOpsWorkInPreview1Mode`, ci-spec case
`url-and-query-string-library` (multi-byte `%E3%81%82` and `%F0%9F%98%80`
cross-backend), DocExamplesTest via the reference pages.
