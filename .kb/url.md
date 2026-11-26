# URL / query-string library (`rontolisp:url-*`, `rontolisp:query-param*`)

`rontolisp`-package policy layer over `rontolisp:http-handler`'s raw `:query-string`
([[http-server]]): `url-decode`, `url-encode`, `query-params`, `query-param`, `url-path`,
`url-query`.

## Mechanism
- One Lisp source `src/main/resources/am/ik/rontolisp/eval/url.lisp` (json.lisp / linalg.lisp
  pattern); public single-colon defuns + `rontolisp::%url-` helpers. Registered in `LispNames`
  and `PackageRegistry`'s `rontolisp` set; native binary embeds it via `resource-config.json`
  (typeReachable `UrlLibrary`).
- Fixed-arity defuns only, so like `LinalgLibrary` (unlike `JsonLibrary`) there is NO call-site
  rewriting. Interpreter: `LispEvaluator.resolveFunction` lazily evaluates `UrlLibrary.forms()`
  via `UrlLibrary.isUrlFunction`. Compile path: `UrlLibrary.process(program)`, OUTERMOST of the
  three library pre-passes.
- Portability rules as [[json]]: ASCII-only `char-code` checks; a `%XX` run is collected as a
  BYTE LIST first so multi-byte UTF-8 reassembles into one `(code-char cp)`
  ([[characters-code-points]]).

## Semantics
- `url-encode`: UTF-8 percent escapes, uppercase hex, space -> `%20`, RFC 3986 unreserved pass
  through. `url-decode` also maps `+` -> space; invalid hex, truncated escape and invalid UTF-8
  signal.
- `query-params` -> ALIST of string pairs, duplicates in order, bare `flag` -> `("flag" . "")`,
  empty segments skipped, nil/"" -> nil. `query-param` -> first decoded match or nil, nil-safe.
  `url-query` -> nil with no `?`, `""` for a bare trailing `?`; split at the FIRST `?`.

## Tests
`LispEvaluatorTest` (url*/queryParam*), `JvmLispCompilerTest.compileAndRunUrl*`,
`WasmLispCompilerTest.urlOpsCompileInEveryMode`,
`WasmLispCompilerIntegrationTest.urlOpsWorkInPreview1Mode`,
`PackageResolverTest.urlLibraryFormsAreAResolverFixedPoint`, ci-spec
`url-and-query-string-library`, DocExamplesTest.
