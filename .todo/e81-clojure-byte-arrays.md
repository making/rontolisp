# e81. Clojure: a byte-array value kind on the four backends

Difficulty: High

No Clojure byte array exists: `bytes?` is constantly false and arrays ignore their element
class. Every consumer refuses by name today:

- `clojure.java.io` (`.kb/clojure-frontend.md`, "clojure.java.io"): `.read` into a buffer,
  `.readAllBytes`, `.write` of an array and `.transferTo`'s callers that hand one,
  `(io/input-stream bytes)`, `(io/copy bytes out)`, `java.io.ByteArrayInputStream` /
  `ByteArrayOutputStream` (unlowered: a host object or the wasm refusal).
- `rontolisp.http-client` `:as :bytes` (e63 item 1).
- `(String. bytes "UTF-8")`, `.getBytes`, `slurp` of a byte array, ring-codec's base64
  (`ring.util.codec/base64-encode` is refused "it takes a byte array").

## What decides the design

- The representation: an `(unsigned-byte 8)` vector (what the byte streams already read
  into, `%clojure-io-read-octets`) printed and compared as the oracle's `byte[]`
  (`#object["[B" 0x...]`, identity `=`), signed on `aget` like Java's `byte`.
- Which arms it needs (`ClojureArms`): a family whose producers are `byte-array`, `bytes`,
  `.getBytes`, the `ByteArrayOutputStream` reads, so a program making none stays
  byte-identical.

## Plan

1. Measure on clj 1.12.6 the members the consumers above call and what they print.
2. The kind, its family, `bytes?`/`aget`/`alength`/`aset`, then each consumer with a
   clojure-spec case on all four backends.
3. `doc/*/clojure/reference/clojure-java-io.md` (the byte-array refusal goes), `bytes-p.md`.
