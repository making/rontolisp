# URL / query-string library (`rontolisp:url-*`, `rontolisp:query-param*`)

**Decision (2026-07-04):** implement the parsing/decoding layer as a
Lisp-source library (the json.lisp / linalg.lisp pattern), NOT by putting a
parsed map into the request plist -- the plist carries the raw `:query`
string (`.todo/54-http-request-plist-path-query.md`, which should land
first), and policy (percent-decoding, duplicate keys, `+`) lives here.
Backward compatibility is not a constraint.

## Why

Every HTTP example hand-writes `query-param`/`parse-args` with the same
omissions (no %XX decoding, no `+` -> space). `examples/httpbin.lisp`
`parse-args`, `examples/magic-8-ball.lisp`, `examples/wasmcloud/http-handler`
and `http-kv-handler` all carry copies. One shared pure-Lisp implementation
serves all three backends with zero per-backend codegen, and is equally
useful on the fetch (client) side for building URLs.

## Proposed API (names open to bikeshedding; `rontolisp` package, external)

- `(rontolisp:url-decode s)` -- percent-decoding + `+` -> space. UTF-8:
  decode %XX bytes, then bytes -> string. Check what string/char primitives
  exist per backend (`code-char` is there; multi-byte UTF-8 reassembly needs
  care -- see how json.lisp handles `\uXXXX` escapes for the precedent).
- `(rontolisp:url-encode s)` -- the inverse, for fetch URL building
  (unreserved chars pass, space -> `%20`).
- `(rontolisp:query-params q)` -- parse `"a=1&b=two&flag"` into an alist
  `(("a" . "1") ("b" . "two") ("flag" . ""))`, keys/values url-decoded,
  duplicates preserved in order. Accepts `nil` (returns `nil`) so
  `(rontolisp:query-params (getf request :query))` is safe.
  Alist (not hash table) so it prints readably and `assoc ... :test
  #'string=` works -- verified 2026-07-04: `assoc`/`rassoc` `:test` already
  work on all three backends (`.todo/06-sequence-test-key-keywords.md` was
  stale; now refreshed).
- `(rontolisp:query-param q name)` -- decoded value of the first `name`
  match, or `nil`; `nil`-safe in `q`. The common one-liner for handlers:
  `(rontolisp:query-param (getf request :query) "name")`.
- Maybe `(rontolisp:url-path s)` / `(rontolisp:url-query s)` -- splitting
  helpers still useful for fetch-side URL strings even after `.todo/54`
  removes the need on the serve side. Decide whether they earn their keep.

## Implementation pattern (follow json.lisp / linalg.lisp)

- One Lisp source file (e.g. `src/main/resources/.../url.lisp` next to
  `json.lisp`/`linalg.lisp` -- check their actual location) + a
  `UrlLibrary` Java class. Choose the loading mechanics:
  - `JsonLibrary.process` style: a cli/playground pre-pass that splices the
    defuns into compiled programs when any `rontolisp:url-*`/`query-*` name
    is referenced; compiler unit tests must call it explicitly.
  - `LinalgLibrary` style: interpreter lazy-loads on a function-lookup miss.
  The interpreter and the compile path both need coverage; json.lisp does
  both -- read `.kb/json.md` first, and register names in `LispNames` /
  `PackageRegistry.CL_SYMBOLS`-equivalent for the `rontolisp` package so
  they resolve as external symbols (`.kb/packages.md`).
- No per-backend codegen: everything is `position`/`subseq`/`char`/
  `concatenate`/`code-char` level Lisp. WASM string-content `equal`/`_hash`
  already works for runtime-built strings.

## Consumers to rewrite once it lands

`examples/httpbin.lisp` (`parse-args` -> `query-params`, keep its
hash-table JSON serialization by converting or teach json-stringify alists
-- check what `rontolisp:json-stringify` does with an alist first),
`examples/magic-8-ball.lisp`, `examples/wasmcloud/http-handler/app.lisp`,
`examples/wasmcloud/http-kv-handler/app.lisp`. Delete their local helpers.

## Docs / tests

Per-operator reference pages under `doc/en/reference/functions/` (H1 = name,
signature, one runnable ```lisp example with `; => ...`) + `_catalog.yaml`
entries, mirrored to `doc/ja/**`; run the `-Drontolisp.doc.fix=true` helper.
Add `ci-spec.yaml` cases (decoding a `%E3%81%82`-style multi-byte value is a
good cross-backend case). Standard tail per CLAUDE.md: format, `./mvnw
test`, native-image + `CiSpecE2eTest`, `DocExamplesTest`, javadoc.

Depends on: `.todo/54-http-request-plist-path-query.md` (plist `:query`).
Related: `.todo/06-sequence-test-key-keywords.md` (alist ergonomics).
