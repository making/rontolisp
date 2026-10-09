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
| `reader`, `writer` | `(reader x & opts)`: a buffered character stream over a path, a File, a URL, a URI or a byte stream, or the stream itself; `:encoding` names the charset (UTF-8 by default), `:append true` appends a writer's text |
| `input-stream`, `output-stream` | `(input-stream x & opts)`: a buffered byte stream over a path, a File, a URL or a URI, or the byte stream itself; `:append true` appends an output stream's bytes |
| `copy` | `(copy input output & opts)`: what a byte stream, a reader, a File or a string holds, written to a byte stream, a writer or a File, characters in `:encoding`; any other pair is the oracle's `IllegalArgumentException` |
| `delete-file` | `(delete-file f & [silently])`: deletes the file, answering `true`; when it cannot, answers `silently` if that is truthy and otherwise throws a `java.io.IOException` |
| `make-parents` | `(make-parents f & more)`: makes the missing directories above the File `(file f & more)`, answering whether it made any |
| `resource` | `(resource name)`: the URL of the file the source path holds under `name` ([Resources](#resources)), or `nil` |
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
`getParentFile`, `getPath`, `isAbsolute`, `getAbsolutePath`, `getCanonicalPath`, `toURI`,
`toURL`, `compareTo` -- or from the file system: `exists`, `isFile`, `isDirectory`, `length`,
`lastModified`, `canRead`, `isHidden`, `list`, `listFiles`, `mkdir`, `mkdirs`,
`createNewFile`, `renameTo`, `delete`. `(java.io.File. path)` and
`(java.io.File. parent child)` make the same value, and the file stream constructions
(`java.io.FileReader.`, `FileWriter.`, `FileInputStream.`, `FileOutputStream.`, over a path or
a File) the namespace's streams.

A URL keeps its spelling and answers `getProtocol`, `getHost`, `getPort`, `getPath`,
`getFile`, `getQuery`, `getRef`, `getAuthority` and `getUserInfo` like `java.net.URL`; a
spelling of no known protocol is the oracle's `java.net.MalformedURLException`. A `file:` URL
opens its file. A URI answers `getScheme` and `getPath`, `toURL` its URL, and `uri?` is true
of it. `class` of each answers its class's keyword (`:java.io.File`), and `instance?`, a
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
answers `read` (the next octet, `-1` past the end), `available`, `skip`, `transferTo`, `write`
of an int's low octet, `flush` and `close`. A reader over a byte stream decodes it, and a
writer over one encodes its text into it when flushed or closed. `:encoding` names UTF-8,
ISO-8859-1 or US-ASCII (or one of their aliases); any other name is the oracle's
`java.io.UnsupportedEncodingException`.

## Resources

`resource` looks a name up on the source path -- the roots of the program's project and of its
dependencies, directories and jars ([Projects](../semantics.md#projects-depsedn)) -- where the
oracle looks on the class path, which the same project fills with the same roots. A name
written as a string literal is found while the program compiles: a directory's file answers
its `file:` URL, a jar's entry its `jar:file:...!/name` URL, and the text found travels with the
program, so it reads the same wherever the program runs, a WASM module included. A name the
program computes is looked up when it runs, below the source path's directories.

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
- Reading a URL of another protocol than `file:` is refused by name, but for a `jar:` URL
  `resource` answered; the oracle opens a connection.
- No byte array exists here: `read` into a buffer, `readAllBytes` and `write` of a byte array
  are refused by name. `:encoding` knows three charsets, where the oracle knows the JDK's.
- `resource` finds a name the program computes below the source path's directories only, not
  inside a jar, and never consults a class loader given.
- `(java.net.URL. s)` and `(java.net.URI. s)` construct host objects (the interpreter and the
  JVM); `as-url` makes the URL every backend has, and `.toURI` of it the URI.
- `lastModified` answers whole seconds (a multiple of 1000), where the oracle may answer
  milliseconds.
- On WASM `getAbsolutePath` of a relative File is refused (no working directory). `canRead`
  answers whether the file exists.
- `line-seq` also takes a File, a URL and a byte stream, as it takes a path
  ([line-seq](line-seq.md)), where the oracle takes only a reader.
