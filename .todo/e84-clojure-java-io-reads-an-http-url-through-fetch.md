# e84. clojure.java.io: read an `http:`/`https:` URL through `rontolisp:fetch`

Difficulty: High

`(slurp "https://...")`, `(io/reader url)`, `(io/input-stream url)` and `.openStream` open a
connection in the oracle; here a URL of another protocol than `file:` is refused by name
(`reading the http: URL ... is not built in`, `ClojureJavaIoTest#aUrlOfAnotherProtocolThanFileIsRefused`).
Scripts slurp a URL often; libraries mostly use an HTTP client.

## What decides the design

- `rontolisp.http-client` already fetches on every transport through `rontolisp:fetch`
  (`.kb/clojure-frontend.md`, "HTTP client"), but a program NAMING fetch is what the
  transport splices and the Preview 1 refusal read: the namespace's kernels cannot name it
  unconditionally, or every program loading `clojure.java.io` (a startup namespace) changes
  and wasm P1 refuses it.
- So the fetch must be named only by a program that may read such a URL: a literal
  `http:`/`https:` spelling in `slurp`/`as-url`/`reader` position, or an opt-in, decided
  with measurements of the splice.
- The oracle's `URLConnection` follows redirects and decodes the body by `:encoding`, not
  by the reply's charset.

## Plan

1. Measure the oracle on slurp/reader/input-stream of an http URL (redirects, status >= 400:
   `FileNotFoundException`/`IOException` with the URL).
2. The gate, the kernel over `rontolisp:fetch`, a case in `clojure-http-spec.yaml` on the
   four transports (`FetchSpecE2eTest#clojureHttpClient`).
3. `doc/*/clojure/reference/clojure-java-io.md` (the refusal bullet goes).
