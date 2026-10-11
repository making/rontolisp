# WIT contracts (rontolisp.wit)

`rontolisp.wit` binds a program to a WIT file read when the program compiles: `import` makes
the functions of a WIT interface vars, `export` implements a WIT world with the program's vars,
and `provide` binds an imported interface's implementation where the program provides it itself.
They lower to the directives of the [WIT contracts guide](../../guides/wit-contracts.md)
(`rontolisp:wit-import`, `rontolisp:wit-export`, `rontolisp:wit-provide`), so every backend
binds the interface the way it binds a Common Lisp program's. Require it like
`clojure.string`.

| Name | Example | Result |
|---|---|---|
| `rontolisp.wit/import` | `(import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})` | `kv/open`, `kv/bucket-get`, ... are vars |
| `rontolisp.wit/export` | `(export "greeter.wit")` | each export of the world is the var its label names |
| `rontolisp.wit/provide` | `(provide "wasi:keyvalue/store@0.2.0-draft" store)` | the interface's calls reach `store` |

```console
$ cat hits.clj
(ns hits (:require [rontolisp.wit :as wit]))

(wit/import "kv.wit" {:interface "wasi:keyvalue/store@0.2.0-draft" :as kv})

(let [bucket (kv/open "")]
  (kv/bucket-set bucket "hits" (.getBytes "42"))
  (println (String. (kv/bucket-get bucket "hits"))))
$ rontolisp hits.clj -o hits.wasm --component
$ wasmtime run -S keyvalue=y hits.wasm
42
```

Both refer to their WIT file relative to the file that names it, like `load`.

## Where it runs

| Target | `import` | `export` |
|---|---|---|
| interpreter, JVM | each function calls the interface's provider ([provide](wit-provide.md)) | the world is checked against the program |
| WASM core module (`-o out.wasm`, `--no-wasi`) | one host import per function | an export per world export |
| `--component` | a component import, called through the canonical ABI | typed component exports |

## What crosses

A WIT value crosses as the Clojure value below, converted on the way across both ways -- for
a caller and for a [provider](wit-provide.md) alike. A label keeps the WIT's spelling, so the
case `DNS-error` is the keyword `:DNS-error`.

| WIT | Clojure |
|---|---|
| `s8` ... `u64`, `f32`, `f64` | a number |
| `bool` | `true` or `false` |
| `char` | a character |
| `string` | a string |
| `list<u8>` | a [byte array](byte-array.md) |
| a resource handle (`own`, `borrow`) | an opaque integer |
| `option<T>` | the value, or `nil` |
| `record` | a map holding each field under its keyword: `{:port 0 :address [127 0 0 1]}` |
| `enum` | the case's keyword: `:ipv4` |
| `variant` | a case without payload is its keyword, `:get`; a case with one `[:case payload]`, `[:other "PATCH"]` |
| `flags` | a set of keywords: `#{:read :write}` |
| `tuple<...>`, `list<T>` | a vector |
| `result<T, E>`, as an argument or inside another value | `[:ok v]` or `[:error e]`; an arm without payload is `:ok` or `:error` |
| `result<T, E>`, as a function's result | the ok value; the error arm throws an `ExceptionInfo` whose `ex-data` holds the error value under `:rontolisp.wit/error` |

Going to the host, a list, a tuple and flags take any collection (a vector, a list, a seq, a
set), and a record any map; a field the map lacks is `nil`, an option's none. A value of no
shape of its type throws an `IllegalArgumentException` naming the type; a `list<u8>` takes
only a byte array, anything else throwing the `ClassCastException` of a cast to `byte[]`. The
error arm's exception names the member, and with the `rontolisp.wit` alias its value is
`(::wit/error (ex-data e))`:

```console
$ cat bind.clj
(ns bind (:require [rontolisp.wit :as wit]))

(wit/import "sockets.wit" {:interface "wasi:sockets/types@0.3.0" :as sock})

(let [s (sock/tcp-socket-create :ipv4)]
  (sock/tcp-socket-bind s [:ipv4 {:port 0 :address [127 0 0 1]}])
  (println (first (sock/tcp-socket-get-local-address s)))
  (try (sock/tcp-socket-bind s [:ipv4 {:port 0 :address [127 0 0 1]}])
       (catch clojure.lang.ExceptionInfo e
         (println (ex-message e))
         (println (::wit/error (ex-data e))))))
$ rontolisp bind.clj -o bind.wasm --component
$ wasmtime run -S inherit-network=y bind.wasm
:ipv4
tcp-socket-bind of wasi:sockets/types@0.3.0 answered its error arm
:invalid-state
```

A `list<u8>` crosses as its octets, both ways, on every target: a component, a provider, and a
WASM core module, whose glue (`--emit-js-glue`) hands the host a `Uint8Array` and takes one back
(text as its UTF-8 encoding). Octets that are not valid UTF-8 cross exact. A Common Lisp
provider's string arrives as its UTF-8 encoding, and a Common Lisp caller of a Clojure provider
gets the octets as an `(unsigned-byte 8)` vector.

A member whose types reach a stream or a future is not bound, and an `async func` is not
either. A reference to one is refused when the program compiles, naming the WIT line:

```console
$ rontolisp app.clj
error: app.clj:4:1: feed of example:geo/api@0.1.0 takes option<stream<u8>> (parameter 'body'), an option carrying a stream, which the Clojure tier does not carry yet (geo.wit:8)
```

Each target narrows it further the way it does for Common Lisp. A WASM core module's import
carries the integers up to 32 bits, the floats, `bool`, `string`, `list<u8>` and handles, and a
member reaching beyond them is refused where the program calls it; a component's import carries
every row but flags and an argument list of anything but `u8`; a world's exports carry the
integers, `f64`, `bool` and `string`.
