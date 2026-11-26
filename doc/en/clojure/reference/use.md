# use

`(use 'clause ...)`

Loads namespaces and refers the names each quoted clause lists, answering `nil` --
`(:only [...])` narrows them and `(:exclude [...])` subtracts from them,
the same refer wiring `ns` does. Without a filter it refers every public var of the
namespace (never a private one). A [built-in namespace](namespaces.md#built-in-namespaces)
needs no file, any other namespace loads from its file on the source path;
a bare library symbol refers all of it; an unknown namespace is an error.
An unquoted vector spec is accepted too, though real Clojure rejects it.
A prefix list `'(prefix [sub ...])` wires each member under the prefix, quoted or bare, and so
does a vector whose second element is no keyword (`'[clojure [set :as s] walk]`), like the oracle.
A computed spec (a local or var naming the library, a call answering it) and every
application as a value load when the call runs on the interpreter, which keeps the
program's lowering; a compiled program refuses them at run time, since its libraries load
while it lowers (of no spec, the oracle's `Nothing specified to load` either way).

```clojure
(use '[clojure.string :only [upper-case]])
(println (upper-case "hi")) ; HI
```
