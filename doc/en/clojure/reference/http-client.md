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
| `:body` | a string, or an input stream read to its end |
| `:basic-auth` | `[user pass]` or `{:user ... :pass ...}`: an `Authorization: Basic` header |
| `:oauth-token` | an `Authorization: Bearer` header |
| `:accept` | `:json`: `Accept: application/json` |
| `:as` | `:string` (the default: the body decoded as UTF-8) or `:stream` (the body unread) |
| `:throw` | `false` answers every status |
| `:async` | `true` answers a future of the response |
| `:async-then`, `:async-catch` | with `:async`: a function of the response, and of a map of the failure |

A request sends `Accept: */*` unless `:headers` names one, plus fetch's own `User-Agent`.

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

With `:as :stream` the `:body` is the reply's octets unread. `slurp` and
`clojure.java.io/reader` read it as UTF-8 text, `with-open` and `.close` close it, and a Ring
handler may answer it as its response `:body`, which relays the octets unchanged:

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
- No compression is asked for, and a `gzip` or `deflate` reply is refused; `:as :bytes` is
  refused (no value here is a byte array). The `:stream` body is no `java.io.InputStream`:
  `.read` does not take it, and `clojure.java.io/reader` reads it whole first.
- Refused by name: the options `:client`, `:interceptors`, `:timeout`, `:version`,
  `:multipart`, `:raw` and `:expect-continue`; the vars `client`, `default-client-opts`
  and the `->` builders, which make a `java.net.http` client; the namespace
  `babashka.http-client` itself, which points here.
- A transport failure is a `java.io.IOException` (babashka.http-client: its subclass, such as
  `java.net.ConnectException`); a wrong argument count is the front end's
  `ArityException` (`wrong number of arguments passed to: get`).
- Under `--host-fetch` the host's own `fetch` follows redirects (up to 20), so `:uri` is the
  URL requested.
