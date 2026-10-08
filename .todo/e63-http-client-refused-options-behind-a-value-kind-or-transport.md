# e63. rontolisp.http-client: the refused options behind a value kind or a transport gap

Difficulty: High

`rontolisp.http-client` (`.kb/clojure-frontend.md`, "HTTP client") refuses by name what
babashka.http-client 0.4.23 does through a value kind or a transport feature rontolisp lacks.
Each item is independent; each changes `clojure-http-spec.yaml` on the four transports
together (`FetchSpecE2eTest#clojureHttpClient`).

1. `:as :bytes`: no Clojure byte array exists (`bytes?` is constantly false). A byte-array
   value kind on the four backends (`byte-array`, `aget`, `alength`, `String.` over bytes,
   `slurp` of one) is a front-end design; the client is one consumer.
2. Compression: the oracle sends `accept-encoding: gzip, deflate` and inflates the reply;
   here nothing is asked for and a `gzip`/`deflate` reply is refused. Needs an inflate the
   wasm targets can run (or a transport that inflates).
3. `:multipart`: a `multipart/form-data` body from string, stream and file parts (a `:file`
   part is a `java.io.File`, a host object only on the interpreter and the JVM).
4. The `:as :stream` body is no `java.io.InputStream`: `.read` refuses it and
   `clojure.java.io/reader` drains it whole before the first read; a reader pulling fetch's
   stream chunk by chunk.
5. `:timeout` waits on `148` (fetch timeouts).
6. A wrong argument count is the front end's defn wording (`wrong number of arguments passed
   to: get`), not the oracle's `Wrong number of args (0) passed to:
   babashka.http-client/get`. Front-end wide: decide whether a defn's arity refusal takes
   the oracle's words (the count and the qualified name) for every program.
