# clojure.java.io

Files, URLs and the streams over them. `clojure.java.io` is loaded before the program, as in
Clojure, so `clojure.java.io/file` works without a `require`; it is Clojure source written for
rontolisp from the documented behavior of Clojure's namespace. Its values are rontolisp's own,
not host objects: a `java.io.File`, a `java.net.URL`, a `java.net.URI` and the byte streams
behave the same on the interpreter, the JVM and both WASM targets. On WASM a file needs a
`--dir` preopen covering it, like [slurp](slurp.md)'s; without one the open is the oracle's
`java.io.FileNotFoundException`.

| Var | Behavior |
|---|---|
| `file` | `(file arg)` / `(file parent child & more)`: the `java.io.File` a path, a File or a `file:` URL names (`nil` for `nil`); each further argument a relative path below the one before |
| `as-file`, `as-url` | The `Coercions` protocol: what a value stands for as a File or as a URL; `nil` answers `nil` |
| `as-relative-path` | `(as-relative-path x)`: the path of a relative File or string; an absolute one is an `IllegalArgumentException` |
| `reader`, `writer` | `(reader x & opts)`: a buffered character stream over a path, a File, a URL, a URI, a byte array or a byte stream, or the stream itself; `:encoding` names the charset (UTF-8 by default), `:append true` appends a writer's text |
| `input-stream`, `output-stream` | `(input-stream x & opts)`: a buffered byte stream over a path, a File, a URL, a URI or a byte array, or the byte stream itself; `:append true` appends an output stream's bytes |
| `copy` | `(copy input output & opts)`: what a byte stream, a byte array, a reader, a File or a string holds, written to a byte stream, a writer or a File, characters in `:encoding`; any other pair is the oracle's `IllegalArgumentException` |
| `delete-file` | `(delete-file f & [silently])`: deletes the file, answering `true`; when it cannot, answers `silently` if that is truthy and otherwise throws a `java.io.IOException` |
| `make-parents` | `(make-parents f & more)`: makes the missing directories above the File `(file f & more)`, answering whether it made any |
| `resource` | `(resource name)`: the URL of the file or directory the source path holds under `name` ([Resources](#resources)), or `nil` |
| `make-reader`, `make-writer`, `make-input-stream`, `make-output-stream` | The `IOFactory` protocol the four stream functions call, with their options as a map |
| `default-streams-impl` | The `IOFactory` methods to extend a type with: a reader over its input stream, a writer over its output stream, and a refusal of both byte streams |

[slurp](slurp.md) and [spit](spit.md) open what no path names through `reader` and `writer`,
as in Clojure, and [file-seq](file-seq.md) walks a directory tree of Files.

```console
clojure> (require '[clojure.java.io :as io])
nil
clojure> (def f (io/file "/tmp/notes" "a.txt"))
#'user/f
clojure> (io/make-parents f)
true
clojure> (with-open [w (io/writer f)] (.write w "one\ntwo\n"))
nil
clojure> (with-open [r (io/reader f)] (vec (line-seq r)))
["one" "two"]
clojure> (with-open [in (io/input-stream f)] (.read in))
111
clojure> (io/copy f (io/file "/tmp/notes/b.txt"))
nil
clojure> (sort (map str (file-seq (io/file "/tmp/notes"))))
("/tmp/notes" "/tmp/notes/a.txt" "/tmp/notes/b.txt")
clojure> (io/delete-file "/tmp/notes/b.txt")
true
```

## Files, URLs and URIs

A File is its path, normalized as `java.io.File` normalizes one on Unix (no doubled or
trailing slash). It prints as `#object[java.io.File "path"]`, `str` answers the path, and two
Files are `=` when their paths are. Its methods answer from the path -- `getName`, `getParent`,
`getParentFile`, `getPath`, `isAbsolute`, `getAbsolutePath`, `toURI`,
`toURL`, `compareTo` -- or from the file system: `getCanonicalPath` (every symbolic link
resolved, a part that does not exist kept as spelled), `exists`, `isFile`, `isDirectory`, `length`,
`lastModified`, `canRead`, `isHidden`, `list`, `listFiles`, `mkdir`, `mkdirs`,
`createNewFile`, `renameTo`, `delete`. `(java.io.File. path)` and
`(java.io.File. parent child)` make the same value, and the file stream constructions
(`java.io.FileReader.`, `FileWriter.`, `FileInputStream.`, `FileOutputStream.`, over a path or
a File) the namespace's streams.

A URL keeps its spelling and answers `getProtocol`, `getHost`, `getPort`, `getPath`,
`getFile`, `getQuery`, `getRef`, `getAuthority` and `getUserInfo` like `java.net.URL`; a
spelling of no known protocol is the oracle's `java.net.MalformedURLException`. A `file:` URL
opens its file, an `http:` or `https:` URL its reply ([HTTP URLs](#http-urls)). A URI answers
`getScheme` and `getPath`, `toURL` its URL, and `uri?` is true of it. `class` of each answers its class's keyword (`:java.io.File`), and `instance?`, a
multimethod on `class` and a protocol extended to the class take the value, its supers
included (`java.io.InputStream` for a byte stream).

```clojure
(require '[clojure.java.io :as io])
(io/file "src" "app" "core.clj")
; => #object[java.io.File "src/app/core.clj"]
(.getName (io/file "src/app/core.clj"))
; => "core.clj"
(str (.getParentFile (io/file "src/app/core.clj")))
; => "src/app"
(= (io/file "a/b/") (io/file "a" "b"))
; => true
(str (io/as-url (io/file "/tmp/a b.txt")))
; => "file:/tmp/a%20b.txt"
(.getPort (io/as-url "https://example.com:8080/x?q=1"))
; => 8080
```

## Streams

A reader or writer over a file is a character stream like `*in*` and `*out*`, so `line-seq`,
`read`, `.readLine`, a `binding` of `*in*` or `*out*` and `with-open` take it. A byte stream
answers `read` (the next octet, `-1` past the end), `read` into a byte array or a part of one
(the count read, `-1` past the end), `readNBytes`, `readAllBytes` (a byte array), `available`,
`skip`, `transferTo`, `write` of an int's low octet or of a byte array or a part of one,
`flush` and `close`. A reader over a byte stream decodes it, and a writer over one encodes its
text into it when flushed or closed. `:encoding` names UTF-8, ISO-8859-1 or US-ASCII (or one
of their aliases); any other name is the oracle's `java.io.UnsupportedEncodingException`.

`(java.io.ByteArrayInputStream. bytes)` reads a byte array in place,
`(java.io.ByteArrayInputStream. bytes off len)` the part of it from `off`, and
`(java.io.ByteArrayOutputStream.)` gathers the octets written to it: `toByteArray` answers a
copy of them, `size` their count, `toString` (like `str`) their text in UTF-8 or the charset
named, `writeTo` writes them to another byte stream and `reset` empties it. As in Java,
closing either changes nothing. A stream over octets (a byte array, a resource) keeps the
position `mark` takes for `reset`; a stream over a file keeps none (`markSupported` is
false).

```clojure
(def out (java.io.ByteArrayOutputStream.))
(.write out (.getBytes "héllo"))
(.write out 33)
(vec (.toByteArray out))
; => [104 -61 -87 108 108 111 33]
(str out)
; => "héllo!"
(let [in (java.io.ByteArrayInputStream. (.toByteArray out)) buf (byte-array 3)]
  [(.read in buf) (vec buf) (vec (.readAllBytes in))])
; => [3 [104 -61 -87] [108 108 111 33]]
```

## HTTP URLs

An `http:` or `https:` URL -- a URL, a URI or a string spelling one -- is read through
[`rontolisp:fetch`](../../guides/http-fetch.md) the way the oracle's
`java.net.HttpURLConnection` reads it: `slurp`, `reader`, `input-stream` and a URL's
`.openStream` send a GET, follow a redirect (300, 301, 302, 303, 307) to a URL of the same
protocol, and refuse a reply of status 400 or more where the stream opens -- 404 and 410 as
`java.io.FileNotFoundException` of the URL, any other as `java.io.IOException` naming the code;
the 20th redirect is `java.net.ProtocolException`. The body is read as it arrives, decoded in
`:encoding` (UTF-8 by default) whatever charset the reply names, and never decompressed.
`input-stream` answers a `java.io.BufferedInputStream` over it, `.openStream` the connection's
own stream. Writing to one is refused in the oracle's words.

A program reads such a URL when it writes one as a string literal in the form a read opens --
`(slurp "https://...")`, `(io/reader (str "https://" host path))`,
`(.openStream (io/as-url "https://..."))` -- or when it requires `rontolisp.http-urls`, a
namespace that defines nothing. Either makes the program use fetch from its start, so its target
must carry fetch (the targets of the [HTTP client](http-client.md)): a Preview 1 module, and a
`--no-wasi` one without `--host-fetch`, is refused when it compiles. In any other program,
reading an `http:` URL is an `UnsupportedOperationException` naming both ways in; such a program
uses no fetch, so a component reading only files imports no `wasi:http`.

```clojure
;; a URL written where it is read: the program reads it through rontolisp:fetch
(println (subs (slurp "https://httpbin.ik.am/get") 0 1))
(println (try (slurp "https://httpbin.ik.am/status/404")
              (catch java.io.FileNotFoundException e :not-found)))
```

```
{
:not-found
```

A URL the program computes is read once it requires the namespace:

```console
$ cat get.clj
(ns get (:require [rontolisp.http-urls]))
(println (subs (slurp (first *command-line-args*)) 0 15))
$ rontolisp get.clj -- https://example.com/
<!doctype html>
```

## Resources

`resource` looks a name up on the source path -- the roots of the program's project and of its
dependencies, directories and jars ([Projects](../semantics.md#projects-depsedn)) -- where the
oracle looks on the class path, which the same project fills with the same roots. A directory's
file or subdirectory answers its `file:` URL, a jar's entry its `jar:file:...!/name` URL (a
directory entry `name/` answers `name` too). A name written as a string literal is found while
the program compiles, and the contents found travel with the program octet for octet, so they
read the same wherever the program runs, a WASM module included. A name the program computes
is looked up when it runs, in each root of the source path in order, a jar's entries included;
reading the `jar:` URL it answers opens the entry from the jar. On WASM those directories and
jars need a `--dir` preopen. A program naming `resource` only with string literals carries no
lookup.

```console
$ cat resources/config.edn
{:port 8080}
$ cat src/app/main.clj
(ns app.main (:require [clojure.java.io :as io] [clojure.edn :as edn]))
(prn (edn/read-string (slurp (io/resource "config.edn"))))
$ rontolisp src/app/main.clj        # deps.edn holds {:paths ["src" "resources"]}
{:port 8080}
```

## Protocols

`Coercions` and `IOFactory` are protocols a program extends like the oracle's: a type extended
to `Coercions` is a File to `file`, one extended to `IOFactory` opens through its own methods,
and `slurp` and `spit` reach them too. `extend` takes `default-streams-impl` with a method
replaced.

```clojure
(require '[clojure.java.io :as io])
(defrecord Doc [text])
(extend Doc io/IOFactory
  (assoc io/default-streams-impl
         :make-reader (fn [d _] (java.io.BufferedReader. (java.io.StringReader. (:text d))))))
(slurp (->Doc "hello"))
; => "hello"
(with-open [r (io/reader (->Doc "a\nb"))] (vec (line-seq r)))
; => ["a" "b"]
```

## Differences

- A File, a URL, a URI and a stream print as the oracle's `#object[...]` without the identity
  hash, and `str` of a stream answers its class name (the oracle's `Class@hash` without the
  hash). `class` answers a keyword, like for every value ([Deviations](../deviations.md)).
- `.hashCode` of a URL or a URI is its spelling's `String.hashCode`, where the oracle hashes
  its parts, and two are `=` when their spellings are, where `java.net.URL` compares resolved
  hosts. A File's `.hashCode` is the oracle's.
- An `http:` or `https:` URL is read only by a program that uses fetch for it
  ([HTTP URLs](#http-urls)), where the oracle reads one in any program. Likewise a `jar:` URL is
  read where `resource` found it while the program compiled, or in a program that looks a
  computed name up on a source path holding a jar. Reading a URL of another protocol than
  `file:`, `http:`, `https:` and `jar:` is refused by name; the oracle opens a connection.
- Reading an `http:` URL, a transport failure is a `java.io.IOException` carrying the
  transport's message (the oracle's `java.net.ConnectException` and `UnknownHostException` are
  ones); a 305 reply is not followed (the oracle retries through the proxy it names); the
  request carries fetch's `User-Agent`; a reply that is not well-formed UTF-8 is decoded as
  `rontolisp:read-all` decodes it, where the oracle puts U+FFFD for each malformed sequence; and
  `.available` answers what has arrived, 0 before the first read.
- `:encoding` knows three charsets, where the oracle knows the JDK's.
- `input-stream` and `output-stream` answer a `ByteArrayInputStream` or a
  `ByteArrayOutputStream` itself, where the oracle wraps it in a buffered stream, and a
  stream over a file or an `http:` URL keeps no `mark`.
- `resource` never consults a class loader given.
- `(java.net.URL. s)` and `(java.net.URI. s)` construct host objects (the interpreter and the
  JVM); `as-url` makes the URL every backend has, and `.toURI` of it the URI.
- `lastModified` answers whole seconds (a multiple of 1000), where the oracle may answer
  milliseconds.
- On WASM `getAbsolutePath` of a relative File is refused (no working directory). `canRead`
  answers whether the file exists.
- `line-seq` also takes a File, a URL and a byte stream, as it takes a path
  ([line-seq](line-seq.md)), where the oracle takes only a reader.
