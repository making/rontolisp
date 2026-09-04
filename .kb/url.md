# URL / query-string library (`rontolisp:url-*`, `rontolisp:query-param*`)

`rontolisp`-package (not CL): `url-decode`, `url-encode`, `query-params`, `query-param`,
`url-path`, `url-query`. Policy layer over the raw `:query-string` of
`rontolisp:http-handler` (`.kb/http-server.md`). Docs:
`doc/*/reference/functions/rontolisp-<name>.md`.

## Mechanism
- Single Lisp source `src/main/resources/am/ik/rontolisp/eval/url.lisp` (json.lisp /
  linalg.lisp pattern): public single-colon defuns + `rontolisp::%url-` helpers, in canonical
  package shape (`PackageResolverTest.urlLibraryFormsAreAResolverFixedPoint`).
- All public entries are fixed-arity defuns, so like `LinalgLibrary` (unlike `JsonLibrary`)
  there is NO call-site rewriting.
- Interpreter: `LispEvaluator.resolveFunction` lazily evaluates `UrlLibrary.forms()` on first
  resolution of a public name (`UrlLibrary.isUrlFunction`).
- Compile path: `UrlLibrary.process(program)` in `RontoLispCli.compileToFile` + web
  playground, OUTERMOST of the three library pre-passes (compiler unit tests call it
  explicitly). Trigger: any qualified spelling anywhere (quoted mentions and `#'` count), or a
  bare exported name under `(in-package rontolisp)`.
- Registered in `LispNames` and `PackageRegistry`'s `rontolisp` set; native binary embeds
  url.lisp via `resource-config.json` (typeReachable `UrlLibrary`).

## Portability inside url.lisp (as `.kb/json.md`)
- Only ASCII structural chars examined via `char-code`.
- A `%XX` run is collected into a BYTE LIST first so multi-byte UTF-8 reassembles; bytes
  UTF-8-decode to code points, each emitted as one `(code-char cp)` -- no surrogate splitting
  ([[characters-code-points]]). `url-encode` emits UTF-8 percent escapes (uppercase hex,
  space -> `%20`, RFC 3986 unreserved pass through); `url-decode` also maps `+` -> space.
  Invalid hex, truncated escapes, invalid UTF-8 signal errors.

## Semantics
- `query-params` -> ALIST of string pairs; duplicates kept in order; bare `flag` ->
  `("flag" . "")`; empty segments skipped; nil/"" -> nil.
- `query-param` -> first decoded match or nil; nil-safe in the query argument.
- `url-query` -> nil with no `?`, `""` for a bare trailing `?`. Split at the FIRST `?`.

## Tests
`LispEvaluatorTest` (url*/queryParam*), `JvmLispCompilerTest.compileAndRunUrl*` (+ cl-user
non-pollution), `WasmLispCompilerTest.urlOpsCompileInEveryMode`,
`WasmLispCompilerIntegrationTest.urlOpsWorkInPreview1Mode`, ci-spec
`url-and-query-string-library`, DocExamplesTest.
