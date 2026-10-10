# WASM host functions (rontolisp.wasm)

`rontolisp.wasm` declares what crosses between a compiled WebAssembly module and its host:
`defimport` binds a var to a function the host provides, and `export` -- or a `defn`'s
`{:wasm/export ...}` metadata -- hands a var to the host. Both lower to the directives of the
[WASM host boundary guide](../../guides/wasm-host-boundary.md) (`rontolisp:wasm-import`,
`rontolisp:wasm-export`), so a Clojure module and a Common Lisp module making the same
declarations compile to the same bytes. Require it like `clojure.string`.

| Name | Example | Result |
|---|---|---|
| `rontolisp.wasm/defimport` | `(defimport add {:from "host" :params [:int :int] :returns :int})` | `add` calls the host's `add` |
| `rontolisp.wasm/export` | `(export add10 {:params [:int] :returns :int})` | the module exports `add10` |
| `:wasm/export` metadata | `(defn add10 {:wasm/export {:params [:int] :returns :int}} [n] ...)` | the module exports `add10` |

```console
$ cat main.clj
(ns main (:require [rontolisp.wasm :as wasm]))

(wasm/defimport add {:from "host" :params [:int :int] :returns :int})

(defn add10 {:wasm/export {:params [:int] :returns :int}} [n]
  (add n 10))
$ rontolisp main.clj -o main.wasm --no-wasi
$ rontolisp host.clj -o host.wasm --no-wasi     # a module exporting "add"
$ wasmtime run --preload host=host.wasm --invoke add10 main.wasm 32
42
```

On the interpreter and the JVM an export is an ordinary function and an import has no host
to call:

```clojure
(require '[rontolisp.wasm :as wasm])

(wasm/defimport add {:from "host" :params [:int :int] :returns :int})

(defn add10 {:wasm/export {:params [:int] :returns :int}} [n]
  (+ n 10))

(println (add10 32))
(println (try (add 1 2) (catch UnsupportedOperationException e (ex-message e))))
```

```
42
add is a host function (rontolisp.wasm/defimport): only a compiled WASM module can call it
```

## Types

The type keywords are the boundary's: `:int`, `:long`, `:s8` ... `:u64`, `:float`, `:bool`,
`:string`, `:s-expr`, `:bytes`, `:extern` (imports only) and, for a result, `:void` -- what a
declaration without `:returns` means. A value Clojure spells differently from the boundary is
converted on the way across:

| Type | To the host | From the host |
|---|---|---|
| `:bool` | `false` and `nil` cross as false | false arrives as `false`, not `nil` |
| `:s-expr` | the text `pr-str` prints | the value `read-string` reads |
| `:bytes` | a [byte array](byte-array.md)'s octets; anything else throws `ClassCastException` | a byte array |

So vectors, maps, keywords and `false` round-trip through `:s-expr`, which crosses as the same
UTF-8 text a `:string` does on every host. A declaration with none of these types lowers to
exactly the directive a Common Lisp source would write. `:async` is refused: the future a
suspending crossing answers is no Clojure future yet.

`:bytes` transfers raw octets, so `ff` crosses as `ff` where a `:string` would be decoded as
UTF-8. As a result it is the caller's buffer: an import answering `:bytes` takes, after its
declared parameters, the byte array the host fills, and answers the full length of the value --
a length past the array's size means the array was too small. An export answering `:bytes`
answers a byte array, which its host reads into the buffer it passes:

```console
$ cat bin.clj
(ns bin (:require [rontolisp.wasm :as wasm]))

(wasm/defimport read-chunk {:from "host" :as "readChunk" :params [:int] :returns :bytes})

(defn checksum {:wasm/export {:params [:bytes] :returns :int}} [data]
  (reduce + (map #(if (neg? %) (+ % 256) %) data)))

(defn first-chunk {:wasm/export {:as "firstChunk" :params [:int] :returns :string}} [id]
  (let [buf (byte-array 4096)
        n (read-chunk id buf)]
    (String. buf 0 (min n 4096) "UTF-8")))
$ rontolisp bin.clj -o bin.wasm --no-wasi --emit-js-glue
```

Like the Common Lisp `:bytes` type, it crosses only a WASM core module of the GC backend; on
the interpreter and the JVM an import answering `:bytes` is a stub taking the byte array too.

## Where it runs

| Target | `defimport` | `export` |
|---|---|---|
| WASM core module (`-o out.wasm`, `--no-wasi`) | an import of the module | an export of the module |
| `--component` | refused by `rontolisp:wasm-import`: a component calls a WIT interface ([rontolisp.wit](wit.md)) | a typed component export |
| interpreter, JVM | a function throwing `UnsupportedOperationException` | none; the function stays callable |

`--emit-js-glue` writes the JavaScript half of a `--no-wasi` module's boundary as it does for a
Common Lisp module. A complete pair, with a node driver over the generated file, is
[`examples/clojure/host-boundary/`](https://github.com/making/rontolisp/tree/develop/examples/clojure/host-boundary).
