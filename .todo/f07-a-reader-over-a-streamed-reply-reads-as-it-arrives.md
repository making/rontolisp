# f07. A reader over a streamed reply reads it as it arrives

Difficulty: High

With babashka.http-client, `(io/reader (:body (http/get url {:as :stream})))` is a
`BufferedReader` over the response's `InputStream`: `.readLine` answers a line as soon as it
has arrived and `line-seq` is lazy, so a program follows a long-lived reply (server-sent events,
NDJSON, a log tail) line by line. Here (2026-10-09):

- `clojure.java.io/reader` over a byte stream (`%clojure-io-decoded-reader`) reads the rest of
  it and decodes it at once into a string stream: the first line waits for the end of the
  reply, and a reply that never ends never answers.
- `line-seq` (`%clojure-io-line-seq`) is strict: every line is read before it answers.
- On the JVM, `RontoFetch` takes the reply with `BodyHandlers.ofByteArray()`, so the future
  settles only once the whole reply is in; even a `.read` loop over the `:stream` body waits for
  the end there. The interpreter (`HttpSupport.BodyPump`), the component, the native runner and
  a `--host-boundary=streaming` reactor hand chunks over as they arrive, so a `.read` loop
  (`%clojure-io-read-byte`) already reads as the reply arrives there.

## What decides the design

- A character stream whose characters come from a pull: it decodes the chunk in hand (UTF-8
  carrying a sequence split across chunks, the JDK replacement for malformed input, other
  charsets through the existing decoder) and pulls the next chunk only when the line or
  character asked for is not complete. Either a Lisp stream kind every backend's stream runtime
  reads (`read-line`, `read-char`, `peek-char`), or a clojure.java.io reader kind whose members
  (`.readLine`, `.read`, `.ready`, `line-seq`, `slurp`) the io kernels dispatch.
- `line-seq` lazy over such a reader, as the oracle's `lazy-seq` of `.readLine`, without a cost
  on the strict file path.
- `RontoFetch` streaming the reply (a publisher queuing a packed vector a batch, like the
  interpreter's `BodyPump`), and what that costs a whole-body `read-all`.

## Plan

1. A `clojure-http-spec.yaml` case on the four legs whose origin holds the rest of a reply
   until the client has read its first line (the origin blocks on a latch the client releases
   with a second request).
2. The reader, then lazy `line-seq`, then the JVM's streaming reply.
3. `doc/*/clojure/reference/http-client.md` "Differences" loses the reader line.
