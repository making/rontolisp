# f15. clojure.java.io: a buffered stream over a byte stream, and marks over a file

Difficulty: Low

`clojure.java.io/input-stream` of a byte stream answers the stream itself, and a stream over a
file keeps no mark (`%clojure-io-m-mark`, `markSupported` false). The oracle wraps any
`InputStream` in a fresh `BufferedInputStream`, which marks. Measured 2026-10-09 against clj
1.12.6, on the interpreter:

| program | oracle | here |
|---|---|---|
| `(class (io/input-stream (java.io.ByteArrayInputStream. (byte-array 1))))` | `java.io.BufferedInputStream` | `:java.io.ByteArrayInputStream` |
| `(with-open [in (io/input-stream f)] (.markSupported in))` over a three-octet file | `true` | `false` |
| `.read`, `.mark`, `.read`, `.reset`, `.read` over `"abc"` | `98` | `IOException: mark/reset not supported` |

`output-stream` of a `ByteArrayOutputStream` answers it too, where the oracle wraps it in a
`BufferedOutputStream`.

## What decides the design

- A buffered wrapper over another byte stream (its class, its own close and flush reaching the
  stream under it), or the class alone where nothing else differs.
- A file mark: the position the stream had (`file-position`), restored by `reset` within the
  read limit -- a 2-argument `file-position` in `clojure.lisp` pulls the Gray dispatchers into
  a Ring program (`.kb/clojure-frontend.md`, "Byte arrays"), so measure that first.

## Plan

1. clojure-spec cases for both rows on the four backends.
2. The wrapper, then the file mark.
3. `doc/*/clojure/reference/clojure-java-io.md`'s two differences go.
