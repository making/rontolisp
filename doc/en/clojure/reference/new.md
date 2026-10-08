# new

`(new Class args...)`

Constructs a host object; the `(Class. args)` suffix spelling and `(Class/new args)`
([Class/member](class-member.md)) are the same operation. The
instance answers to every other interop verb. Runs on the interpreter and the JVM only --
the wasm backends reject `java:`. Three constructions build a stream instead, on every
backend: a zero-argument `java.io.StringWriter` (see [with-out-str](with-out-str.md)), and
a `java.io.PushbackReader` or `java.io.BufferedReader` over a stream (a
`clojure.java.io/reader`, `*in*`), which is that stream, or over a `java.io.StringReader`
construction, which is a string reader -- what [read](read.md) reads. A `java.io.File` of a
path or of a parent and a child, a `FileReader`, `FileWriter`, `FileInputStream` or
`FileOutputStream` over a path or a File, an `InputStreamReader` or `OutputStreamWriter` over
a byte stream and a `BufferedInputStream`, `BufferedOutputStream` or `BufferedWriter` over a
stream (which is that stream) construct [clojure.java.io](clojure-java-io.md)'s values, on
every backend too. A throwable class that carries only a message
and a cause (`Exception`, `IllegalArgumentException`, `java.io.IOException`, ...) constructs
an exception (see [throw](throw.md)), on every backend too.

```clojure
(println (.length (new String "hi"))) ; 2
```
