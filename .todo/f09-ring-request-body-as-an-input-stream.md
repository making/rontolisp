# f09. Ring: the request :body is an InputStream of the request's octets

Difficulty: Medium

Under ring-jetty-adapter 1.15.3 a request's `:body` is an `InputStream` (Jetty's `HttpInput`):
`.read` answers octets, and a request without a body has an empty one. Here
(`.kb/clojure-frontend.md`, "Ring") it is the `:buffered` character stream, nil without a body.

Measured 2026-10-09, a handler answering `[(class body) (instance? java.io.InputStream body)
<a .read loop>]`, a POST of `ff fe 41 c3 a9`, then a GET:

| | Oracle | Interpreter |
|---|---|---|
| POST | `[org.eclipse.jetty.ee9.nested.HttpInput true [255 254 65 195 169]]` | `[:java.io.BufferedReader false [2089027 169]]` (`f08`) |
| GET | `[org.eclipse.jetty.ee9.nested.HttpInput true []]` | `NullPointerException: read of nil` |

So a binary upload reads as characters, and a Ring proxy sending the request on with
`rontolisp.http-client` (`{:body (:body req)}`) sends its text, not its octets.

## What decides the design

- The body as a `clojure.java.io` byte stream (`(:C%INPUT-STREAM ...)`, as a reply's
  `:as :stream` body is) over the transport's octets: `.read`, `slurp`, `io/reader`,
  `line-seq` and `io/copy` then take it through the io kernels, and `%clojure-http-body` sends
  its octets. What a Clack-level middleware reading `:raw-body` keeps (the same octets, the
  bivalent stream underneath).
- `(class body)` printing `:java.io.BufferedReader` (a keyword) is its own oddity; the oracle's
  class name is Jetty's, so decide what `class` answers.
- Once no Ring body is a character stream, `%clojure-http-body`'s reader arms (a
  `clojure.java.io/reader`, a CL stream: sent as their text) can become the oracle's ex-info
  (`Don't know how to convert class java.io.BufferedReaderto body`).

## Plan

1. `ClojureRingAdapterTest` `/echo` and a `ServeRingComponentE2eTest` route with a binary body
   and a bodiless request, on the four transports.
2. The body kind, then each consumer.
3. `doc/*/clojure/reference/ring.md` and `run-server.md`, the request map's `:body`.
