# HTTP client (rontolisp.http-client)

`rontolisp.http-client` sends HTTP requests, with the API of
[babashka.http-client](https://github.com/babashka/http-client): `request` over an options
map, and `get`, `post`, `put`, `delete`, `head` and `patch` over a URL and the options. It is
built in: require it like `clojure.string`. Every request goes through
[`rontolisp:fetch`](../../guides/http-fetch.md), so one source runs on every transport fetch
has:

| Target | Build | Transport |
|---|---|---|
| Interpreter, JVM | `rontolisp app.clj`, `-o App.class` | the JDK's `HttpClient` |
| WASM component | `-o app.wasm --component`, run with `wasmtime run -S http=y` | `wasi:http` |
| WASM reactor | `-o app.wasm --no-wasi --host-fetch` | the host's `fetch` (a Cloudflare Worker, node) |
| Native executable | `--native` | the executable's own client |

A plain Preview 1 module, and a `--no-wasi` one without `--host-fetch`, has no transport: the
compile refuses the program, naming the flags that give it one.

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [r (http/get "https://httpbin.ik.am/get" {:headers {"accept" "application/json"}})]
  (println (:status r) (get-in r [:headers "content-type"])))
```

```
200 application/json
```

## Options

| Option | Meaning |
|---|---|
| `:uri` (or `:url`) | the URL, a string: an absolute `http` or `https` URL |
| `:method` (or `:request-method`) | `:get` (the default), `:head`, `:post`, `:put`, `:delete`, `:options` or `:patch` |
| `:headers` | a map of names (strings or keywords) to a string, or to a seq of strings sent as one field each |
| `:query-params` | a map joined to the URL's query, URL-encoded; a collection value repeats its key |
| `:form-params` | a map sent as an `application/x-www-form-urlencoded` body |
| `:body` | a string; a `java.io.File` or an input stream (a `clojure.java.io` stream, a reply's `:as :stream` body), sent as its octets; a reader, read to its end |
| `:multipart` | a seq of parts, sent as a `multipart/form-data` body in place of `:body` and `:form-params` |
| `:basic-auth` | `[user pass]` or `{:user ... :pass ...}`: an `Authorization: Basic` header |
| `:oauth-token` | an `Authorization: Bearer` header |
| `:accept` | `:json`: `Accept: application/json` |
| `:as` | `:string` (the default: the body decoded as UTF-8) or `:stream` (the body unread) |
| `:decompress-body` | `false` leaves a compressed body as it arrived |
| `:throw` | `false` answers every status |
| `:async` | `true` answers a future of the response |
| `:async-then`, `:async-catch` | with `:async`: a function of the response, and of a map of the failure |

A request sends `Accept: */*` and `Accept-Encoding: gzip, deflate` unless `:headers` names
them, plus fetch's own `User-Agent`. Any other `:body` throws an `ex-info`.

## Multipart bodies

A `:multipart` part is a map of `:name` (or `:part-name`), `:content` (a string, a
`java.io.File` or an input stream) and, optionally, `:file-name` and `:content-type`. The
body is babashka.http-client's, octet for octet: each part carries `Content-Disposition`
(with a `filename` for a `File` or a `:file-name`), `Content-Type` (a string's is
`text/plain; charset=UTF-8`, a `File`'s comes from its extension, anything else's is
`application/octet-stream`) and `Content-Transfer-Encoding`. The boundary is
`babashka_http_client_Boundary` and a random UUID, and the request's `content-type` names
it, replacing any `:headers` gave. On a `--no-wasi` reactor without `--host-random` the UUID
comes from the generator `random` draws from, which the emitted glue seeds.

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [r (http/post "https://httpbin.ik.am/post"
                   {:multipart [{:name "title" :content "hello"}
                                {:name "note" :file-name "note.txt" :content "a note"}]})]
  (println (:status r) (subs (get-in r [:request :headers "content-type"]) 0 30)))
```

```
200 multipart/form-data; boundary=
```

A `:content` that opens as no input stream throws `IllegalArgumentException`
(`Cannot open <42> as an InputStream.`), and a `File` that is not there
`java.io.FileNotFoundException`.

## The response

A map of `:status`, `:headers` (lower-case names; a field the reply repeats is a vector of
its values), `:body`, `:uri` (the URL answered, after redirects) and `:request` (the
options as sent). A redirect (301, 302, 303, 307, 308) is followed like
babashka.http-client's default client: at most four hops, never from `https` to `http`, a
303 (and a `POST`'s 301 or 302) as a `GET` without the body, and a hop to another origin
without the request's `Authorization`, `Cookie`, `Origin`, `Referer` and `Host`.

A status outside 200-207, 300-304 and 307 throws an `ex-info`,
`Exceptional status code: N`, whose data is the response, unless `:throw false`:

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(println (try (http/get "https://httpbin.ik.am/status/404")
              (catch clojure.lang.ExceptionInfo e [(ex-message e) (:status (ex-data e))])))
(println (:status (http/get "https://httpbin.ik.am/status/404" {:throw false})))
```

```
[Exceptional status code: 404 404]
404
```

A transport failure (no connection, a broken transfer) throws `java.io.IOException`
carrying the transport's message; a URL the JDK refuses, `IllegalArgumentException` at the
call (`Illegal character in path at index 24: ...`, `URI with undefined scheme`).

## Compressed replies

A reply whose `Content-Encoding` is `gzip` or `deflate` (zlib, or the raw DEFLATE some
servers send under that name) is decompressed before `:as` reads it, so `:body` is the
text, or under `:as :stream` the decompressed octets; `:headers` still names the coding. A
`HEAD` request, `:decompress-body false` and any other coding leave the body as it
arrived. A reply that is not what its coding says throws what `java.util.zip` throws:
`java.util.zip.ZipException` (`Not in GZIP format`, `Corrupt GZIP trailer`, `invalid
block type`, ...) or, for one cut short, `java.io.EOFException` (`Unexpected end of ZLIB
input stream`). A gzip header is read at the call, the rest as the body is read.

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [r (http/get "https://httpbin.ik.am/gzip")]
  (println (get-in r [:headers "content-encoding"]) (subs (:body r) 0 1)))
```

```
gzip {
```

## Asynchronous requests

`:async true` answers a future at once: `deref` (and `@`) waits for the response,
`(deref f ms timeout-val)` at most `ms` milliseconds, `future?` is `true`, `future-done?`
tells whether it has arrived, `future-cancel` answers `false` (a request cannot be
cancelled) and `future-cancelled?` is `false`. A failure throws at the `deref`, as
`java.util.concurrent.ExecutionException` whose cause is the exception.

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [f (http/get "https://httpbin.ik.am/get" {:async true})]
  (println (future? f) (:status @f) (future-done? f)))
```

```
true 200 true
```

In a Preview 1 module (`--native`, `--host-fetch`) the request completes before the call
returns, so a timed `deref` answers the response.

## A reply as a stream

With `:as :stream` the `:body` is a `java.io.InputStream` over the reply's octets, unread:
the JDK client's response stream, or the `java.util.zip.GZIPInputStream` or
`InflaterInputStream` a compressed reply is read through. `.read` answers the next octet
(`-1` at the end) as the reply arrives, `.skip`, `.available`, `.transferTo` and
`clojure.java.io/copy` (to a `File` or an output stream, octet for octet) take it, `slurp`
and `clojure.java.io/reader` read it as text (UTF-8 unless `:encoding` names another
charset), `with-open` and `.close` close it, and a Ring handler may answer it as its
response `:body`, which relays the octets unchanged as they arrive:

```clojure
(ns example
  (:require [rontolisp.http-client :as http]
            [clojure.java.io :as io]))

(let [body (:body (http/get "https://httpbin.ik.am/get" {:as :stream}))]
  (println (instance? java.io.InputStream body) (char (.read body)))
  (.close body))
```

```
true {
```

A Ring proxy:

```console
$ cat proxy.clj
(ns proxy
  (:require [ring.adapter.rontolisp :refer [run-server]]
            [rontolisp.http-client :as http]))

(defn handler [req]
  (let [r (http/get (str "https://example.com" (:uri req)) {:as :stream :throw false})]
    {:status (:status r) :body (:body r)}))

(run-server handler {:port 3000})
$ rontolisp proxy.clj -o src/worker.wasm --no-wasi --host-fetch --host-boundary=streaming --emit-js-glue
```

## Differences

- The response has no `:version`, and its `:uri` is a string (babashka.http-client: a
  `java.net.URI`).
- `:as :bytes` is refused (no value here is a byte array), and so are the byte-array
  members of the `:stream` body (`.read` into a buffer, `.readAllBytes`).
  `clojure.java.io/reader` over the `:stream` body reads it whole before its first line
  (a `.read` loop over the body itself takes each octet as it arrives); on the JVM the
  transport takes the whole reply before the response is answered.
- Refused by name: the options `:client`, `:interceptors`, `:timeout`, `:version`, `:raw`
  and `:expect-continue`; the vars `client`, `default-client-opts` and the `->` builders,
  which make a `java.net.http` client; the namespace `babashka.http-client` itself, which
  points here.
- A reader is sent as its text, where babashka.http-client refuses one. A Ring request's
  `:body` is a reader here (an input stream under Jetty), so a binary upload sent on is not
  sent octet for octet.
- A transport failure is a `java.io.IOException` (babashka.http-client: its subclass, such as
  `java.net.ConnectException`); a wrong argument count is the front end's
  `ArityException` (`wrong number of arguments passed to: get`).
- Under `--host-fetch` the host's own `fetch` follows redirects (up to 20), so `:uri` is the
  URL requested, and decompresses a reply itself, so `:headers` lack its
  `content-encoding` and `content-length`.
