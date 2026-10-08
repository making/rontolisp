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
  (kv/bucket-set bucket "hits" "42")
  (println (kv/bucket-get bucket "hits")))
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

A WIT value crosses as the Clojure value below. A `bool` is converted on the way across,
like a `:bool` of [rontolisp.wasm](wasm.md#types); every other row is the same value in both
languages.

| WIT | Clojure |
|---|---|
| `s8` ... `u64`, `f32`, `f64` | a number |
| `bool` | `true` or `false` |
| `char` | a character |
| `string` | a string |
| `list<u8>` | a string, one character per byte |
| a resource handle (`own`, `borrow`) | an opaque integer |
| `option<T>` | the value, or `nil` |
| `result<T, E>`, as a result | the ok value; the error arm throws, and `(catch Exception e ...)` takes it |

A member whose types reach beyond the table -- a record, a variant, an enum, flags, a tuple, a
list of anything but `u8`, a stream or a future, a `result` argument -- is not bound, and an
`async func` is not either. A reference to one is refused when the program compiles, naming the
WIT line:

```console
$ rontolisp app.clj
error: app.clj:4:1: plot of example:geo/api@0.1.0 takes point (parameter 'p'), a record, which the Clojure tier does not carry yet (geo.wit:5)
```

Each target narrows it further the way it does for Common Lisp: a WASM core module's import
carries the integers up to 32 bits, the floats, `bool`, `string`, `list<u8>` and handles, and a
world's exports carry the integers, `f64`, `bool` and `string`.
