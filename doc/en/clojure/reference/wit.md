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

Both refer to their WIT file relative to the file that names it, like `load`. The path may name a
WIT package directory as it is published: its `*.wit` files are one package, and each entry of its
`deps/` a package its `use` clauses may name.

An export reads the program's top level -- a `def` it closes over, the decimal-digit table
`Integer/parseInt` and `parse-long` read. A command component runs no top level when a host calls
an export directly (`wasmtime run --invoke`, a composed component), so such an export traps there.
`(export "num.wit" {:reactor true})` makes the exports the entry points instead: the top level runs
once at the first export call, and no `wasi:cli/run` is lifted. The default stays the command,
which is what a program that also prints from its top level needs; a WASM command module refuses
the option.

```console
$ cat num.clj
(ns num (:require [rontolisp.wit :as wit]))

(wit/export "num.wit" {:reactor true})

(defn parse [text] (Integer/parseInt text))
$ rontolisp num.clj -o num.wasm --component
$ wasmtime run --invoke 'parse("42")' num.wasm
42
```

## Where it runs

| Target | `import` | `export` |
|---|---|---|
| interpreter, JVM | each function calls the interface's provider ([provide](wit-provide.md)) | the world is checked against the program |
| WASM core module (`-o out.wasm`, `--no-wasi`) | one host import per function | an export per world export |
| `--component` | a component import, called through the canonical ABI | typed component exports |

## What crosses

A WIT value crosses as the Clojure value below, converted on the way across both ways -- for
a caller, a [provider](wit-provide.md) and a world's export alike. A label keeps the WIT's spelling, so the
case `DNS-error` is the keyword `:DNS-error`.

| WIT | Clojure |
|---|---|
| `s8` ... `u64`, `f32`, `f64` | a number |
| `bool` | `true` or `false` |
| `char` | a character |
| `string` | a string |
| `list<u8>` | a [byte array](byte-array.md) |
| a resource handle (`own`, `borrow`) | an opaque integer; in a world's export, a handle of a resource the world exports is the value its constructor answered ([Resources](#resources)) |
| `option<T>` | the value, or `nil` |
| `record` | a map holding each field under its keyword: `{:port 0 :address [127 0 0 1]}` |
| `enum` | the case's keyword: `:ipv4` |
| `variant` | a case without payload is its keyword, `:get`; a case with one `[:case payload]`, `[:other "PATCH"]` |
| `flags` | a set of keywords: `#{:read :write}` |
| `tuple<...>`, `list<T>` | a vector |
| `result<T, E>`, as an argument or inside another value | `[:ok v]` or `[:error e]`; an arm without payload is `:ok` or `:error` |
| `result<T, E>`, as a function's result | the ok value; the error arm is an `ExceptionInfo` whose `ex-data` holds the error value under `:rontolisp.wit/error`, thrown to a caller, thrown by a provider or an export |

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

[`examples/clojure/wit-types/`](https://github.com/making/rontolisp/tree/develop/examples/clojure/wit-types) crosses an enum, flags, variants, a
record, an option, a list of tuples and a `result` both ways against a provider.

A world's export takes and answers the same values: its var is called with the Clojure values,
and a `result` it answers is its value, or the error arm when it throws that `ExceptionInfo` --
any other exception traps the call.

```console
$ cat shapes.wit
package example:shapes@0.1.0;

world shapes {
  record point { x: s32, y: s32 }

  export flip: func(p: point) -> point;
  export measure: func(text: string) -> result<u32, string>;
}
$ cat shapes.clj
(ns shapes (:require [rontolisp.wit :as wit]))

(wit/export "shapes.wit")

(defn flip [{:keys [x y]}] {:x y :y x})

(defn measure [text]
  (if (seq text)
    (count text)
    (throw (ex-info "nothing to measure" {::wit/error "empty text"}))))
$ rontolisp shapes.clj -o shapes.wasm --component
$ wasmtime run --invoke 'flip({x: 1, y: 2})' shapes.wasm
{x: 2, y: 1}
$ wasmtime run --invoke 'measure("")' shapes.wasm
err("empty text")
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
every row but an argument list of anything but `u8`. A component's export carries
every row, a handle of a resource the component imports only where the component imports
(not under `--no-wasi`), and a world whose types reach a stream or a future is refused at the
WIT line; a `--no-wasi` module's export carries the same through its `--emit-js-glue` glue
(flags as an object of booleans), but no handle; a WASI module's and `--no-gc`'s
exports carry the integers, `f64`, `bool` and `string`
([`rontolisp:wit-export`](../../reference/functions/rontolisp-wit-export.md#compound-types)).

## Resources

A resource an exported interface declares is implemented by vars named by its member
names, as `import` names them on the other side: `counter-new` for the constructor,
`counter-add` for a method (the resource first), `counter-merge` for a static function.
`counter-drop`, where the namespace defines it, is called with the value each time a holder
releases a handle; a handle handed over to an export is not released. The value the
constructor's var answers is the resource -- any value, an atom here -- and wherever the WIT
names the resource (a parameter, a result, a record's field, a list's element) the var takes
or answers that value.

```console
$ cat counters.wit
package example:counters@0.1.0;

interface registry {
  resource counter {
    constructor(name: string, start: u32);
    add: func(by: u32) -> u32;
    merge: static func(a: counter, b: borrow<counter>) -> counter;
  }

  total: func(counters: list<borrow<counter>>) -> u32;
}

world counters {
  export registry;
}
$ cat counters.clj
(ns counters (:require [rontolisp.wit :as wit]))

(wit/export "counters.wit" {:reactor true})

(defn counter-new [name start] (atom {:name name :value start}))
(defn counter-add [self by] (:value (swap! self update :value + by)))
(defn counter-merge [a b] (swap! a update :value + (:value @b)) a)
(defn total [counters] (reduce + (map (comp :value deref) counters)))
$ rontolisp counters.clj -o counters.wasm --component
```

A resource of an interface the world does not export, named with `use` by an exported
interface or the world, is one the component imports: its handle is the integer an `import`
of that interface takes, as `reg/counter-read` does below. A borrowed one is released when
the var has answered, so the program does not drop it; an owned one is the program's.

```console
$ cat reporter.clj
(ns reporter (:require [rontolisp.wit :as wit]))

(wit/import "wit/counters.wit" {:interface "example:counters/registry" :as reg})

(defn summary [c]
  (let [{:keys [name value]} (reg/counter-read c)]
    (str name " is at " value)))

(wit/export "wit/counters.wit" {:world "reporter" :reactor true})
```

[`examples/wit/resource/counters.clj`](https://github.com/making/rontolisp/tree/develop/examples/wit/resource)
implements the world of the Lisp example beside it, which its command calls once composed;
the Common Lisp side: [Resources](../../reference/functions/rontolisp-wit-export.md#resources).
