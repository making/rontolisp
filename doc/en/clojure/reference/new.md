# new

`(new Class args...)`

Constructs a host object; the `(Class. args)` suffix spelling is the same operation. The
instance answers to every other interop verb. Runs on the interpreter and the JVM only --
the wasm backends reject `java:`. Three constructions build a stream instead, on every
backend: a zero-argument `java.io.StringWriter` (see [with-out-str](with-out-str.md)), and
a `java.io.PushbackReader` or `java.io.BufferedReader` over a stream (a
`clojure.java.io/reader`, `*in*`), which is that stream, or over a `java.io.StringReader`
construction, which is a string reader -- what [read](read.md) reads.

```clojure
(println (.length (new String "hi"))) ; 2
```
