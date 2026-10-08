# Ring utilities (ring.util, ring.middleware)

The commonly used pure-function part of [Ring](https://github.com/ring-clojure/ring)'s
`ring-core` (1.15.5) and `ring-codec` (1.3.0) is built in: require it like any library, and
the same source runs on every backend. Each namespace is Clojure source shipped with
rontolisp and loaded like a project file, so its vars behave like your own (`:refer :all`,
`#'`, function values); a file of the same name on your source path takes precedence, as a
source directory precedes a dependency on the classpath. A handler built from them is served
by the [Ring adapter](ring.md).

| Namespace | Vars |
|---|---|
| `ring.util.response` | `response` `status` `header` `content-type` `charset` `redirect` `redirect-status-codes` `redirect-after-post` `created` `not-found` `bad-request` `find-header` `get-header` `update-header` `get-charset` `set-cookie` `response?` |
| `ring.util.request` | `request-url` `content-type` `content-length` `character-encoding` `urlencoded-form?` `body-string` `path-info` `in-context?` `set-context` |
| `ring.util.codec` | `url-encode` `url-decode` `percent-encode` `percent-decode` `form-encode` `form-decode` `form-decode-str` `form-decode-map` `assoc-conj` |
| `ring.util.mime-type` | `default-mime-types` `ext-mime-type` |
| `ring.middleware.params` | `wrap-params` `params-request` `assoc-query-params` `assoc-form-params` |
| `ring.middleware.keyword-params` | `wrap-keyword-params` `keyword-params-request` |
| `ring.middleware.content-type` | `wrap-content-type` `content-type-response` |

```clojure
(require '[ring.util.response :as response] '[ring.util.codec :as codec])
(:headers (response/redirect "/login" :see-other))
; => {"Location" "/login"}
(response/get-charset (response/content-type (response/response "") "text/html; charset=utf-8"))
; => "utf-8"
(codec/url-decode "caf%C3%A9")
; => "café"
(codec/form-encode {"q" "a b"})
; => "q=a+b"
(get (codec/form-decode "a=1&a=2&b=x+y") "a")
; => ["1" "2"]
```

The middleware wrap a handler as in Ring: `wrap-params` adds `:query-params`, `:form-params`
(from a `application/x-www-form-urlencoded` body) and `:params`, and `wrap-keyword-params`
turns the `:params` keys that read as keywords into keywords.

```clojure
(ns app
  (:require [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :as response]))

(defn greet [{:keys [params]}]
  (response/response (str "Hello, " (:name params "stranger") "!")))

(def handler (-> greet wrap-keyword-params wrap-params))

(println (:body (handler {:request-method :get :uri "/greet"
                          :query-string "name=J%C3%BCrgen" :headers {}})))
```

```
Hello, Jürgen!
```

## Not built in

These need a host the WebAssembly backends do not have, and are refused by name when a
program names them:

- `ring.util.response/file-response`, `url-response`, `resource-response` and
  `resource-data` (a `java.io.File`, a URL, a class-loader resource);
  `ring.util.codec/base64-encode` and `base64-decode` (byte arrays).
- The namespaces `ring.middleware.cookies`, `session`, `flash`, `multipart-params`,
  `nested-params`, `not-modified`, `file`, `file-info`, `resource`, `head` and
  `content-length`, `ring.util.io`, `time`, `parsing`, `test` and `async`, and
  `ring.websocket`. `ring.adapter.jetty` is refused with a pointer to
  `ring.adapter.rontolisp/run-server`.

## Differences

- A charset is named by a string: `UTF-8`, `ISO-8859-1` or `US-ASCII`, or one of the
  JDK's aliases for them, in any case; any other name signals an `IllegalArgumentException`
  whose message is the name. The default is UTF-8, and malformed input decodes as the JDK
  replaces it.
- `body-string` is a function of `nil`, a string, a seq or a stream, not a multimethod a
  program extends.
- `wrap-params` reads a form body with `slurp`; the `:encoding` option (or the request's
  charset) governs the percent-decoding, as in Ring.
- `content-length` reads ASCII digits only (Java's `Long/valueOf` also takes other
  scripts' digits).
